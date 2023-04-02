package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.MangaWithTags
import dev.kord.rest.builder.message.EmbedBuilder
import java.util.*

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