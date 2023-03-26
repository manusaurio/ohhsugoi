package ar.pelotude.ohhsugoi.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.pelotude.Database
import ar.pelotude.ohhsugoi.Demographic
import ar.pelotude.ohhsugoi.uuidString
import manga.data.Manga
import manga.data.SelectMangaWithTags
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.extension

typealias MangaWithTags = SelectMangaWithTags

interface MangaDatabase {
    suspend fun getManga(id: Long): Manga?

    suspend fun getTaggedManga(id: Long): SelectMangaWithTags?

    suspend fun getMangas(title: String)

    suspend fun addManga(
        title: String,
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
}

// TODO: Inject IO dispatcher
class MangaDatabaseSQLite(
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
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

    private inline val Long.boolean: Boolean
        get() = this == 1L

    override suspend fun getManga(id: Long): Manga? {
        return queries.select(id).executeAsOneOrNull()
    }

    override suspend fun getTaggedManga(id: Long): MangaWithTags? {
        return queries.selectMangaWithTags(id).executeAsOneOrNull()
    }

    override suspend fun getMangas(title: String) {
        TODO("...")
    }

    override suspend fun addManga(
        title: String,
        imgURLSource: URL?,
        link: String?,
        demographic: Demographic?,
        volumes: Long?,
        pagesPerVolume: Long?,
        chapters: Long?,
        pagesPerChapter: Long?,
        tags: Set<String>,
        read: Boolean,
    ): Long? = try {
        database.transactionWithResult {
            val mangaId = queries.insert(
                title = title,
                link = link,
                img_URL = null,
                demographics = demographic?.alias ?: Demographic.OTHER.alias,
                volumes = volumes?.toLong(),
                pages_per_volume = pagesPerVolume?.toLong(),
                chapters = chapters?.toLong(),
                pages_per_chapter = pagesPerChapter?.toLong(),
                read = 0
            ).executeAsOne()
            // TODO: log

            for (tag in tags) {
                val tagId: Long = try {
                    queries.insertTag(tag)
                    queries.getLastInsertRowId().executeAsOne()
                } catch (e: SQLException) { // UNIQUE, use stored instead
                    // TODO ^ change to sqlite exception, check if the reason is `UNIQUE`
                    System.err.println("${e.cause} with error ${e.errorCode}!")
                    queries.selectTagId(tag).executeAsOne()
                }

                queries.insertTagAssociation(tagId, mangaId)
            }

            // TODO: compress/resize, remove exif metadata
            if (imgURLSource != null) {
                // TODO: log
                val extension = Path.of(imgURLSource.path).extension

                val mangaFileName = "$mangaId-${uuidString()}.${extension}"

                val destiny = Path(System.getenv("MANGA_IMAGES_PATH")) / mangaFileName



                imgURLSource.openStream().buffered().use {
                    Files.copy(it, destiny)
                }

                queries.updateMangaImgURL(mangaFileName, mangaId)
            }

            return@transactionWithResult mangaId
        }
    } catch (e: IOException) {
        // TODO: log
        //  is it worth it to catch IOExceptions?
        null
    }
}