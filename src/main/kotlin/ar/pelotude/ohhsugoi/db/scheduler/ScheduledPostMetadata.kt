package ar.pelotude.ohhsugoi.db.scheduler

import java.time.Instant

class ScheduledPostMetadata<T>(
    val id: T,
    val execInstant: Instant,
    val text: String,
) {
    override fun toString() = "ScheduledPostMetadata(id=$id, dateTime=$execInstant, text=\"$text\")"

    override fun hashCode() = id.hashCode() + execInstant.hashCode() + text.hashCode() * 31

    override fun equals(other: Any?): Boolean {
        return if (other !is ScheduledPostMetadata<*>) false
        else id == other.id && execInstant == other.execInstant && text == other.text
    }

    operator fun component1() = id

    operator fun component2() = execInstant

    operator fun component3() = text
}