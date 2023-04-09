package ar.pelotude.ohhsugoi.db

import com.kotlindiscord.kord.extensions.utils.getKoin
import io.ktor.http.*
import manga.data.SelectMangaWithTags as SelectedMangaWithTagsSQLD
import manga.data.SearchMangaWithTagsFTS as MangaWithTagsFTSSQLD
import manga.data.SearchMangaWithTags as MangaWithTagsSQLD
import java.net.URL
import manga.data.Manga as MangaSQLD

val koin = getKoin()

internal fun storedImgURL(fileName: String): URL {
    val config: DatabaseConfiguration = koin.get()
    val webpage = config.webpage
    val subDirectory = config.mangaCoversUrlPath

    val url = URLBuilder(webpage)
            .appendPathSegments(subDirectory, fileName)
            .build()

    // TODO: Change URLs to ktor Urls here and everywhere else
    return URL(url.toString())
}

internal fun MangaSQLD.toAPIManga(): Manga {
    return MangaData(
        id=id,
        insertionDate=insertion_date,
        title=title,
        description=description,
        link=link,
        imgURLSource=img_URL?.let { storedImgURL(it) },
        chapters=chapters,
        pagesPerChapter=pages_per_chapter,
        volumes=volumes,
        pagesPerVolume=pages_per_volume,
        demographic=demographics,
        read=read.boolean,
    )
}

internal fun SelectedMangaWithTagsSQLD.toAPIMangaWithTags(): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            insertionDate=insertion_date,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { storedImgURL(it) },
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

internal fun MangaWithTagsSQLD.toAPIMangaWithTags(): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            insertionDate=insertion_date,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { storedImgURL(it) },
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
            insertionDate=insertion_date,
            title=title,
            description=description,
            link=link,
            imgURLSource=img_URL?.let { storedImgURL(it) },
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