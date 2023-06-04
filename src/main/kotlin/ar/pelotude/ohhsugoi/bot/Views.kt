package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.Manga
import ar.pelotude.ohhsugoi.db.MangaChanges
import ar.pelotude.ohhsugoi.db.MangaWithTags
import ar.pelotude.ohhsugoi.util.makeTitle
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.application.slash.EphemeralSlashCommandContext
import com.kotlindiscord.kord.extensions.commands.application.slash.SlashCommandContext
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.ephemeralButton
import com.kotlindiscord.kord.extensions.types.*
import dev.kord.common.Color
import dev.kord.core.behavior.channel.createEmbed
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

suspend fun EphemeralSlashCommandContext<MangaExtension.EditArguments, *>.respondWithChanges(
        previousManga: Manga,
        updatedManga: MangaWithTags,
        requestedChanges: MangaChanges,
) {
    channel.createEmbed {
        title = "Editado [#${previousManga.id}] ${previousManga.title}"
        color = colors.success

        user.asUserOrNull()?.let { u ->
            author {
                name = "${u.username} (${u.id.value})"
                icon = u.avatar?.cdnUrl?.toUrl()
            }
        }

        arguments.title?.let {
            field {
                name = "Título"
                value = it
                inline = false
            }
        }

        arguments.description?.let {
            field {
                name = "Descripción"
                value = it
                inline = false
            }
        }

        arguments.link?.let {
            field {
                name = "Link"
                value = it
                inline = false
            }
        }

        arguments.volumes?.let {
            field {
                name = "Tomos"
                value = "$it"
                inline = true
            }
        }

        arguments.pagesPerVolume?.let {
            field {
                name = "Páginas por tomo"
                value = "$it"
                inline = true
            }
        }

        arguments.chapters?.let {
            field {
                name = "Capítulos"
                value = "$it"
                inline = true
            }
        }

        arguments.pagesPerChapter?.let {
            field {
                name = "Páginas por capítulo"
                value = "$it"
                inline = true
            }
        }

        arguments.demographic?.let {
            field {
                name = "Demografía"
                value = it
                inline = true
            }
        }

        requestedChanges.tagsToAdd?.let {
            field {
                name = "Tags para agregar"
                value = it.joinToString(separator=", ", transform=String::makeTitle)
                inline = false
            }
        }

        requestedChanges.tagsToRemove?.let {
            field {
                name = "Tags para remover"
                value = it.joinToString(separator=", ", transform=String::makeTitle)
                inline = false
            }
        }

        // `previousManga` might not represent the last status the user had knowledge of,
        // so we can't rely on it to show the difference between states
        if (!arguments.addTags.isNullOrEmpty() || !arguments.removeTags.isNullOrEmpty()) {
            field {
                name = "Tags resultantes"
                value = updatedManga.tags.joinToString(separator=", ", transform=String::makeTitle)
                inline = true
            }
        }

        arguments.image?.let {
            image = updatedManga.imgURLSource?.toString()
        }

        if (arguments.unsetImage == true && arguments.image == null) { // `Boolean?` not `Boolean`
            field {
                name = "Remover portada"
                value = "Sí"
                inline = true
            }
        }

        if (fields.isEmpty() && image == null) {
            description = "Ningún cambio."
        }
    }
}

fun quickEmbed(title: String, description: String, color: Color) = EmbedBuilder().apply {
    this.title = title
    this.description = description
    this.color = color
}

@RequiresOptIn(
        level = RequiresOptIn.Level.WARNING,
        message = "This function checks if it's being called from a ephemeral or public interaction," +
                " but it won't do anything nor show errors or warnings for anything else. " +
                "Make sure the interaction you are using it from is one of the valid ones.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
/** Opt-in for views in ephemeral or public contexts. */
annotation class EphemeralOrPublicView

@EphemeralOrPublicView
suspend inline fun SlashCommandContext<*, *, *>.respondEphemeralOrPublicEmbed(embed: EmbedBuilder, public: Boolean) {
    when (this) {
        is EphemeralInteractionContext -> if (public) respondPublic { embeds.add(embed) } else respond { embeds.add(embed) }
        is PublicInteractionContext -> if (public) respond { embeds.add(embed) } else respondEphemeral { embeds.add(embed) }
    }
}

@EphemeralOrPublicView
suspend inline fun SlashCommandContext<*, *, *>.respondWithInfo(description: String, title: String = "**Info**", public: Boolean = false) {
    respondEphemeralOrPublicEmbed(quickEmbed(title, description, colors.info), public=public)
}

@EphemeralOrPublicView
suspend inline fun SlashCommandContext<*, *, *>.respondWithSuccess(description: String, title: String = "**Éxito**", public: Boolean = false) {
    respondEphemeralOrPublicEmbed(quickEmbed(title, description, colors.success), public=public)
}

@EphemeralOrPublicView
suspend inline fun SlashCommandContext<*, *, *>.respondWithError(description: String, title: String = "**Error**", public: Boolean = false) {
    respondEphemeralOrPublicEmbed(quickEmbed(title, description, colors.error), public=public)
}

suspend fun EphemeralSlashCommandContext<*, *>.requestConfirmation(
    description: String,
    timeout: Duration = 15.seconds,
    cancel: (suspend () -> Any?)? = null,
    confirm: suspend () -> Any?,
) = respond {
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