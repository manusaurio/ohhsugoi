package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.*
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalStringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondingPaginator
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URL

class MangaExtension: Extension(), KoinComponent {
    private val db: MangaDatabase by inject()

    override val name = "manga"

    private fun URL.seemsSafe() =
            protocol == "https" && host in listOf("media.discordapp.net", "cdn.discordapp.com")

    private fun Attachment?.isValidImage(): Boolean =
            this == null || isImage && size < 8000000 && URL(url).seemsSafe()

    class AddMangaArgs : Arguments() {
        val title by string {
            name = "título"
            description = "El título del manga"
        }

        val description by string {
            name = "descripción"
            description ="Descripción del manga"
        }

        val link by string {
            name = "link"
            description ="Link para comprar o leer el manga"
        }

        val demographic by stringChoice {
            name = "demografía"
            description ="Demografía del manga"

            demographics.forEach {
                choice(it, it)
            }
        }

        val tags by string {
            name = "tags"
            description ="Géneros del manga. Dividir por coma: \"tag uno, tag dos\""
        }

        val chapters by long {
            name = "capítulos"
            description ="Cantidad de capítulos del manga"
            minValue = 1
        }

        val pagesPerChapter by optionalLong {
            name = "páginasporcapítulo"
            description ="Cantidad de páginas aproximada por capítulo"
            minValue = 1
        }

        val volumes by optionalLong {
            name = "tomos"
            description = "Cantidad de tomos del manga"
            minValue = 1
        }

        val pagesPerVolume by optionalLong {
            name = "páginasportomo"
            description = "Cantidad de páginas por tomo"
            minValue = 1
        }

        val image by optionalAttachment {
            name = "imagen"
            description = "Portada del manga"
        }
    }

    class SearchMangaArgs : Arguments() {
        val title by optionalString {
            name = "título"
            description = "El título del manga a buscar"
        }

        val tag by optionalString {
            name = "tag"
            description = "Tag por el cual filtrar"
        }

        val demographic by optionalStringChoice {
            name = "demografía"
            description = "Demografía por la cual filtrar"

            demographics.forEach {
                choice(it, it)
            }
        }
    }

    override suspend fun setup() {
        publicSlashCommand(::SearchMangaArgs) {
            name = "buscar"
            description = "Busca un manga por nombre, tag, y/o demografía"
            guild(Snowflake(System.getenv("KORD_WEEB_SERVER")!!))

            action {
                val (title, tag, demographic) = with (arguments) { Triple(title, tag, demographic) }

                if (listOf(title, tag, demographic).all { it == null }) {
                    respond { content = "Debes especificar al menos un criterio." }
                    return@action
                }

                val mangaList = db.searchManga(title, tag, demographic, 3)

                when {
                    mangaList.isEmpty() -> respond {
                        embed {
                            this.title = "Sin resultados"
                            description = "No se encontró nada similar a ${arguments.title}"
                            color = Color(200, 0, 0)
                        }
                    }

                    mangaList.size == 1 -> respond {
                        embeds.add(
                                EmbedBuilder().mangaView(mangaList.first())
                        )
                    }

                    else -> respondingPaginator {
                        mangaList.forEach { manga ->
                            page {
                                mangaView(manga)
                            }
                        }
                    }.send()
                }
            }
        }

        publicSlashCommand(::AddMangaArgs) {
            name = "agregar"
            description = "Agrega un manga con los datos proporcionados"
            guild(Snowflake(System.getenv("KORD_WEEB_SERVER")!!))

            action {
                val tags = arguments.tags
                        .split(',')
                        .filter(String::isNotBlank)
                        .map(String::trim)
                        .toSet()

                val chapters = arguments.chapters
                val volumes = arguments.volumes
                val ppv = arguments.pagesPerVolume

                val ppc = arguments.pagesPerChapter ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1

                val image: Attachment? = arguments.image
                val validImage: Boolean = image.isValidImage()

                val isValid: Boolean = with(arguments) {
                    title.isNotBlank()
                            && description.isNotBlank()
                            && link.isValidURL()
                            && tags.isNotEmpty()
                            && ppc > 0
                            && validImage
                }

                respond {
                    if (isValid) try {
                        db.addManga(
                                title = arguments.title,
                                description = arguments.description,
                                imgURLSource = image?.let { URL(it.url) },
                                link = arguments.link,
                                demographic = arguments.demographic,
                                volumes = volumes,
                                pagesPerVolume = ppv,
                                chapters = chapters,
                                pagesPerChapter = ppc,
                                tags = tags,
                                read = false
                        )
                        content = "Agregado exitosamente."
                        } catch (e: DownloadException) {
                            content = "Error al agregar."
                        } else {
                        // TODO: Point out the reason(s) the arguments validation didn't pass after trying to submit an entry.
                        content = "Error en los argumentos."
                    }
                }
            }
        }
    }
}
