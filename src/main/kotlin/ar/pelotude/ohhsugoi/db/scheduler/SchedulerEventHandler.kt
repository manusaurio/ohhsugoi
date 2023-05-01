package ar.pelotude.ohhsugoi.db.scheduler

interface SchedulerEventHandler<T> {
    fun handle(e: ScheduleEvent<T>)
}

sealed class ScheduleEvent<T>(val post: ScheduledPostMetadata<T>)
class Success<T>(post: ScheduledPostMetadata<T>): ScheduleEvent<T>(post)
class Failure<T>(post: ScheduledPostMetadata<T>, reason: String): ScheduleEvent<T>(post)