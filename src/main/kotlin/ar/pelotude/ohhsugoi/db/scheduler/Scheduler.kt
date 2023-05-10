package ar.pelotude.ohhsugoi.db.scheduler

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import org.koin.core.component.get
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList

class Scheduler<T> (private val registry: ScheduledRegistry<T>, parent: Job? = null): KordExKoinComponent {
    private val config = get<SchedulerConfiguration>()

    private val supervisorJob = SupervisorJob(parent)
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        when (error) {
            is IOException -> kordLogger.error(error) { "Error in the registry scope. " +
                    "Check your database's health and your connection to it." }
            is SerializationException -> kordLogger.error(error) { "Something went wrong during a post serialization." }
            else -> throw error
        }
    }

    /* scope only meant for scheduled posts */
    private val scope = CoroutineScope(supervisorJob + exceptionHandler).apply {
        launch {
            delay(1000)
            registry.getAnnouncements(Status.PENDING).forEach(::loadScheduled)
        }
    }

    private inner class ScheduledPost(
            private val metadata: ScheduledPostMetadataImpl<T>,
            private val job: Job,
    ) : ScheduledPostMetadata<T> by metadata, Job by job {

        override fun hashCode() = metadata.hashCode() + job.hashCode() * 17

        override fun equals(other: Any?): Boolean {
            return if (other !is Scheduler<*>.ScheduledPost) false
            else metadata == other.metadata && job == other.job
        }

        override fun toString() = "ScheduledPost(id=$id, datetime=$execInstant, text=\"$text\", job=$job)"
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }

        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { _, response -> !response.status.isSuccess() }
            delayMillis { retry: Int ->
                retry * 5000L
            }
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

    /** Launches and returns a job for the post described in [metadata].
     * This is meant to be used internally to construct a [ScheduledPost] to
     * put into [scheduledPosts]. */
    private fun launchScheduled(metadata: ScheduledPostMetadataImpl<T>): Job {
        val (id, execInstant, text) = metadata
        val mention: String? = metadata.mentionId?.let { mentionId ->
            when (mentionId) {
                config.discordGuildId -> "@everyone"
                else -> "<@&$mentionId>"
            }
        }

        val waitingTime = Duration.between(Instant.now(), execInstant).toMillis()

        return scope.launch {
            delay(waitingTime)

            client.post(config.webhook) {
                contentType(ContentType.Application.Json)
                setBody(
                        DiscordHookMessage(
                                content = mention,
                                embeds = listOf(
                                        DiscordHookMessageEmbed(
                                                null,
                                                text
                                        )
                                )
                        )
                )
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
    private fun loadScheduled(metadata: ScheduledPostMetadataImpl<T>) {
        val job = launchScheduled(metadata)

        val scheduledPost = ScheduledPost(metadata, job)

        scheduledPosts[metadata.id] = scheduledPost

        job.invokeOnCompletion {
            scheduledPosts.remove(metadata.id)
        }
    }

    suspend fun schedule(text: String, execInstant: Instant, mentionId: ULong?): ScheduledPostMetadata<T> {
        if (!supervisorJob.isActive)
            throw IllegalStateException("Tried to schedule a post, but the scheduler isn't running")

        val postId = registry.insertAnnouncement(text, execInstant, mentionId)

        val metadata = ScheduledPostMetadataImpl(postId, execInstant, text, mentionId)
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

    suspend fun get(k: T): ScheduledPostMetadata<T>? {
        return scheduledPosts[k] ?: registry.getAnnouncement(k)
    }

    /** Cancels a scheduled post. */
    suspend fun cancel(k: T): Boolean {
        registry.markAsCancelled(k).let { success ->
            if (success) scheduledPosts[k]?.cancel()
            return success
        }
    }

    /** Suspends until this scheduler is stopped. */
    suspend fun join() = supervisorJob.join()

    /** Stops this scheduler, without affecting the registry. */
    suspend fun stop() = supervisorJob.cancelAndJoin()

    fun subscribe(o: SchedulerEventHandler<T>) = listeners.add(o)

    fun unsubscribe(o: SchedulerEventHandler<T>) = listeners.remove(o)

    suspend fun getPosts(statusFilter: Status? = Status.PENDING): Set<ScheduledPostMetadataImpl<T>> {
        return registry.getAnnouncements(statusFilter)
    }
}