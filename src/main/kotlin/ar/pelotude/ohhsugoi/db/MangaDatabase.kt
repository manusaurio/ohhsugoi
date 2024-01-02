package ar.pelotude.ohhsugoi.db

import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ar.pelotude.db.Database
import ar.pelotude.ohhsugoi.*
import ar.pelotude.ohhsugoi.db.scheduler.RawPost
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledRegistry
import ar.pelotude.ohhsugoi.db.scheduler.Status
import ar.pelotude.ohhsugoi.db.scheduler.StoredRawPost
import ar.pelotude.ohhsugoi.util.calculateSHA256
import ar.pelotude.ohhsugoi.util.image.asJpgByteArray
import ar.pelotude.ohhsugoi.util.image.downloadImage
import ar.pelotude.ohhsugoi.util.image.saveAsJpg
import ar.pelotude.ohhsugoi.util.image.toBufferedImage
import ar.pelotude.ohhsugoi.util.toByteArray
import ar.pelotude.ohhsugoi.util.toUUID
import ar.pelotude.ohhsugoi.util.uuidString
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.koin.core.component.inject
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.*
import kotlin.io.path.div

class MangaDatabaseSQLite(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MangaDatabase, UsersDatabase, PollsDatabase, ScheduledRegistry<Long>, KordExKoinComponent {
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
                .associateBy(MangaWithTags::id).let { mangaMap ->
                    ids.map { mangaMap[it] }.filterNotNull()
            }
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

    /*
    override suspend fun insertAnnouncement(content: String, scheduledDateTime: Instant, mentionId: ULong?): Long {
        return withContext(dispatcher) {
            queries.insertAnnouncement(content, scheduledDateTime.epochSecond, mentionId?.toString()).executeAsOne()
        }
    }

     */

    override suspend fun insertAnnouncement(schedulablePost: RawPost, authorId: Long?): Long {
        return withContext(dispatcher) {
            queries.insertAnnouncement(
                schedulablePost.content.toString(),
                schedulablePost.execInstant.toEpochMilli() / 1000L,
                schedulablePost.postType,
            ).executeAsOne()
        }
    }

    override suspend fun getAnnouncement(id: Long): StoredRawPost<Long>? {
        return withContext(dispatcher) {
            queries.selectAnnouncement(id) {
                    id: Long,
                    content: String,
                    scheduled_date: Long,
                    announcement_type: String,
                    status: String,
                ->
                StoredRawPost(
                    id,
                    Status.valueOf(status),
                    Json.decodeFromString(content),
                    Instant.ofEpochSecond(scheduled_date),
                    announcement_type,
                )
            }.executeAsOneOrNull()
        }
    }

    override suspend fun getAnnouncements(status: Status?): Set<StoredRawPost<Long>> {
        return withContext(dispatcher) {
            queries.selectAnnouncements(status?.name) {
                    id: Long,
                    content: String,
                    scheduled_date: Long,
                    announcement_type: String,
                    status: String,
                ->

                StoredRawPost(
                    id=id,
                    status=Status.valueOf(status),
                    content=Json.decodeFromString(content),
                    execInstant=Instant.ofEpochSecond(scheduled_date),
                    postType=announcement_type,
                )
            }.executeAsList().toSet()
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

    override suspend fun createPoll(poll: Poll): ExistingPoll {
        return withContext(Dispatchers.IO) {
            val writtenFile = imgStoreSemaphore.withPermit {
                poll.imgByteArray?.inputStream()?.toBufferedImage()?.asJpgByteArray()?.let { bi ->
                    val targetFileName = dbConfig.pollImagesDirectory / "${bi.calculateSHA256()}.jpg"

                    try {
                        Files.write(targetFileName, bi)
                    } catch (e: IOException) {
                        kordLogger.error(e) { "An error occurred trying to write $targetFileName" }
                        null
                    }
                }
            }

            database.transactionWithResult {
                val insertedPollID = queries.insertPoll(
                    poll.authorID,
                    poll.title,
                    poll.description,
                    poll.singleVote.sqliteBool(),
                    writtenFile?.fileName?.toString(),
                ).executeAsOne()

                val existingPollOptions: List<ExistingPollOption> = poll.options.map { option ->
                    val uuid = UUID.randomUUID()!!

                    val uuidByteArray = ByteBuffer.wrap(ByteArray(16))!!.apply {
                        putLong(uuid.mostSignificantBits)
                        putLong((uuid.leastSignificantBits))
                    }.array()

                    queries.insertPollOption(uuidByteArray, insertedPollID, option.description)

                    ExistingPollOption(uuid, option.description, 0)
                }

                queries.selectPoll(insertedPollID, null) {
                        id: Long, insertionDate: Long, authorID: Long?, title: String,
                        description: String?, imageURL: String?, singleVote: Long,
                        finishedDate: Long?, ->

                    pollSQLDMapper(
                        id,
                        insertionDate,
                        authorID,
                        title,
                        description,
                        imageURL,
                        singleVote,
                        finishedDate,
                        existingPollOptions,
                    )
                }.executeAsOne()
            }
        }
    }

    override suspend fun getPoll(pollID: Long): ExistingPoll? {
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val existingPollOptions = queries.selectPollOptionsByPollID(pollID) {
                        id: ByteArray,
                        description: String,
                        votes: Long,
                    ->

                    val optionUUID: UUID = ByteBuffer.wrap(id)!!.run {
                        UUID(getLong(), getLong())
                    }

                    return@selectPollOptionsByPollID ExistingPollOption(optionUUID, description, votes)
                }.executeAsList()

                val existingPoll = queries.selectPoll(pollID, null) {
                    id: Long, insertionDate: Long, authorID: Long?, title: String,
                    description: String?, imageURL: String?, singleVote: Long,
                    finishedDate: Long?, ->

                    pollSQLDMapper(
                        id,
                        insertionDate,
                        authorID,
                        title,
                        description,
                        imageURL,
                        singleVote,
                        finishedDate,
                        existingPollOptions,
                    )
                }.executeAsOneOrNull()

                return@transactionWithResult existingPoll
            }
        }
    }

    override suspend fun getPollByOptionID(pollOptionID: UUID): ExistingPoll? {
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                queries.selectPollByOptionID(
                    pollOptionID.toByteArray(),
                ).executeAsOneOrNull()?.let { rawPoll ->
                    val existingPollOptions = queries.selectPollOptionsByPollID(rawPoll.id) {
                            optionID: ByteArray,
                            description: String,
                            votes: Long,
                        ->
                        ExistingPollOption(
                            optionID.toUUID(),
                            description,
                            votes,
                        )
                    }.executeAsList()

                    return@transactionWithResult with (rawPoll) {
                        pollSQLDMapper(
                            id,
                            insertion_date,
                            author_id,
                            title,
                            description,
                            image_filename,
                            single_vote,
                            finished_date,
                            existingPollOptions,
                        )
                    }
                }
            }
        }
    }

    override suspend fun vote(pollOptionID: UUID, snowflakeUserID: ULong): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                queries.toggleVote(snowflakeUserID.toLong(), pollOptionID.toByteArray())
                    .executeAsOneOrNull()?.boolean
                    ?: throw PollUnsuccessfulVoteException("Vote request returned null. Does the poll exist?")
            } catch (e: SQLiteException) {
                throw when (e.resultCode) {
                    SQLiteErrorCode.SQLITE_CONSTRAINT_TRIGGER ->
                        PollUnsuccessfulVoteException("Vote request failed. Has the poll been marked as finished?", e)
                    else -> e
                }
            }
        }
    }

    override suspend fun finishPoll(pollID: Long) {
        withContext(Dispatchers.IO) {
            queries.finishPoll(pollID).executeAsOneOrNull()
                ?: throw PollUnsuccessfulOperationException("Could not mark poll $pollID as finished.")
        }
    }
}
