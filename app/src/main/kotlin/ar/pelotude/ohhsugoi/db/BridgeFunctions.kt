package ar.pelotude.ohhsugoi.db

import manga.data.SelectMangaWithTags
import manga.data.SearchMangaWithTagsFTS as MangaWithTagsFTSSQLD
import manga.data.SearchMangaWithTags as MangaWithTagsSQLD
import java.net.URL
import manga.data.Manga as MangaSQLD

internal fun MangaSQLD.toAPIManga(): Manga {
    return MangaData(
        id=id,
        title=title,
        description=description,
        link=link,
        imgURLSource=img_URL?.let { URL(img_URL) },
        chapters=chapters,
        pagesPerChapter=pages_per_chapter,
        volumes=volumes,
        pagesPerVolume=pages_per_volume,
        demographic=demographics,
        read=read.boolean,
    )
}

internal fun MangaWithTagsSQLD.toAPIMangaWithTags(): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { URL(img_URL) },
            chapters=chapters,
            pagesPerChapter=pages_per_chapter,
            volumes=volumes,
            pagesPerVolume=pages_per_volume,
            demographic=demographics,
            read=read.boolean,
        ),
        tags?.toTagSet() ?: setOf()
    )
}

internal fun MangaWithTagsFTSSQLD.toAPIMangaWithTags(): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { URL(img_URL) },
            chapters=chapters,
            pagesPerChapter=pages_per_chapter,
            volumes=volumes,
            pagesPerVolume=pages_per_volume,
            demographic=demographics,
            read=read.boolean,
        ),
        tags?.toTagSet() ?: setOf()
    )
}

internal fun SelectMangaWithTags.toAPIMangaWithTags(): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { URL(img_URL) },
            chapters=chapters,
            pagesPerChapter=pages_per_chapter,
            volumes=volumes,
            pagesPerVolume=pages_per_volume,
            demographic=demographics,
            read=read.boolean,
        ),
        tags?.toTagSet() ?: setOf()
    )
}

internal fun String.toTagSet() = this.split(',').toSet()

internal inline val Long.boolean: Boolean
    get() = this == 1L

internal fun Boolean.sqliteBool() = if (this) 1L else 0L