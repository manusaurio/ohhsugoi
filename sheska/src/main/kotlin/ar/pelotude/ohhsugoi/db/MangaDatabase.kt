package ar.pelotude.ohhsugoi.db

import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.pelotude.Database
import ar.pelotude.ohhsugoi.downloadMangaCover
import ar.pelotude.ohhsugoi.makeTitle
import ar.pelotude.ohhsugoi.saveAsJpg
import ar.pelotude.ohhsugoi.uuidString
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.inject
import java.io.IOException
import java.net.URL
import kotlin.io.path.div

class MangaDatabaseSQLite(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MangaDatabase, KordExKoinComponent {
    internal val dbConfig: DatabaseConfiguration by inject()
    private val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dbConfig.sqlitePath}")

    private val database: Database = Database.Schema.run {
        kordLogger.info { "Database not specified: Assuming sqlite." }

        val userVersion = driver.executeQuery(
            null,
            "PRAGMA user_version;",
            { c -> c.getLong(0) },
            0
        ).value

        kordLogger.info { "Initializing sqlite database. sqlite user_version: $userVersion" }

        if (userVersion == 0L) {
            kordLogger.info { "Creating database from the scratch..." }
            create(driver)
        }

        return@run Database(driver)
    }
    private val queries = database.mangaQueries

    override suspend fun getManga(id: Long): MangaWithTags? {
        return withContext(dispatcher) {
            queries.selectMangaWithTags(listOf(id), ::mangaSQLDmapper).executeAsOneOrNull()
        }
    }

    override suspend fun getMangas(vararg ids: Long): Collection<MangaWithTags> {
        return withContext(dispatcher) {
            queries.selectMangaWithTags(ids.toList(), ::mangaSQLDmapper).executeAsList()
        }
    }

    override suspend fun searchManga(
        text: String?,
        tagFilter: String?,
        demographicFilter: String?,
        limit: Long
    ): Collection<MangaWithTags> {
        return withContext(dispatcher) {
            val titleTagFilter = tagFilter?.makeTitle()
            return@withContext if (text != null) queries.searchMangaWithTagsFTS(
                "title: $text",
                demographicFilter,
                titleTagFilter,
                limit,
                ::mangaSQLDmapper
            ).executeAsList()
            else queries.searchMangaWithTags(
                demographicFilter, titleTagFilter, limit, ::mangaSQLDmapper
            ).executeAsList()
        }
    }

    /**
     * Downloads the content from a [URL], assuming in the process
     * it's an image file, then randomizes a name for it based on [mangaId]
     * with the prefix "$[mangaId]-". The image is stored in the directory
     * specified by the configuration.
     *
     * @param[imgSource] Source of the image to be downloaded
     * @param[mangaId] The id of the manga that the image represents
     * @return A [String] containing filename of the downloaded image,
     * or null if the file couldn't be downloaded
     * @throws DownloadException if an I/O error occurs when downloading the image
     */
    private fun storeMangaCover(imgSource: URL, mangaId: Long): String {
        val mangaFileName = "$mangaId-${uuidString()}.jpg"
        val destiny = dbConfig.mangaImageDirectory / mangaFileName

        try {
            downloadMangaCover(imgSource, dbConfig.mangaCoversWidth, dbConfig.mangaCoversHeight)
                    .saveAsJpg(destiny.toFile())
        } catch(e: IOException) {
            throw DownloadException("The image could not be downloaded", e)
        }
        kordLogger.info { "Added image: $mangaFileName " }

        return mangaFileName
    }

    /**
     * Inserts a [Collection] of [String]s as tags into the database, associating them
     * to the given manga's id. If a tag already exists, then it's associated to its
     * current id in the database.
     *
     * This function does not request its own transaction and should be called within one.
     */
    private fun TransactionCallbacks.addTags(mangaId: Long, tags: Collection<String>) {
        val titledTags = tags.map(String::makeTitle).toSet()
        for (tag in titledTags) {
            val tagId: Long = queries.insertTag(tag).executeAsOne()
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
    ): MangaWithTags {
        val insertionId = database.transactionWithResult {
            val mangaId = queries.insert(
                title = title,
                description = description,
                link = link,
                img_URL = null,
                demographics = demographic ?: "Otros", // XXX ! TODO
                volumes = volumes,
                pages_per_volume = pagesPerVolume,
                chapters = chapters,
                pages_per_chapter = pagesPerChapter,
                read = read.sqliteBool()
            ).executeAsOne()

            addTags(mangaId, tags)

            afterCommit {
                kordLogger.info { "Inserted $title at $mangaId." }
            }

            mangaId
        }

        if (imgURLSource != null) {
            val imgFileName = storeMangaCover(imgURLSource, insertionId)
            queries.updateMangaImgURL(imgFileName, insertionId)
        }

        return queries.selectMangaWithTags(listOf(insertionId), ::mangaSQLDmapper).executeAsOne()
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
                val imgFilePath = if (imgURLSource != null) {
                    // throws DownloadException
                    storeMangaCover(imgURLSource, mangaId)
                } else null

                queries.updateNonNullablesManga(
                    title, description, imgFilePath, link, demographic,
                    volumes, pagesPerVolume, chapters, pagesPerChapter, read?.sqliteBool(),
                    mangaId
                )
            }

            changes.tagsToAdd?.let { tags ->
                addTags(mangaId, tags)
            }

            val tagsToRemove = changes.tagsToRemove?.map(String::makeTitle)

            tagsToRemove?.let { tags ->
                queries.removeTagAssociation(mangaId, tags)
            }
        }
    }

    override suspend fun deleteManga(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.deleteManga(id).executeAsOneOrNull() != null
        }
    }
}
