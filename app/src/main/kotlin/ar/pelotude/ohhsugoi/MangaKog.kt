package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.koggable.Kog
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.interaction.attachment
import dev.kord.rest.builder.interaction.int
import dev.kord.rest.builder.interaction.string
import manga.data.Manga

enum class Demographic(val alias: String) {
    SEINEN("seinen"),
    SHOUNEN("shounen"),
    SHOUJO("shoujo"),
}

class MangaKog : Kog() {
    // TODO: Refactor & add database
    override suspend fun setup() {
        inputCommand {
            name = "bailar"
            description = "Hace que bailes"
            channelId = Snowflake(832779672848433242)

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

                val chapters = c.integers["chapters"]!!
                val volumes = c.integers["volumes"]
                val ppv = c.integers["ppv"]

                val ppc = c.integers["ppc"] ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1


                val (image: Attachment?, validImage: Boolean) = with(c.attachments["image"]) {
                    if (this == null || isImage && size < 8000000) {
                        this to true
                    } else this to false
                }


                val isValid = c.integers.values.all { it > 0 }
                        && c.strings.values.all(String::isNotBlank)
                        && c.strings["link"]!!.isValidURL()
                        && tags.isNotEmpty()
                        && ppc > 0
                        && validImage

                // TODO: Download and register in DB
                if (validImage) { /*
                    image?.let { down ->
                        Files.copy(URL(down.url).openStream(), Paths.get(""))

                        URL(down.url).openStream().use {
                            Files.copy(it, Paths.get("some path"))
                        }
                    }
                    */
                }

                if (isValid) {
                    // val sent = push(manga) // INSERT into DB
                    val sent = true

                    val manga = Manga(
                        1, // unnecesary
                        title,
                        "...", // internal URL as set in the DB
                        link,
                        Demographic.SEINEN.alias,
                        volumes,
                        ppv,
                        chapters,
                        ppc,
                        0
                    )

                    response.respond {
                        content = if (sent)
                            "Agregado ${manga.title} exitosamente."
                        else
                            "Error agregando el manga."
                    }
                } else {
                    response.respond {
                        content = "Error en los argumentos."
                    }
                }
            }
        }
    }
}