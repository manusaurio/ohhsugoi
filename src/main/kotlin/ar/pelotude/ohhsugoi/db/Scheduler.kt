package ar.pelotude.ohhsugoi.db

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

sealed class ScheduleEvent(val post: Scheduler.ScheduledPost)

class Success(post: Scheduler.ScheduledPost): ScheduleEvent(post)
class Failure(post: Scheduler.ScheduledPost, reason: String): ScheduleEvent(post)

interface SchedulerEventHandler {
    fun handle(e: ScheduleEvent)
}

class Scheduler: CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob()

    inner class ScheduledPost(val id: Long, val dateTime: LocalDateTime, val text: String) {
        override fun toString(): String {
            return "ScheduledPost(id=$id, dateTime=$dateTime, text=\"$text\")"
        }

        override fun hashCode() = id.hashCode() + dateTime.hashCode() + text.hashCode() * 31

        override fun equals(other: Any?): Boolean {
            return if (other !is ScheduledPost) false
            else id == other.id && dateTime == other.dateTime && text == other.text
        }

        fun cancel() = this@Scheduler.cancel(id)
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

    private val scheduledPosts: MutableMap<Long, Pair<ScheduledPost, Job>> = ConcurrentHashMap()

    private val listeners = mutableListOf<SchedulerEventHandler>()

    private val _ids = AtomicLong(0)
    private val lastInsertedRow: Long
        get() = _ids.incrementAndGet()

    private val webhook = System.getenv("DISCORD_WEBHOOK")

    fun schedule(text: String, millis: Long): Long {
        // TODO: add programmed post to database, then to local map. Remove `AtomicLong` placeholder
        val thisId = lastInsertedRow
        val execTime = LocalDateTime.now().plusSeconds(millis / 1000L)
        val scheduledPost = ScheduledPost(thisId, execTime, text)

        scheduledPosts[thisId] = scheduledPost to launch {
            delay(millis)

            client.post(webhook) {
                contentType(ContentType.Application.Json)

                setBody(DiscordHookMessage(content=text))
            }.status.run {
                // TODO: update database first, mark as done
                if (value in 200..299) {
                    listeners.forEach { it.handle(Success(scheduledPost)) }
                } else {
                    listeners.forEach { it.handle(Failure(scheduledPost, description)) }
                }
                scheduledPosts.remove(thisId)
            }
        }

        return thisId
    }

    operator fun contains(k: Long) = k in scheduledPosts

    operator fun get(k: Long): ScheduledPost? = scheduledPosts[k]?.first

    fun cancel(k: Long) = scheduledPosts[k]?.let { (sp, job) ->
        // TODO: remove from database first
        job.cancel()
        scheduledPosts.remove(k)
    }
}

@Serializable
data class DiscordHookMessage(val username: String="Sheska", val content: String)