package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.*
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondingPaginator
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.interaction.*
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import java.net.URL
import java.util.*

class MangaExtension(val db: MangaDatabase): Extension() {
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
        val title by string {
            name = "título"
            description = "El título del manga a buscar"
        }
    }

    override suspend fun setup() {
        publicSlashCommand(::SearchMangaArgs) {
            name = "buscar"
            description = "busca un manga por nombre"
            guild(Snowflake(System.getenv("KORD_WEEB_SERVER")!!))

            action {
                val mangaList = db.searchManga(arguments.title, 3)

                when {
                    mangaList.isEmpty() -> respond {
                        embed {
                            title = "Sin resultados"
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


/**
 * Builds a manga view from the scope of an [EmbedBuilder] and returns it.
 *
 * @return The [EmbedBuilder] this function was called from.
 */
fun EmbedBuilder.mangaView(manga: MangaWithTags): EmbedBuilder {
    title = manga.title

    manga.imgURLSource?.let { imgURL ->
        thumbnail {
            url = imgURL.toString()
        }
    }

    field {
        name = "Categorías"

        val tags = manga.tags.takeIf(Set<*>::isNotEmpty)?.joinToString(
                separator = ", ",
                prefix = " ",
                postfix = ".".replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        ) ?: ""
        value = "${manga.demographic}.$tags"
    }

    field {
        name = "Tomos"
        value = manga.volumes?.toString() ?: "?"
        inline = true
    }
    field {
        name = "Páginas por tomo"
        value = manga.pagesPerVolume?.let { "~$it" } ?: "?"
        inline = true
    }
    field {
        name = "Páginas por capítulo"
        value = manga.pagesPerChapter?.let { "~$it" } ?: "?"
        inline = true
    }

    description =
            manga.description +
                    "\n[Leer](${manga.link})"

    footer {
        text = if (manga.read) "Leído por el club." else "No leído por el club."
    }

    return this
}