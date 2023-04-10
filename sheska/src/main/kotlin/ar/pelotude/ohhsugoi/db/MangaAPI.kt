package ar.pelotude.ohhsugoi.db

import java.io.IOException
import java.net.URL

// TODO: Make choosing demographics it safer
//  this includes changing the line
//  ```kotlin
//  demographics = demographic ?: "Otros",
//  ```
//  at MangaDatabase.kt
val demographics = setOf<String>(
        "Seinen",
        "Shounen",
        "Shoujo",
        "Jousei",
        "Kodomo",
        "Hobby",
        "Otros",
)

interface Manga {
    val id: Long
    val title: String
    val insertionDate: Long
    val description: String
    val imgURLSource: URL?
    val link: String?
    val demographic: String
    val volumes: Long?
    val pagesPerVolume: Long?
    val chapters: Long?
    val pagesPerChapter: Long?
    val read: Boolean
}

interface MangaWithTags : Manga {
    val tags: Set<String>
}

data class MangaData(
    override val id: Long,
    override val title: String,
    override val insertionDate: Long,
    override val description: String,
    override val imgURLSource: URL? = null,
    override val link: String?,
    override val demographic: String,
    override val volumes: Long? = null,
    override val pagesPerVolume: Long? = null,
    override val chapters: Long? = null,
    override val pagesPerChapter: Long? = null,
    override val read: Boolean
) : Manga

data class MangaWithTagsData(
    val manga: Manga,
    override val tags: Set<String> = setOf()
) : MangaWithTags, Manga by manga

/**
 * Represents modifications to be done to a manga entry, specified by its [id] and
 * [timestamp].
 *
 * Every nullable property can be set to `null` if no changes are needed for that data.
 *
 * As for tags modifications, [String]s must be provided in [tagsToRemove] and
 * [tagsToAdd].
 *
 * **This class does not represent nullification of data:** `null` values will be
 * ignored.
 *
 * @param[id] The stored unique id of the manga entry
 * @param[timestamp] The timestamp of the manga entry
 */
data class MangaChanges(
    val id: Long,
    val title: String? = null,
    val description: String? = null,
    val imgURLSource: URL? = null,
    val link: String? = null,
    val demographic: String? = null,
    val volumes: Long? = null,
    val pagesPerVolume: Long? = null,
    val chapters: Long? = null,
    val pagesPerChapter: Long? = null,
    val read: Boolean? = null,
    val tagsToRemove: Set<String>? = null,
    val tagsToAdd: Set<String>? = null,
)

interface MangaDatabase {
    suspend fun getManga(id: Long): MangaWithTags?

    suspend fun getMangas(vararg ids: Long): Collection<MangaWithTags>

    suspend fun searchManga(
        text: String? = null,
        tagFilter: String? = null,
        demographicFilter: String? = null,
        limit: Long = 1
    ): Collection<MangaWithTags>

    /**
     * Adds a manga entry to the database. If an exception is thrown during
     * the data submission, the request is cancelled.
     *
     * @return A [Long] with the unique id of the added entry
     * @throws DownloadException if an I/O error occurs when downloading the image
     */
    suspend fun addManga(
        title: String,
        description: String,
        imgURLSource: URL? = null,
        link: String? = null,
        demographic: String? = null,
        volumes: Long? = null,
        pagesPerVolume: Long? = null,
        chapters: Long? = null,
        pagesPerChapter: Long? = null,
        tags: Set<String> = setOf(),
        read: Boolean = false,
    ): Long

    /**
     * Updates a manga by applying changes onto its current data,
     * taken from [MangaChanges]
     * @param[changes] the changes to apply
     * @param[flags] flags of data to be unset
     * @throws [DownloadException] if a [MangaChanges.imgURLSource]
     * was submitted and couldn't be downloaded */
    suspend fun updateManga(changes: MangaChanges, vararg flags: UpdateFlags)

    /**
     * Deletes a manga entry.
     *
     * @return[Boolean] `true` if something was found and deleted, `false` if
     * nothing to delete was found
     */
    suspend fun deleteManga(id: Long): Boolean
}

enum class UpdateFlags {
    UNSET_IMG_URL,
    UNSET_LINK,
    UNSET_VOLUMES,
    UNSET_PPV,
    UNSET_CHAPTERS,
    UNSET_PPC,
}

class DownloadException(message: String, cause: Throwable) : IOException(message, cause)