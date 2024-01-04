package ar.pelotude.ohhsugoi.db.scheduler

/**
 * Interface to be implemented by a class that makes the programmed posts
 * persistent, like a database or a file. All the methods should be
 * thread-safe.
 *
 * Ideally, scheduled announcements should not change their state from sent to
 * anything else. This means a cancelled or failed post can be sent later on
 * (even though it's discouraged,) but a sent post should not be changed to cancelled.
 * or failed. */
interface ScheduledRegistry<T: Any> {
    suspend fun insertAnnouncement(schedulablePost: RawPost, authorId: Long? = null): T

    suspend fun markAsCancelled(id: T): Boolean

    suspend fun markAsFailed(id: T): Boolean

    suspend fun markAsSent(id: T): Boolean

    suspend fun getAnnouncements(status: Status?): Set<StoredRawPost<T>>

    suspend fun getAnnouncement(id: T): StoredRawPost<T>?
}