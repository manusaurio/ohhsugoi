package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.*
import ar.pelotude.ohhsugoi.isValidURL
import ar.pelotude.ohhsugoi.makeTitle
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalStringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.types.edit
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondingPaginator
import dev.kord.common.Color
import dev.kord.core.entity.Attachment
import dev.kord.core.kordLogger
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import org.koin.core.component.inject
import java.net.URL

class MangaExtension: Extension(), KordExKoinComponent {
    private val db: MangaDatabase by inject()
    private val config: MangaKogConfiguration by inject()

    override val name = "manga"

    private fun Attachment?.isValidImage(): Boolean =
            this == null || isImage && size < 8000000

    private fun String.toTagSet() =
            this.split(',')
            .filter(String::isNotBlank)
            .map { it.trim().makeTitle() }
            .toSet()

    inner class AddMangaArgs : Arguments() {
        val title by string {
            name = "título"
            description = "El título del manga"
            minLength = config.mangaTitleMinLength
            maxLength = config.mangaTitleMaxLength

            validate {
                failIf("El título está en blanco po") {
                    value.isBlank()
                }
            }
        }

        val description by string {
            name = "descripción"
            description ="Descripción del manga"
            minLength = config.mangaDescMinLength
            maxLength = config.mangaDescMaxLength

            validate {
                failIf("Ahí no hay descripción po") {
                    value.isBlank()
                }
            }
        }

        val link by string {
            name = "link"
            description ="Link para comprar o leer el manga"

            validate {
                failIfNot("El link no es válido") {
                    value.isValidURL()
                }
            }
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
            validate {
                failIf("No hay tags po sacowea") {
                    value.split(',').all(String::isBlank)
                }
            }
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
            validate {
                failIfNot("El archivo excede el límite de peso") {
                    value.isValidImage()
                }
            }
        }
    }

