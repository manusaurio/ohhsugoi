package ar.pelotude.ohhsugoi.db.scheduler

interface SchedulerEventHandler<T : Any> {
    fun handle(e: ScheduleEvent<T>)
}

sealed class ScheduleEvent<T : Any>(val post: StoredPost<T, out SchedulablePost>)
class Success<T : Any>(post: StoredPost<T, out SchedulablePost>): ScheduleEvent<T>(post)
class Failure<T : Any>(post: StoredPost<T, out SchedulablePost>, val reason: String): ScheduleEvent<T>(post)