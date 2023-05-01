package ar.pelotude.ohhsugoi.db.scheduler

import java.time.ZonedDateTime

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