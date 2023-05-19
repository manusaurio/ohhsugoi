package ar.pelotude.ohhsugoi.db.scheduler

interface SchedulerEventHandler<T> {
    fun handle(e: ScheduleEvent<T>)
}

sealed class ScheduleEvent<T>(val post: ScheduledPostMetadataImpl<T>)
class Success<T>(post: ScheduledPostMetadataImpl<T>): ScheduleEvent<T>(post)
class Failure<T>(post: ScheduledPostMetadataImpl<T>, val reason: String): ScheduleEvent<T>(post)