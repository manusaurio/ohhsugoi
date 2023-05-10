package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.MangaWithTags
import ar.pelotude.ohhsugoi.util.makeTitle
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.application.slash.EphemeralSlashCommandContext
import com.kotlindiscord.kord.extensions.commands.application.slash.PublicSlashCommandContext
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.types.edit
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondEphemeral
import com.kotlindiscord.kord.extensions.types.respondPublic
import dev.kord.common.Color
import dev.kord.core.entity.User
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object colors {
    val info = Color (0, 0, 200)
    val warning = Color(200, 100, 0)
    val success = Color(0, 200, 0)
    val error = Color(200, 0, 0)
}

val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
        "dd/MMMM/YYYY", Locale.forLanguageTag("es-ES")
)

fun <T : ButtonInteractionCreateEvent> CheckContext<T>.sameUser(userA: User) {
    val userB = event.interaction.user
    if (userA != userB) fail()
}

/**
 * Builds a manga view from the scope of an [EmbedBuilder] and returns it.
 *
 * @return The [EmbedBuilder] this function was called from.
 */
fun EmbedBuilder.mangaView(manga: MangaWithTags): EmbedBuilder {
    title = "[#${manga.id}] ${manga.title}"

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
                postfix = ".",
                transform = String::makeTitle,
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
        name = "Capítulos"
        value = manga.chapters?.toString() ?: "?"
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
        val zonedDateTime = Instant.ofEpochSecond(manga.insertionDate).atZone(ZoneId.systemDefault())!!
        val date = formatter.format(zonedDateTime)
        text = "$text\nAgregado el $date"
    }

    return this
}

suspend fun PublicSlashCommandContext<MangaExtension.EditArguments, *>.respondWithChanges(
    previousManga: MangaWithTags
) {
    respond {
        embed {
            title = "Editado [#${previousManga.id}] ${previousManga.title}"
            color = colors.success

            description = "__Campos modificados__:\n\n" +
                    listOf<Pair<String, *>>(
                        ("Título" to arguments.title),
                        ("Descripción" to arguments.description),
                        ("Imagen" to arguments.image),
                        ("Link" to arguments.link),
                        ("Tomos" to arguments.volumes),
                        ("Páginas por tomo" to arguments.pagesPerVolume),
                        ("Capítulos" to arguments.chapters),
                        ("Páginas por capítulo" to arguments.pagesPerChapter),
                        ("Demografía" to arguments.demographic),
                        ("Tags (nuevos)" to arguments.addTags),
                        ("Tags (removidos)" to arguments.removeTags),
                        ("Imagen removida" to arguments.unsetImage),
                    )
                        .filter { it.second != null }
                        .joinToString("\n") { it.first }
        }
    }
}

fun quickEmbed(title: String, description: String, color: Color) = EmbedBuilder().apply {
    this.title = title
    this.description = description
    this.color = color
}

suspend fun EphemeralSlashCommandContext<*, *>.respondWithInfo(description: String, title: String = "**Info**", public: Boolean = false) {
    val embed = quickEmbed(title, description, colors.info)

    if (public) respondPublic {
        embeds.add(embed)
    } else respond {
        embeds.add(embed)
    }
}

suspend fun EphemeralSlashCommandContext<*, *>.respondWithSuccess(description: String, title: String = "**Éxito**", public: Boolean = false) {
    val embed = quickEmbed(title, description, colors.success)

    if (public) respondPublic {
        embeds.add(embed)
    } else respond {
        embeds.add(embed)
    }
}

suspend fun EphemeralSlashCommandContext<*, *>.respondWithError(description: String, title: String = "**Error**", public: Boolean = false) {
    val embed = quickEmbed(title, description, colors.error)

    if (public) respondPublic {
        embeds.add(embed)
    } else respond {
        embeds.add(embed)
    }
}

suspend fun PublicSlashCommandContext<*, *>.respondWithInfo(description: String, title: String = "**Info**", public: Boolean = true) {
    val embed = quickEmbed(title, description, colors.info)

    if (public) respond {
        embeds.add(embed)
    } else respondEphemeral {
        embeds.add(embed)
    }
}

suspend fun PublicSlashCommandContext<*, *>.respondWithSuccess(description: String, title: String = "**Éxito**", public: Boolean = true) {
    val embed = quickEmbed(title, description, colors.success)

    if (public) respond {
        embeds.add(embed)
    } else respondEphemeral {
        embeds.add(embed)
    }
}

suspend fun PublicSlashCommandContext<*, *>.respondWithError(description: String, title: String = "**Error**", public: Boolean = true) {
    val embed = quickEmbed(title, description, colors.error)

    if (public) respond {
        embeds.add(embed)
    } else respondEphemeral {
        embeds.add(embed)
    }
}

suspend fun PublicSlashCommandContext<*, *>.requestConfirmation(
    description: String,
    timeout: Duration = 15.seconds,
    cancel: (suspend () -> Any?)? = null,
    confirm: suspend () -> Any?,
) = respondEphemeral {
    embed {
        title = "❗ Confirmación"
        this.description = description
    }

    components(timeout) {
        val done = AtomicBoolean()
        val user = user.asUser()

        timeoutCallback = {
            cancel?.invoke()
            interactionResponse.delete()
        }


        ephemeralButton {
            label = "Confirmar"
            check { sameUser(user) }

            action {
                if (!done.getAndSet(true)) {
                    this@components.cancel()
                    edit { components = mutableListOf() }
                    confirm()
                }
            }
        }

        ephemeralButton {
            label = "Cancelar"
            check { sameUser(user) }

            action {
                if (!done.getAndSet(true)) {
                    this@components.cancel()
                    cancel?.invoke()
                    interactionResponse.delete()
                }
            }
        }
    }
}