package ar.pelotude.ohhsugoi.db.scheduler

import java.time.Instant

enum class Status(val code: Long) {
    PENDING(0),
    SENT(1),
    CANCELLED(2),
    FAILED(3),
}

interface ScheduledPostMetadata<T> {
    val id: T
    val execInstant: Instant
    val text: String
    val mentionId: ULong?
    val status: Status
}

class ScheduledPostMetadataImpl<T>(
        override val id: T,
        override val execInstant: Instant,
        override val text: String,
        override val mentionId: ULong?,
        override val status: Status = Status.PENDING,
) : ScheduledPostMetadata<T> {
    override fun toString() = "ScheduledPostMetadata(id=$id, dateTime=$execInstant, text=\"$text\")"

    override fun hashCode() = id.hashCode() + execInstant.hashCode() + text.hashCode() * 31

    override fun equals(other: Any?): Boolean {
        return if (other !is ScheduledPostMetadataImpl<*>) false
        else id == other.id && execInstant == other.execInstant && text == other.text
    }

    operator fun component1() = id

    operator fun component2() = execInstant

    operator fun component3() = text

    operator fun component4() = mentionId

    operator fun component5() = status
}