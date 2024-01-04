package ar.pelotude.ohhsugoi.db.scheduler

import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.reflect.KClass

enum class Status(val code: Long) {
    PENDING(0),
    SENT(1),
    CANCELLED(2),
    FAILED(3),
}

interface ScheduledPostMetadata {
    val execInstant: Instant
    val text: String
}

interface Storable<T : Any> {
    val id: T
    val status: Status
}

open class RawPost(
    val content: JsonObject,
    val execInstant: Instant,
    val postType: String,
)

class StoredRawPost<T: Any>(
    override val id: T,
    override val status: Status,
    content: JsonObject,
    execInstant: Instant,
    postType: String,
) : RawPost(content, execInstant, postType), Storable<T>

class StoredPost<T : Any, U : SchedulablePost>(
    val post: U,
    override val id: T,
    override val status: Status = Status.PENDING,
) : Storable<T>, SchedulablePost by post

interface SchedulablePost : ScheduledPostMetadata {
    val identifier: String

    suspend fun process(client: HttpClient): HttpStatusCode
}

/**
 * Class to specify how [SchedulablePost]s should be saved in a registry,
 * such as a database, as JSON.
 *
 * This abstract class for serializers is not meant to be implemented to
 * generate the JSON objects that are sent to the target online platforms
 * or anything similar: it's purpose is to be used to serialize and
 * deserialize schedulable posts as they are understood by the scheduler.
 */
abstract class SchedulablePostSerializer<T : SchedulablePost> {
    abstract fun fromJson(json: JsonObject, execInstant: Instant): T

    abstract fun toJson(post: T): JsonObject
}

/**
 * Annotation to be used in [SchedulablePost]s, specifying their [identifier] and [SchedulablePostSerializer].
 *
 * Classes using this annotation can then be registered in the scheduler.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SerializableStorablePost(val identifier: String, val postSerializer: KClass<out SchedulablePostSerializer<*>>)