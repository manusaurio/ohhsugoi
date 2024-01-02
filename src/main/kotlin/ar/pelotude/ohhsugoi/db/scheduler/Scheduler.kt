package ar.pelotude.ohhsugoi.db.scheduler

import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.kordLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.set
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class Scheduler<T : Any> (private val registry: ScheduledRegistry<T>, parent: Job? = null): KordExKoinComponent {
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
    private val scope = CoroutineScope(supervisorJob + exceptionHandler)

    private inner class ScheduledPost(
            val storedPost: StoredPost<T, *>,
            private val job: Job,
    ) : Storable<T> by storedPost, Job by job, SchedulablePost by storedPost {

        override fun hashCode() = storedPost.hashCode() + job.hashCode() * 17

        override fun equals(other: Any?): Boolean {
            return if (other !is Scheduler<*>.ScheduledPost) false
            else storedPost == other.storedPost && job == other.job
        }

        override fun toString() = "ScheduledPost(id=$id, datetime=$execInstant, text=\"$text\", job=$job)"
    }

    private val serializers = ConcurrentHashMap<String, SchedulablePostSerializer<out SchedulablePost>>()

    private fun Map<String, SchedulablePostSerializer<out SchedulablePost>>.getOrThrow(id: String)
            = this[id] ?: throw SerializerException("Missing serializer for $id")

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

    /** Launches and returns a job for the post described in [p].
     * This is meant to be used internally to construct a [ScheduledPost] to
     * put into [scheduledPosts]. */
    private fun <U>launchScheduled(p: U): Job where U : StoredPost<T, *>, U : SchedulablePost {
        val id = p.id
        val execInstant = p.execInstant
        val waitingTime = Duration.between(Instant.now(), execInstant).toMillis()

        return scope.launch {
            delay(waitingTime)

            p.process(client).run {
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

                if (success) listeners.forEach { it.handle(Success(p)) }
                else listeners.forEach { it.handle(Failure(p, description)) }
            }
        }
    }

    private fun <T : Any> StoredRawPost<T>.toStoredPost(): StoredPost<T, *> {
        val schedulable = serializers.getOrThrow(this.postType).fromJson(this.content, this.execInstant)

        return StoredPost(schedulable, id, status)
    }

    /** Loads pending messages from the database.
     * This should be called after every relevant listener has
     * subscribed to the scheduler, so they don't miss the first
     * possible events. */
    suspend fun synchronize() {
        registry.getAnnouncements(Status.PENDING).forEach { loadScheduled(it.toStoredPost()) }
    }

    /** Sets everything up for a job and launches it. This is
     * meant to be used with posts stored in the registry only. */
    private fun loadScheduled(metadata: StoredPost<T, *>) {
        val job = launchScheduled(metadata)

        val scheduledPost = ScheduledPost(metadata, job)

        scheduledPosts[metadata.id] = scheduledPost

        job.invokeOnCompletion {
            scheduledPosts.remove(metadata.id)
        }
    }

    suspend fun <U : SchedulablePost>schedule(post: U): StoredPost<T, out U> {
        if (!supervisorJob.isActive)
            throw IllegalStateException("Tried to schedule a post, but the scheduler isn't running")

        val serializer = serializers[post.identifier]!! // TODO:

        serializer as SchedulablePostSerializer<U>
        val jsonContent = serializer.toJson(post)

        val postId = registry.insertAnnouncement(RawPost(jsonContent, post.execInstant, post.identifier))

        val stored = StoredPost(post, postId)
        val job = launchScheduled(stored)
        val scheduledPost = ScheduledPost(stored, job)

        scheduledPosts[postId] = scheduledPost

        // now we know this id's value is present
        // in the map, so it's safe to remove it
        // (just in case some madman schedules something 0 seconds ahead)
        job.invokeOnCompletion {
            scheduledPosts.remove(postId)
        }

        return stored
    }

    operator fun contains(k: T) = k in scheduledPosts

    /**
     * Retrieves a stored post.
     *
     * @param[clazz] Class of the [ScheduledPost] to be retrieved as.
     * @param[k] Id. of the post.
     *
     * @throws[SerializerException] If the provided [clazz] is incorrect.
     * @return The post, if it exists and it's of the class [clazz].
     */
    suspend fun <U : SchedulablePost> get(clazz: Class<out U>, k: T): StoredPost<T, out U>? {
        val post = scheduledPosts[k]?.storedPost ?: registry.getAnnouncement(k)?.toStoredPost() ?: return null

        try {
            val actualPost = clazz.cast(post.post) as U
            return StoredPost(actualPost, post.id, post.status)
        } catch(e: ClassCastException) {
            throw SerializerException("The requested post (id. $k) could not be retrieved as ${clazz.name}", e)
        }


    }

    /**
     * Retrieves a stored post.
     *
     * @param[U] Class of the [ScheduledPost] to be retrieved as.
     * @param[k] Id. of the post.
     *
     * @throws[SerializerException] If the provided [U] is incorrect.
     * @return The post, if it exists and it's of the class specified as [U].
     */
    suspend inline fun <reified U : SchedulablePost>get(k: T): StoredPost<T, out U>? = get(U::class.java, k)

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

    suspend fun getPosts(statusFilter: Status? = Status.PENDING): List<StoredPost<T, out SchedulablePost>> {
        return registry.getAnnouncements(statusFilter).map { it.toStoredPost() }
    }

    fun <U : SchedulablePost>registerPostType(postType: KClass<out U>) {
        val annotation = postType.annotations
            .find { a -> a.annotationClass == SerializableStorablePost::class } as? SerializableStorablePost

        if (annotation === null) throw SerializerFatalException("Missing serializable annotation in class during registration")

        val postTypeIdentifier = annotation.identifier

        val serializer = annotation
            .postSerializer
            .primaryConstructor
            ?.takeIf { c -> c.parameters.isEmpty() }
            ?.call()
            ?: throw SerializerFatalException("Missing parameterless constructor in serializable class during registration")

        serializers[postTypeIdentifier] = serializer
    }

    inline fun <reified U : SchedulablePost>registerPostType() = registerPostType(U::class)

    /**
     * Helper function to be called after updating existing scheduled posts in the
     * registry and synchronize the local ongoing [ScheduledPost]'s job.
     */
    private suspend fun refresh(k: T) {
        // this is a safe way to refresh without synchronizing possible concurrency
        scheduledPosts[k]?.cancel()
        registry.getAnnouncement(k)!!.toStoredPost().run(::loadScheduled)
    }
}