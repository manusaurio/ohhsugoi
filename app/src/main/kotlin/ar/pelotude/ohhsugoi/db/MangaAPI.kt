package ar.pelotude.ohhsugoi.db

import java.net.URL

enum class Demographic(val alias: String) {
    SEINEN("seinen"),
    SHOUNEN("shounen"),
    SHOUJO("shoujo"),
    JOSUEI("jousei"),
    KOMODO("komodo"),
    HOBBY("hobby"),
    OTHER("otros"),
}
interface Manga {
    val id: Long
    val title: String
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
 * Represents modifications to be done to a manga entry, specified by its [id].
 * Every nullable property can be set to `null` if no changes are needed for that data.
 *
 * As for tags modifications, [String]s must be provided in [tagsToRemove] and
 * [tagsToAdd].
 *
 * **This class does not represent nullification of data:** `null` values will be
 * ignored.
 */
data class MangaChanges(
    val id: Long,
    val title: String?,
    val description: String?,
    val imgURLSource: URL?,
    val link: String?,
    val demographic: String?,
    val volumes: Long?,
    val pagesPerVolume: Long?,
    val chapters: Long?,
    val pagesPerChapter: Long?,
    val read: Boolean?,
    val tagsToRemove: Set<String>? = null,
    val tagsToAdd: Set<String>? = null,
)

interface MangaDatabase {
    suspend fun getManga(id: Long): Manga?

    suspend fun getTaggedManga(id: Long): MangaWithTags?

    suspend fun searchManga(text: String, limit: Long = 1): Collection<MangaWithTags>

    suspend fun addManga(
        title: String,
        description: String,
        imgURLSource: URL? = null,
        link: String? = null,
        demographic: Demographic? = null,
        volumes: Long? = null,
        pagesPerVolume: Long? = null,
        chapters: Long? = null,
        pagesPerChapter: Long? = null,
        tags: Set<String> = setOf(),
        read: Boolean = false,
    ): Long?

    suspend fun updateManga(changes: MangaChanges, vararg flags: UpdateFlags)
}

enum class UpdateFlags {
    UNSET_IMG_URL,
    UNSET_LINK,
    UNSET_VOLUMES,
    UNSET_PPV,
    UNSET_CHAPTERS,
    UNSET_PPC,
}