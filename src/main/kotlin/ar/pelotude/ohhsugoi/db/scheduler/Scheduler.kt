package ar.pelotude.ohhsugoi.db.scheduler

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList

class Scheduler<T> private constructor(private val registry: ScheduledRegistry<T>, parent: Job? = null) {
    companion object {
        suspend operator fun <T> invoke(registry: ScheduledRegistry<T>, parent: Job? = null) = Scheduler(
            registry,
            parent
        ).apply {
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

        val execInstant
            get() = metadata.execInstant

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
        override fun toString() = "ScheduledPost(id=$id, datetime=$execInstant, text=\"$text\", job=$job)"
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

    private val listeners = CopyOnWriteArrayList<SchedulerEventHandler<T>>()

    private val webhook: String by inject(qualifier = named("hookToken"))

    /** Launches and returns a job for the post described in [metadata].
     * This is meant to be used internally to construct a [ScheduledPost] to
     * put into [scheduledPosts]. */
    private fun launchScheduled(metadata: ScheduledPostMetadata<T>): Job {
        val (id, execInstant, text) = metadata

        val waitingTime = Duration.between(Instant.now(), execInstant).toMillis()

        return scope.launch {
            delay(waitingTime)

            client.post(webhook) {
                contentType(ContentType.Application.Json)
                setBody(DiscordHookMessage(content = text))
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

    suspend fun schedule(text: String, execInstant: Instant): ScheduledPost {
        if (!supervisorJob.isActive)
            throw IllegalStateException("Tried to schedule a post, but the scheduler isn't running")

        val postId = registry.insertAnnouncement(text, execInstant)

        val metadata = ScheduledPostMetadata(postId, execInstant, text)
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

    fun subscribe(o: SchedulerEventHandler<T>) = listeners.add(o)

    fun unsubscribe(o: SchedulerEventHandler<T>) = listeners.remove(o)
}