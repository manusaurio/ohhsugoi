package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.Demographic
import ar.pelotude.ohhsugoi.db.DownloadException
import ar.pelotude.ohhsugoi.db.MangaWithTags
import ar.pelotude.ohhsugoi.koggable.Kog
import dev.kord.common.entity.Snowflake
import dev.kord.common.Locale as Langs
import dev.kord.core.behavior.interaction.respondPublic
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.interaction.*
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.async
import java.net.URL
import java.util.*

class MangaKog(
    val db: ar.pelotude.ohhsugoi.db.MangaDatabase,
    defaultServer: Snowflake = Snowflake(System.getenv("KORD_WEEB_SERVER")!!)
) : Kog(defaultServer) {
    private fun URL.seemsSafe() =
        protocol == "https" && host in listOf("media.discordapp.net", "cdn.discordapp.com")

    private fun Attachment?.isValidImage(): Boolean =
        this == null || isImage && size < 8000000 && URL(url).seemsSafe()

    override suspend fun setup() {
        inputCommand() {
            name = "buscar"
            description = "Busca un manga por título"

            command {
                name(Langs.ENGLISH_UNITED_STATES, "search")
                description(Langs.ENGLISH_UNITED_STATES, "Search manga by title")

                string("texto", "Texto a buscar en los títulos") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "text")
                    description(Langs.ENGLISH_UNITED_STATES, "Text to look up in the titles")
                }
            }

            handler {
                interaction.respondPublic {
                    val searchTerms = interaction.command.strings["texto"]!!
                    val mangaList = db.searchManga(searchTerms)

                    mangaList.firstOrNull()?.let { manga: MangaWithTags ->
                        embed {
                            title = manga.title

                            manga.imgURLSource?. let { imgURL ->
                                thumbnail {
                                    url = imgURL.toString()
                                }
                            }

                            field {
                                name = "Categorías"

                                val tags = manga.tags.takeIf(Set<*>::isNotEmpty)?.joinToString(
                                    separator=", ",
                                    prefix=" ",
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
                        }
                    } ?: embed {
                        title = "No encontrado"
                        description = "No se encontró nada con los términos $searchTerms."
                    }
                }
            }
        }

        inputCommand {
            name = "sugerir"
            description = "Agrega un manga con los datos proporcionados"

            command {
                name(Langs.ENGLISH_UNITED_STATES, "recommend")
                description(Langs.ENGLISH_UNITED_STATES, "Add a manga entry with the supplied data")

                string("título", "El título del manga") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "title")
                    description(Langs.ENGLISH_UNITED_STATES, "The title of the manga")
                }
                // TODO: Store description `minLength` and `maxLength` in db, remove hardcoded values
                //  label: enhancement
                string("descripción", "Una corta descripción del manga") {
                    required = true; minLength = 20; maxLength = 500
                    name(Langs.ENGLISH_UNITED_STATES, "description")
                    description(Langs.ENGLISH_UNITED_STATES, "Brief description of the manga")
                }
                string("link", "Link para comprar o leer el manga.") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "link")
                    description(Langs.ENGLISH_UNITED_STATES, "Website where this title can be read or bought")
                }
                string("demografía", "Demografía del manga.") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "demographic")
                    description(Langs.ENGLISH_UNITED_STATES, "Target demographic of this title")

                    Demographic.values().forEach { n -> choice(n.alias, n.alias) }
                }

                string("tags", "Géneros del manga. Dividir por coma: \"tag uno, tag dos\"") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "tags")
                    description(Langs.ENGLISH_UNITED_STATES, "Genres/themes of this title. Divide by comma: \"first tag, second tag\"")
                }

                int("capítulos", "Cantidad de capítulos") {
                    required = true
                    name(Langs.ENGLISH_UNITED_STATES, "chapters")
                    description(Langs.ENGLISH_UNITED_STATES, "Extension of the manga in number of chapters")
                }

                int("páginasporcapítulo", "Cantidad de páginas por capítulo") {
                    name(Langs.ENGLISH_UNITED_STATES, "pagesperchapter")
                    description(Langs.ENGLISH_UNITED_STATES, "Extension of a single chapter in number of pages")

                }

                int("tomos", "Cantidad de tomos") {
                    name(Langs.ENGLISH_UNITED_STATES, "volumes")
                    description(Langs.ENGLISH_UNITED_STATES, "Extension of the manga in number of volumes.")
                }

                int("páginasportomo", "Cantidad de páginas por tomo") {
                    name(Langs.ENGLISH_UNITED_STATES, "pagespervolume")
                    description(Langs.ENGLISH_UNITED_STATES, "Extension of a single volume in number of pages.")

                }

                attachment("imagen", "Portada del manga") {
                    name(Langs.ENGLISH_UNITED_STATES, "image")
                    description(Langs.ENGLISH_UNITED_STATES, "Cover of the manga")
                }
            }

            handler {
                val deferredResp = kord.async {
                    interaction.deferPublicResponse()
                }

                val c = interaction.command

                val title = c.strings["título"]!!
                val description = c.strings["descripción"]!!
                val link = c.strings["link"]!!
                // TODO: Demographic check on manga submission crashes bot sometimes
                //  This is caused by the fact it excepcts to find the value of an enum as
                //  a string, but what's being stored are the alias, so they dont always match
                //  (`"OTHER" != "otros.uppercase()`)
                //  labels: bug
                val demographic = enumValueOf<Demographic>(c.strings["demografía"]!!.uppercase())
                val tags = c.strings["tags"]!!
                    .split(',')
                    .filter(String::isNotBlank)
                    .map(String::trim)
                    .toSet()

                val chapters = c.integers["capítulos"]!!
                val volumes = c.integers["tomos"]
                val ppv = c.integers["páginasportomo"]

                val ppc = c.integers["páginasporcapítulo"] ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1

                val image: Attachment? = c.attachments["imagen"]
                val validImage: Boolean = image.isValidImage()

                val isValid = c.integers.values.all { it > 0 }
                        && c.strings.values.all(String::isNotBlank)
                        && c.strings["link"]!!.isValidURL()
                        && tags.isNotEmpty()
                        && ppc > 0
                        && validImage

                // TODO: set a cooldown for the user,
                //  check if similar titles exist,
                //  then add anyway but inform the user
                //  labels: enhancement
                val response = deferredResp.await()

                response.respond {
                    if (isValid) {
                        try {
                            db.addManga(
                                title = title,
                                description = description,
                                imgURLSource = image?.let { URL(it.url) },
                                link = link,
                                demographic = demographic,
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
                        }
                    } else {
                        // TODO: Point out the reason(s) the arguments validation didn't pass after trying to submit an entry.
                        content = "Error en los argumentos."
                    }
                }
            }
        }
    }
}