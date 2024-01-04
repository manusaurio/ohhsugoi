package ar.pelotude.ohhsugoi.db

import ar.pelotude.ohhsugoi.db.scheduler.Status
import ar.pelotude.ohhsugoi.db.scheduler.StoredRawPost
import com.kotlindiscord.kord.extensions.utils.getKoin
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URL
import java.time.Instant

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

internal fun storedPollImgUrl(fileName: String): URL {
    val config: DatabaseConfiguration = koin.get()
    val webpage = config.webpage
    val subDirectory = config.pollImagesUrlPath

    val url = URLBuilder(webpage)
        .appendPathSegments(subDirectory, fileName)
        .build()

    return URL(url.toString())
}

internal fun mangaSQLDmapper(
    id: Long, title: String, description: String, imgURL: String?, link: String?,
    demographic: String, volumes: Long?, pagesPerVolume: Long?, chapters: Long?,
    pagesPerChapter: Long?, read: Long, insertionDate: Long, authorId: Long?,
    deleted: Long, tags: String?
): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            title=title,
            description=description,
            imgURLSource=imgURL?.let { storedImgURL(it) },
            link=link,
            demographic=demographic,
            volumes=volumes,
            pagesPerVolume=pagesPerVolume,
            chapters=chapters,
            pagesPerChapter=pagesPerChapter,
            read=read.boolean,
            insertionDate=insertionDate,
        ),
        tags=tags?.toTagSet() ?: setOf()
    )
}

internal fun pollSQLDMapper(
    id: Long, insertionDate: Long, authorID: Long?, title: String, description: String?,
    imageFileName: String?, singleVote: Long, finishedDate: Long?,
    existingPollOptions: List<ExistingPollOption>,
    ): ExistingPoll {
    return ExistingPoll(
        id,
        Instant.ofEpochSecond(insertionDate),
        authorID,
        title,
        description,
        finishedDate?.let { Instant.ofEpochSecond(it) },
        imageFileName?.let(::storedPollImgUrl),
        existingPollOptions,
        singleVote.boolean,
    )
}

internal fun storedSQLDScheduledPostMapper(
    id: Long,
    content: String,
    scheduledDate: Long,
    announcementType: String,
    status: String,
) = StoredRawPost(
    id=id,
    status=Status.valueOf(status),
    content=Json.decodeFromString(content),
    execInstant=Instant.ofEpochSecond(scheduledDate),
    postType=announcementType,
)

internal fun String.toTagSet() = this.split(',').toSet()

internal inline val Long.boolean: Boolean
    get() = this == 1L

internal fun Boolean.sqliteBool() = if (this) 1L else 0L