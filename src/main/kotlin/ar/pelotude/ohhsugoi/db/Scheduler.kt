package ar.pelotude.ohhsugoi.db

import dev.kord.core.kordLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

sealed class ScheduleEvent<T>(val post: ScheduledPostMetadata<T>)

class Success<T>(post: ScheduledPostMetadata<T>): ScheduleEvent<T>(post)
class Failure<T>(post: ScheduledPostMetadata<T>, reason: String): ScheduleEvent<T>(post)

interface SchedulerEventHandler<T> {
    fun handle(e: ScheduleEvent<T>)
}

/**
 * Interface to be implemented by a class that makes the programmed posts
 * persistent, like a database or a file. All the methods should be
 * thread-safe.
 *
 * Ideally, scheduled announcements should not change their state from sent to
 * anything else. This means a cancelled or failed post can be sent later on
 * (even though it's discouraged,) but a sent post should not be changed to cancelled.
 * or failed.
 */
interface ScheduledRegistry<T> {
    suspend fun insertAnnouncement(content: String, scheduledDateTime: ZonedDateTime): T

    suspend fun markAsCancelled(id: T)

    suspend fun markAsFailed(id: T)

    suspend fun markAsSent(id: T)

    suspend fun getPendingAnnouncements(): Set<ScheduledPostMetadata<T>>
}

class ScheduledPostMetadata<T>(
    val id: T,
    val execTime: ZonedDateTime,
    val text: String,
) {
    override fun toString() = "ScheduledPostMetadata(id=$id, dateTime=$execTime, text=\"$text\")"

    override fun hashCode() = id.hashCode() + execTime.hashCode() + text.hashCode() * 31

    override fun equals(other: Any?): Boolean {
        return if (other !is ScheduledPostMetadata<*>) false
        else id == other.id && execTime == other.execTime && text == other.text
    }

    operator fun component1() = id

    operator fun component2() = execTime

    operator fun component3() = text
}

class Scheduler<T> private constructor(private val registry: ScheduledRegistry<T>, parent: Job? = null) {
    companion object {
        suspend operator fun <T> invoke(registry: ScheduledRegistry<T>, parent: Job? = null) = Scheduler(registry, parent).apply {
            populate()
        }
    }

    private suspend fun populate() {
        registry.getPendingAnnouncements().forEach(::loadScheduled)
    }

    private val supervisorJob = SupervisorJob(parent)
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        when (error) {
            is IOException -> kordLogger.error(error) { "The connection in a scheduled post failed." }
            is SerializationException -> kordLogger.error(error) { "Something went wrong during a post serialization." }
            else -> throw error
        }
    }

    /* scope only meant for scheduled posts */
    private val scope = CoroutineScope(supervisorJob + exceptionHandler)

    inner class ScheduledPost(
        private val metadata: ScheduledPostMetadata<T>,
        private val job: Job,
    ) {
        val id
            get() = metadata.id

        val execTime
            get() = metadata.execTime

        val text
            get() = metadata.text

        override fun hashCode() = metadata.hashCode() + job.hashCode() * 17

        override fun equals(other: Any?): Boolean {
            return if (other !is Scheduler<*>.ScheduledPost) false
            else metadata == other.metadata && job == other.job
        }

        /** Stops this scheduled post locally and then tries to mark it as cancelled in the registry */
        suspend fun cancel() {
            job.cancel()
            registry.markAsCancelled(id)
        }
        override fun toString() = "ScheduledPost(id=$id, datetime=$execTime, text=\"$text\", job=$job)"
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }

        engine {
            maxConnectionsCount = 4
            threadsCount = 2

            endpoint {
                connectAttempts = 5
                connectTimeout = 15_000
            }
        }
    }

    private val scheduledPosts: ConcurrentMap<T, ScheduledPost> = ConcurrentHashMap()

    private val listeners = mutableListOf<SchedulerEventHandler<T>>()

    private val webhook = System.getenv("DISCORD_WEBHOOK")

    /** Launches and returns a job for the post described in [metadata].
     * This is meant to be used internally to construct a [ScheduledPost] to
     * put into [scheduledPosts]. */
    private fun launchScheduled(metadata: ScheduledPostMetadata<T>): Job {
        val (id, execDateTime, text) = metadata

        val waitingTime = Duration.between(ZonedDateTime.now(), execDateTime).toMillis()

        return scope.launch {
            delay(waitingTime)

            client.post(webhook) {
                contentType(ContentType.Application.Json)
                setBody(DiscordHookMessage(content=text))
            }.status.run {
                // we prevent `markAs...` cancellation to make
                // sure the new state is reflected in the database
                val success = withContext(NonCancellable) {
                    if (value in 200..299) {
                        registry.markAsSent(id)
                        true
                    } else {
                        registry.markAsFailed(id)
                        false
                    }
                }

                if (success) listeners.forEach { it.handle(Success(metadata)) }
                else listeners.forEach { it.handle(Failure(metadata, description)) }
            }
        }
    }

    /** Sets everything up for a job and launches it. This is
     * meant to be used with posts stored in the registry only. */
    private fun loadScheduled(metadata: ScheduledPostMetadata<T>) {
        val job = launchScheduled(metadata)

        val scheduledPost = ScheduledPost(metadata, job)

        scheduledPosts[metadata.id] = scheduledPost

        job.invokeOnCompletion {
            scheduledPosts.remove(metadata.id)
        }
    }

    suspend fun schedule(text: String, execDateTime: ZonedDateTime): ScheduledPost {
        if (!supervisorJob.isActive)
            throw IllegalStateException("Tried to schedule a post, but the scheduler isn't running")

        val postId = registry.insertAnnouncement(text, execDateTime)

        val metadata = ScheduledPostMetadata(postId, execDateTime, text)
        val job = launchScheduled(metadata)

        val scheduledPost = ScheduledPost(metadata, job)

        scheduledPosts[postId] = scheduledPost

        // now we know this id's value is present
        // in the map, so it's safe to remove it
        // (just in case some madman schedules something 0 seconds ahead)
        job.invokeOnCompletion {
            scheduledPosts.remove(postId)
        }

        return scheduledPost
    }

    operator fun contains(k: T) = k in scheduledPosts

    operator fun get(k: T): ScheduledPost? = scheduledPosts[k]

    /** Cancels a scheduled post. */
    suspend fun cancel(k: T) = scheduledPosts[k]?.let { post ->
        registry.markAsCancelled(k)
        post.cancel()
    }

    /** Suspends until this scheduler is stopped. */
    suspend fun join() = supervisorJob.join()

    /** Stops this scheduler, without affecting the registry. */
    suspend fun stop() = supervisorJob.cancelAndJoin()
}

@Serializable
data class DiscordHookMessage(val username: String="Sheska", val content: String)