    inner class SearchMangaArgs : Arguments() {
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

    inner class EditArguments: Arguments() {
        val id by long {
            name = "id"
            description = "El id del manga a editar."
        }

        val title by optionalString {
            name = "nuevonombre"
            description = "Nuevo tíutlo del manga"
            minLength = config.mangaTitleMinLength
            maxLength = config.mangaTitleMaxLength
        }

        val image by optionalAttachment {
            name = "imagen"
            description = "Nueva imagen del manga"
            validate {
                failIfNot("El archivo excede el límite de peso") {
                    value.isValidImage()
                }
            }
        }

        val description by optionalString {
            name = "descripción"
            description = "Nueva descripción"
            minLength = config.mangaDescMinLength
            maxLength = config.mangaDescMaxLength
        }

        val link by optionalString {
            name = "link"
            description ="Nuevo link donde comprar o leer el manga"

            validate {
                failIfNot("El link no es válido") {
                    value!!.isValidURL()
                }
            }
        }

        val volumes by optionalLong {
            name = "tomos"
            description = "Nueva cantidad de tomos"
            minValue = 1
        }

        val pagesPerVolume by optionalLong {
            name = "páginasportomo"
            description = "Nueva cantidad de páginas por tomo"
            minValue = 1
        }

        val chapters by optionalLong {
            name = "capítulos"
            description = "Nueva cantidad de capítulos"
            minValue = 1
        }

        val pagesPerChapter by optionalLong {
            name = "páginasporcapítulo"
            description = "Nueva cantidad de páginas por capítulo"
            minValue = 1
        }

        val demographic by optionalStringChoice {
            name = "demografía"
            description = "Nueva demografía del manga"
            demographics.forEach {
                choice(it, it)
            }
        }

        val addTags by optionalString {
            name = "nuevostags"
            description = "Nuevos tags para este título"
            validate {
                failIf("No hay tags po sacowea") {
                    value!!.split(',').all(String::isBlank)
                }
            }
        }

        val removeTags by optionalString {
            name = "removertags"
            description = "Tags a ser removidos"
            validate {
                failIf("No hay tags po sacowea") {
                    value!!.split(',').all(String::isBlank)
                }
            }
        }

        val unsetImage by optionalBoolean {
            name = "sacarimagen"
            description = "Elimina la imagen del manga."
        }
    }

    inner class DeletionArguments: Arguments() {
        val id by long {
            name = "id"
            description = "id del manga a eliminar"
        }
    }

    override suspend fun setup() {
        publicSlashCommand(::SearchMangaArgs) {
            name = "buscar"
            description = "Busca un manga por nombre, tag, y/o demografía"
            guild(config.guild)

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
                            description = "No se encontró nada similar a lo buscado:\n" +
                                    (arguments.title?.let { "\n__Título__: $it" } ?: "")  +
                                    (arguments.demographic?.let { "\n__Demografía__: $it" } ?: "") +
                                    (arguments.tag?.let { "\n__Tag__: $it" } ?: "")

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
            guild(config.guild)

            action {
                val chapters = arguments.chapters
                val volumes = arguments.volumes
                val ppv = arguments.pagesPerVolume

                val ppc = arguments.pagesPerChapter ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1

                val validPpc: Boolean = ppc > 0

                respond {
                    if (validPpc) try {
                        val imgURLSource: URL? = arguments.image?.let { URL(it.url) }
                        val tags = arguments.tags.toTagSet()

                        val id = db.addManga(
                                title = arguments.title,
                                description = arguments.description,
                                imgURLSource = imgURLSource,
                                link = arguments.link,
                                demographic = arguments.demographic,
                                volumes = volumes,
                                pagesPerVolume = ppv,
                                chapters = chapters,
                                pagesPerChapter = ppc,
                                tags = arguments.tags.toTagSet(), // TODO: Make tags be saved in lowercase in db
                                read = false
                        )
                        content = "Agregado exitosamente."
                        embed {
                            // TODO: rewrite this mess! Also,
                            //  the image used here is EPHEMERAL so
                            //  we shouldn't rely on it
                            val a = arguments
                            mangaView(
                                MangaWithTagsData(
                                    MangaData(
                                        id, a.title, java.time.Instant.now().epochSecond, a.description, imgURLSource,
                                        a.link, a.demographic, a.volumes, a.pagesPerVolume, a.chapters, a.pagesPerChapter,
                                        false
                                    ),
                                    tags
                                )
                            )
                        }
                    } catch (e: DownloadException) {
                            content = "Error al agregar."
                    } else {
                        content = "**Error**: Especifica la cantidad de páginas por capítulo o valores en otros argumentos que me permitan computarlo!"
                    }
                }
            }
        }

        publicSlashCommand(::EditArguments) {
            name = "editar"
            description = "Modifica o elimina los campos de un manga"
            guild(config.guild)

            action {
                val flags = mutableListOf<UpdateFlags>()

                arguments.unsetImage?.let { flags.add(UpdateFlags.UNSET_IMG_URL) }

                val currentManga = db.getManga(arguments.id)

                currentManga ?: run {
                    respond {
                        content = "La id ${arguments.id} no pudo ser encontrada"
                    }
                    return@action
                }

                respond {
                    confirmationDialog(
                            "¿Confirmas la edición sobre ${currentManga.title}?",
                            user.asUser(),
                    ) {
                        edit { components { } }

                        val mangaChanges = with(arguments) {
                            MangaChanges(
                                id=id,
                                title=title,
                                description=description,
                                imgURLSource=arguments.image?.let { URL(it.url) },
                                link=link,
                                volumes=volumes,
                                pagesPerVolume=pagesPerVolume,
                                chapters=chapters,
                                pagesPerChapter=pagesPerChapter,
                                demographic=demographic,
                                tagsToAdd=addTags?.toTagSet(),
                                tagsToRemove=removeTags?.toTagSet(),
                                read=null,
                            )
                        }

                        respond {
                            try {
                                db.updateManga(mangaChanges, *flags.toTypedArray())
                                kordLogger.info { "${user.id} edited entry #${mangaChanges.id} (${currentManga.title})" }

                                // TODO: move to Views
                                embed {
                                    title = "Editado [#${currentManga.id}] ${currentManga.title}"
                                    color = Color(0, 200, 0)

                                    description = "__Campos modificados__:\n\n" +
                                            listOf<Pair<String, *>>(
                                                ("Título" to arguments.title),
                                                ("Descripción" to arguments.description),
                                                ("Imagen" to arguments.image),
                                                ("Link" to arguments.link),
                                                ("Tomos" to arguments.volumes),
                                                ("Páginas por capítulo" to arguments.pagesPerChapter),
                                                ("Demografía" to arguments.demographic),
                                                ("Tags (nuevos)" to arguments.addTags),
                                                ("Tags (removidos)" to arguments.removeTags)
                                            )
                                                .filter { it.second != null }
                                                .joinToString("\n") { it.first }
                                }
                            } catch (e: DownloadException) {
                                kordLogger.trace(e) { "Error downloading a cover from ${currentManga.imgURLSource}" }

                                embed {
                                    title = "**Error**"
                                    description = "Hubo un problema descargando la imagen"

                                    color = Color(200, 0, 0)
                                }
                            }
                        }
                    }
                }
            }
        }

        publicSlashCommand(::DeletionArguments) {
            name = "borrar"
            description = "Elimina una entrada de la base de datos"

            action {
                val manga = db.getManga(arguments.id)

                manga ?: run {
                    respond {
                        content = "La id ${arguments.id} no pudo ser encontrada"
                    }
                    return@action
                }

                respond {
                    confirmationDialog(
                        "¿Confirmas la eliminación de **${manga.title}**?",
                        user.asUser()
                    ) {
                        edit { components { } }
                        respond {
                            db.deleteManga(manga.id)
                            content = if (db.deleteManga(manga.id)) "Eliminado **${manga.title}**"
                            else "No se pudo eliminar ${manga.title}."
                        }
                    }
                }
            }
        }
    }
}
