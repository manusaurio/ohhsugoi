package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.koggable.Kog
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.interaction.attachment
import dev.kord.rest.builder.interaction.int
import dev.kord.rest.builder.interaction.string
import java.net.URL


// TODO: Move to database
enum class Demographic(val alias: String) {
    SEINEN("seinen"),
    SHOUNEN("shounen"),
    SHOUJO("shoujo"),
    JOSUEI("jousei"),
    KOMODO("komodo"),
    HOBBY("hobby"),
    OTHER("otros"),
}

class MangaKog(val db: ar.pelotude.ohhsugoi.db.MangaDatabase) : Kog() {
    // TODO: Refactor
    private fun java.net.URL.seemsSafe() =
        protocol == "https" && host in listOf("media.discordapp.net", "cdn.discordapp.com")


    override suspend fun setup() {
        inputCommand {
            name = "sugerir"
            description = "Agrega un manga con los datos proporcionados"
            channelId = Snowflake(System.getenv("KORD_WEEB_SERVER"))

            command {
                string("manga_title", "El título del manga") { required = true }
                string("link", "Link para comprar o leer el manga.") { required = true }
                string("demographic", "Demografía del manga.") {
                    required = true
                    Demographic.values().forEach { n -> choice(n.alias, n.alias) }
                }

                string("tags", "Géneros del manga. Dividir por coma: \"tag uno, tag dos\".") {
                    required = true
                }

                int("chapters", "Cantidad de capítulos.") { required = true }
                int("ppc", "Cantidad de páginas por capítulo.")
                int("volumes", "Cantidad de tomos.")
                int("ppv", "Cantidad de páginas por tomo.")
                attachment("image", "Portada del manga.")
            }

            handler {
                val response = interaction.deferPublicResponse()

                val c = interaction.command

                val title = c.strings["manga_title"]!!
                val link = c.strings["link"]!!
                val demographic = enumValueOf<Demographic>(c.strings["demographic"]!!.uppercase())
                val tags = c.strings["tags"]!!
                    .split(',')
                    .filter(String::isNotBlank)
                    .map(String::trim)
                    .toSet()

                val chapters = c.integers["chapters"]!!
                val volumes = c.integers["volumes"]
                val ppv = c.integers["ppv"]

                val ppc = c.integers["ppc"] ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1

                val (image: Attachment?, validImage: Boolean) = with(c.attachments["image"]) {
                    if (this == null || isImage && size < 8000000 && URL(url).seemsSafe()) {
                        this to true
                    } else this to false
                }

                val isValid = c.integers.values.all { it > 0 }
                        && c.strings.values.all(String::isNotBlank)
                        && c.strings["link"]!!.isValidURL()
                        && tags.isNotEmpty()
                        && ppc > 0
                        && validImage


                // set a cooldown for the user,
                // check if similar titles exist,
                // then add anyway but inform the user


                response.respond {
                    if (isValid) {
                        val operation = db.addManga(
                            title = title,
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

                        content = if (operation != null) {
                            "Agregado exitosamente."
                        } else {
                            "Error al agregar."
                        }
                    } else {
                        content = "Error en los argumentos."
                    }

                }
            }
        }
    }
}