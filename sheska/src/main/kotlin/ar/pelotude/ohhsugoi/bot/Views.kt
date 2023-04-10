package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.MangaWithTags
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.types.edit
import dev.kord.core.entity.User
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.FollowupMessageCreateBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

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

suspend fun FollowupMessageCreateBuilder.confirmationDialog(
        content: String,
        actor: User,
        cancel: (suspend () -> Any?)? = null,
        confirm: suspend () -> Any?
) {
    this.content = content
    components {
        val done = AtomicBoolean()

        ephemeralButton {
            label = "Confirmar"
            check { sameUser(actor) }

            action {
                if (!done.getAndSet(true)) {
                    confirm()
                    this@components.cancel()
                    edit { components { } }
                }
            }
        }

        ephemeralButton {
            label = "Cancelar"
            check { sameUser(actor) }

            action {
                if (!done.getAndSet(true)) {
                    cancel?.invoke()
                    this@components.cancel()
                    edit { components { } }
                }
            }
        }
    }
}