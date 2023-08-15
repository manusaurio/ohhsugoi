package ar.pelotude.ohhsugoi.db

import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.pelotude.db.Database
import ar.pelotude.ohhsugoi.*
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledPostMetadataImpl
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledRegistry
import ar.pelotude.ohhsugoi.db.scheduler.Status
import ar.pelotude.ohhsugoi.util.image.downloadImage
import ar.pelotude.ohhsugoi.util.image.saveAsJpg
import ar.pelotude.ohhsugoi.util.uuidString
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.component.inject
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.div

class MangaDatabaseSQLite(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MangaDatabase, UsersDatabase, ScheduledRegistry<Long>, KordExKoinComponent {
    internal val dbConfig: DatabaseConfiguration by inject()

    private val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${dbConfig.sqlitePath}")

    private val database: Database = Database.Schema.run {
        kordLogger.info { "Database not specified: Assuming sqlite." }

        val userVersion = driver.executeQuery(
            null,
            "PRAGMA user_version;",
            { c -> c.getLong(0) },
            0
        ).value!!

        kordLogger.info { "Initializing sqlite database. sqlite user_version: $userVersion" }

        if (userVersion == 0L) {
            kordLogger.info { "Creating database from the scratch..." }
            create(driver)
        }

        // Run sqldelight migrations
        if (userVersion < version) {
            migrate(driver, userVersion.toInt(), version)
        }

        return@run Database(driver)
    }

    private val queries = database.mangaQueries

    private val imgStoreSemaphore = Semaphore(3)

    private fun String.escapeForFTS(): String {
        return this.replace("\"", "\"\"")
                .split(" ")
                .joinToString(" ") { "\"$it\"" }
    }

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
        tagFilterA: String?,
        tagFilterB: String?,
        demographicFilter: String?,
        limit: Long
    ): Collection<MangaWithTags> {
        return withContext(dispatcher) {
            val titleTagFilterA = tagFilterA?.lowercase()?.trim()
            val titleTagFilterB = tagFilterB?.lowercase()?.trim()

            return@withContext if (text != null) queries.searchMangaWithTagsFTS(
                    "title: ${text.escapeForFTS()}",
                    demographicFilter,
                    titleTagFilterA,
                    titleTagFilterB,
                    limit,
                    ::mangaSQLDmapper
            ).executeAsList()
            else queries.searchMangaWithTags(
                    demographicFilter, titleTagFilterA, titleTagFilterB, limit, ::mangaSQLDmapper
            ).executeAsList()
        }
    }

    override suspend fun searchMangaTitle(text: String, limit: Long): Collection<Pair<Long, String>> {
        if (text.isEmpty()) return listOf()

        return withContext(dispatcher) {
            queries.run {
                if (text.length < 3) {
                    searchMangaTitlesStartingWith(text, limit, ::Pair).executeAsList()
                } else {
                    searchMangaTitlesFTS(text.escapeForFTS(), limit, ::Pair).executeAsList()
                }
            }
        }
    }

    /**
     * Downloads the content from a [URL], assuming  it's an image file,
     * then randomizes a name for it based on [mangaId]
     * with the prefix "$[mangaId]-". The image is stored in the directory
     * specified by the configuration.
     *
     * @param[imgSource] Source of the image to be downloaded
     * @param[mangaId] The id of the manga that the image represents
     * @return A [String] containing filename of the downloaded image,
     * or null if the file couldn't be downloaded
     *
     * @throws IOException if an I/O error occurs when downloading the image
     * @throws UnsupportedDownloadException if the image file or its content is not supported
     */
    private suspend fun storeMangaCover(imgSource: URL, mangaId: Long): String {
        imgStoreSemaphore.withPermit {
            val mangaFileName = "$mangaId-${uuidString()}.jpg"
            val destiny = dbConfig.mangaImageDirectory / mangaFileName

            /** throws [IOException] and [UnsupportedDownloadException] */
            downloadImage(imgSource, dbConfig.mangaCoversWidth, dbConfig.mangaCoversHeight)
                .saveAsJpg(destiny.toFile(), 0.85f)

            kordLogger.info { "Added image: $mangaFileName" }

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
    private fun TransactionCallbacks.addTags(mangaId: Long, tags: Collection<String>) {
        val cleanTags = tags.map { it.lowercase().trim() }.filter(String::isNotBlank)

        if (cleanTags.isEmpty()) return

        for (tag in cleanTags) {
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

    override suspend fun updateManga(changes: MangaChanges, vararg flags: UpdateFlags): MangaWithTags = withContext(dispatcher) {
        val mangaId = changes.id

        val imgFilePath = if (changes.imgURLSource != null) {
            // throws DownloadException
            storeMangaCover(changes.imgURLSource, mangaId)
        } else null

        queries.transactionWithResult {
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
                queries.updateNonNullablesManga(
                    title, description, imgFilePath, link, demographic,
                    volumes, pagesPerVolume, chapters, pagesPerChapter, read?.sqliteBool(),
                    mangaId
                )
            }

            changes.tagsToAdd?.let { tags ->
                addTags(mangaId, tags)
            }

            val tagsToRemove = changes.tagsToRemove?.map { it.lowercase().trim() }?.filter(String::isNotBlank)

            tagsToRemove?.takeIf(Collection<*>::isNotEmpty)?.let { tags ->
                queries.removeTagAssociation(mangaId, tags)
            }

            queries.selectMangaWithTags(listOf(mangaId), ::mangaSQLDmapper).executeAsOne()
        }
    }

    override suspend fun deleteManga(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.deleteManga(id).executeAsOneOrNull() != null
        }
    }

    override suspend fun searchTags(name: String, limit: Long): Collection<String> {
        return withContext(dispatcher) {
            queries.searchTagTitle(name, limit).executeAsList()
        }
    }

    override suspend fun insertAnnouncement(content: String, scheduledDateTime: Instant, mentionId: ULong?): Long {
        return withContext(dispatcher) {
            queries.insertAnnouncement(content, scheduledDateTime.epochSecond, mentionId?.toString()).executeAsOne()
        }
    }

    override suspend fun getAnnouncement(id: Long): ScheduledPostMetadataImpl<Long>? {
        return withContext(dispatcher) {
            queries.selectAnnouncement(id) { id, content, date, mentionIdStr, status ->
                ScheduledPostMetadataImpl(
                    id,
                    Instant.ofEpochSecond(date),
                    content,
                    mentionIdStr?.toULong(),
                    Status.valueOf(status),
                )
            }.executeAsOneOrNull()
        }
    }

    override suspend fun getAnnouncements(status: Status?): Set<ScheduledPostMetadataImpl<Long>> {
        return withContext(dispatcher) {
            queries.selectAnnouncements(status?.name) { id, content, date, mentionIdStr, status ->
                ScheduledPostMetadataImpl(
                    id,
                    Instant.ofEpochSecond(date),
                    content,
                    mentionIdStr?.toULong(),
                    Status.valueOf(status),
                )
            }.executeAsList().toSet()
        }
    }

    override suspend fun removeMentionRoleFrom(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.unsetMentionFromAnnouncement(id).executeAsOneOrNull() != null
        }
    }

    override suspend fun editAnnouncement(
        id: Long,
        content: String?,
        scheduledDateTime: Instant?,
        mentionRole: ULong?
    ): Boolean {
        return withContext(dispatcher) {
            queries.editAnnouncement(
                id=id,
                content=content,
                mentionId=mentionRole?.toString(),
                date=scheduledDateTime?.epochSecond,
            ).executeAsOneOrNull() != null
        }
    }

    override suspend fun markAsCancelled(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.markAnnouncementAsCancelled(id).executeAsOneOrNull() != null
        }
    }

    override suspend fun markAsSent(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.markAnnouncementAsSent(id).executeAsOneOrNull() != null
        }
    }

    override suspend fun markAsFailed(id: Long): Boolean {
        return withContext(dispatcher) {
            queries.markAnnouncementAsFailed(id).executeAsOneOrNull() != null
        }
    }

    override suspend fun setUser(user: UserData) {
        withContext(dispatcher) {
            queries.insertUser(user.snowflakeId.toString(), user.zone.toString())
        }
    }

    override suspend fun getUser(snowflake: ULong): UserData? {
        return withContext(dispatcher) {
            queries.getUser(snowflake.toString()) { id, zone ->
                UserData(id.toULong(), ZoneId.of(zone))
            }.executeAsOneOrNull()
        }
    }
}
