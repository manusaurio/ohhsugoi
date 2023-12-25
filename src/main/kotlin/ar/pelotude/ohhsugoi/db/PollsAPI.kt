package ar.pelotude.ohhsugoi.db

import java.net.URL
import java.time.Instant
import java.util.*

open class PollOption(open val description: String)

data class ExistingPollOption(
    val id: UUID,
    override val description: String,
    val votes: Long,
) : PollOption(description)

open class Poll(
    open val authorID: Long? = null,
    open val title: String,
    open val description: String?,
    open val imgByteArray: ByteArray? = null,
    open val options: List<PollOption>,
    open val singleVote: Boolean = true,
)

class ExistingPoll(
    val id: Long,
    val creationInstant: Instant,
    override val authorID: Long? = null,
    override val title: String,
    override val description: String?,
    val finishedInstant: Instant?,
    val imgSrc: URL?,
    override val options: List<ExistingPollOption>,
    override val singleVote: Boolean = true,
) : Poll(
    authorID=authorID,
    title=title,
    description=description,
    options=options,
    singleVote=singleVote,
) {
    override val imgByteArray: ByteArray? by lazy {
        imgSrc?.openStream()?.readAllBytes()
    }
}

open class PollException(message: String, cause: Throwable? = null) : Exception(message, cause)

open class PollUnsuccessfulOperationException(message: String, cause: Throwable? = null): PollException(message, cause)

class PollUnsuccessfulVoteException(message: String, cause: Throwable? = null) : PollUnsuccessfulOperationException(message, cause)

interface PollsDatabase {
    suspend fun createPoll(poll: Poll): ExistingPoll

    suspend fun getPoll(pollID: Long): ExistingPoll?

    suspend fun getPollByOptionID(pollOptionID: UUID): ExistingPoll?

    suspend fun vote(pollOptionID: UUID, snowflakeUserID: ULong): Boolean

    suspend fun finishPoll(pollID: Long)
}