package ar.pelotude.ohhsugoi.db

import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.pelotude.Database
import ar.pelotude.ohhsugoi.uuidString
import kotlinx.coroutines.*
import manga.data.SearchMangaWithTags
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.extension
import dev.kord.core.kordLogger

class MangaDatabaseSQLite(
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MangaDatabase {
    private val database: Database = Database.Schema.run {
        create(driver)
        return@run Database(driver)
    }
    private val queries = database.mangaQueries

    init {
        with(driver) {
            // TODO: set up database
        }
    }

    override suspend fun getManga(id: Long): Manga? {
        val manga = queries.select(id).executeAsOneOrNull()
        return withContext(dispatcher) {
            queries.select(id).executeAsOneOrNull()?.toAPIManga()
        }
    }

    override suspend fun getTaggedManga(id: Long): MangaWithTags? {
        return withContext(dispatcher) {
            queries.selectMangaWithTags(id).executeAsOneOrNull()?.toAPIMangaWithTags()
        }
    }

    override suspend fun searchManga(text: String, limit: Long): Collection<MangaWithTags> {
        return withContext(dispatcher) {
            queries.searchMangaWithTags(text, limit).executeAsList().map(SearchMangaWithTags::toAPIMangaWithTags)
        }
    }

    /**
     * Downloads the content from the passed [URL], assuming in the process
     * it's an image file, then randomizes a name for it based on [mangaId]
     * with the prefix "$[mangaId]-"
     *
     * @param[mangaId] the id of the manga that the image represents
     * @return A [String] containing the full path to the downloaded image,
     * or null if the file couldn't be downloaded,
     * @throws DownloadException if an I/O error occurs when downloading the image
     */
    private fun URL.downloadImage(mangaId: Long): String {
        // TODO: compress/resize, remove exif metadata

        val extension = Path.of(path).extension
        val mangaFileName = "$mangaId-${uuidString()}.${extension}"
        val destiny = Path(System.getenv("MANGA_IMAGES_PATH")) / mangaFileName

        this.openStream().buffered().use { stream ->
            try {
                Files.copy(stream, destiny)
            } catch(e: IOException) {
                throw DownloadException("The image could not be downloaded", e)
            }
            kordLogger.info { "Added image: $mangaFileName " }
            return mangaFileName
        }
    }

    /**
     * Inserts a [Collection] of [String]s as tags into the database, associating them
     * to the given manga's id. If a tag already exists, then it's associated to its
     * current id in the database.
     *
     * This function does not request its own transaction and should be called within one.
     */
    private fun TransactionCallbacks.addTags(mangaId: Long, tags: Set<String>) {
        // TODO: Refactor `insertTags` making it not insert tags one by one, or to not depend on catching SQL exceptions
        //  If it's necessary to use exceptions, make it something more specific to sqlite than `SQLException`
        //  labels: enhancement
        for (tag in tags) {
            val tagId: Long = try {
                queries.insertTag(tag)
                queries.getLastInsertRowId().executeAsOne()
            } catch (e: SQLException) { // UNIQUE, use stored instead
                queries.selectTagId(tag).executeAsOne()
            }

            queries.insertTagAssociation(tagId, mangaId)
        }
    }

    override suspend fun addManga(
        title: String,
        description: String,
        imgURLSource: URL?,
        link: String?,
        demographic: String?,
        volumes: Long?,
        pagesPerVolume: Long?,
        chapters: Long?,
        pagesPerChapter: Long?,
        tags: Set<String>,
        read: Boolean,
    ): Long {
        val insertionId = database.transactionWithResult {
            val mangaId = queries.insert(
                title = title,
                description = description,
                link = link,
                img_URL = null,
                demographics = demographic ?: "Otros",
                volumes = volumes,
                pages_per_volume = pagesPerVolume,
                chapters = chapters,
                pages_per_chapter = pagesPerChapter,
                read = read.sqliteBool()
            ).executeAsOne()

            addTags(mangaId, tags)

            if (imgURLSource != null) {
                val imgFileName = imgURLSource.downloadImage(mangaId)
                queries.updateMangaImgURL(imgFileName, mangaId)
            }

            mangaId
        }

        kordLogger.info { "Inserted $title at $insertionId." }

        return insertionId
    }

    override suspend fun updateManga(changes: MangaChanges, vararg flags: UpdateFlags) = withContext(dispatcher) {
        val mangaId = changes.id

        queries.transaction {
            flags.forEach { flag ->
                when (flag) {
                    UpdateFlags.UNSET_IMG_URL -> queries.unsetImgURL(mangaId)
                    UpdateFlags.UNSET_LINK -> queries.unsetLink(mangaId)
                    UpdateFlags.UNSET_VOLUMES -> queries.unsetVolumes(mangaId)
                    UpdateFlags.UNSET_PPV -> queries.unsetPagesPerVolume(mangaId)
                    UpdateFlags.UNSET_CHAPTERS -> queries.unsetChapters(mangaId)
                    UpdateFlags.UNSET_PPC -> queries.unsetPagesPerChapter(mangaId)
                }
            }

            with (changes) {
                if (imgURLSource != null) {
                    val imgFileName = imgURLSource.downloadImage(mangaId)
                }

                val imgFilePath = imgURLSource?.let {
                    // throws DownloadException
                    val fileName = it.downloadImage(mangaId)
                    fileName
                }

                queries.updateNonNullablesManga(
                    title, description, imgFilePath, link, demographic,
                    volumes, pagesPerVolume, chapters, pagesPerChapter, read?.sqliteBool(),
                    mangaId,
                )
            }

            changes.tagsToAdd?.let { tags ->
                addTags(mangaId, tags)
            }

            changes.let { c ->
                if (c.tagsToAdd != null && c.tagsToRemove != null) {
                    (c.tagsToRemove subtract c.tagsToAdd).takeIf(Set<*>::isNotEmpty)?.let { tags ->
                        queries.removeTagAssociation(mangaId, tags)
                    }
                }
            }
        }
    }
}