package ar.pelotude.ohhsugoi.bot.polls

import ar.pelotude.ohhsugoi.bot.EphemeralOrPublicView
import ar.pelotude.ohhsugoi.bot.UtilsExtensionConfiguration
import ar.pelotude.ohhsugoi.bot.mangaView
import ar.pelotude.ohhsugoi.bot.optionIcons
import ar.pelotude.ohhsugoi.bot.respondWithError
import ar.pelotude.ohhsugoi.bot.toEmbed
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.Poll
import ar.pelotude.ohhsugoi.db.PollException
import ar.pelotude.ohhsugoi.db.PollOption
import ar.pelotude.ohhsugoi.db.PollUnsuccessfulOperationException
import ar.pelotude.ohhsugoi.db.PollsDatabase
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import ar.pelotude.ohhsugoi.db.scheduler.platforms.DiscordWebhookMessage
import ar.pelotude.ohhsugoi.db.scheduler.platforms.XPost
import ar.pelotude.ohhsugoi.util.image.asJpgByteArray
import ar.pelotude.ohhsugoi.util.image.stitch
import ar.pelotude.ohhsugoi.util.toMentionOn
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.long
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalLong
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalString
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.common.exception.RequestException
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.interaction.updateEphemeralMessage
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.kordLogger
import dev.kord.rest.builder.RequestBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.create.MessageCreateBuilder
import dev.kord.rest.builder.message.embed
import dev.kord.rest.json.request.MultipartInteractionResponseCreateRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.time.Instant
import java.util.*

class MangaPollsExtension<T : Any> : Extension(), KordExKoinComponent {
    override val name = "manga_polls"

    private val config: UtilsExtensionConfiguration by inject()

    private val db: MangaDatabase by inject()

    private val pollsDb: PollsDatabase by inject()

    private val titleAutoCompletionIdNamePairs:
            (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit)?
            by inject(named("mangaIdAutoCompletion"))

    private val busyMutex = Mutex()

    private val scheduler: Scheduler<T> = get<Scheduler<T>>()

    inner class NewPollArguments : Arguments() {
        val title by string {
            name = "título"
            description = "Título de la votación."
            maxLength = 80
        }

        val pollOptionA by long {
            name = "opción_a"
            description = "Opción A."
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val pollOptionB by long {
            description = "Opción B."
            name = "opción_b"
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val pollOptionC by optionalLong {
            description = "Opción C."
            name = "opción_c"
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val pollOptionD by optionalLong {
            description = "Opción D."
            name = "opción_d"
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val description by optionalString {
            description = "Descripción de la votación."
            name = "descripción"
            maxLength = 80
        }

        val singleVote by optionalBoolean {
            name = "votoúnico"
            description = "¿Sólo puede votarse una opción?"
        }
    }

    override suspend fun setup() {
        publicSlashCommand {
            name = "votación"
            description = "Crea una votación."
            guild(config.guild)

            publicSubCommand(::NewPollArguments) {
                name = "mangas"
                description = "Crea una votación con entradas de manga."

                action {
                    respond {
                        val mangas = with (arguments) {
                            listOfNotNull(pollOptionA, pollOptionB, pollOptionC, pollOptionD).distinct().let {
                                db.getMangas(*it.toLongArray())
                            }
                        }

                        val pollOptions = mangas.map { manga -> PollOption(manga.title) }

                        @OptIn(EphemeralOrPublicView::class)
                        if (mangas.size < 2) {
                            respondWithError("No se encontraron suficientes entradas para crear una votación.")
                            return@respond
                        }

                        // if it's already busy let's not stitch the covers
                        // I don't want the RPi die out of an OOM error or a busy and slow swap
                        val imgBytes = if (busyMutex.tryLock()) try {
                            withContext(Dispatchers.IO) {
                                mangas.mapNotNull {
                                    it.imgURLSource ?: this@MangaPollsExtension.javaClass.getResource("no-cover.jpg")
                                        .also { url ->
                                            if (url === null)
                                                kordLogger.warn {
                                                    "Default cover image for manga entries could not be loaded."
                                                }
                                        }
                                }.takeIf { it.size > 1 }
                                    ?.let(::stitch)
                                    ?.asJpgByteArray()
                            }
                        } finally {
                            busyMutex.unlock()
                        } else null

                        val pollCandidate = Poll(
                            authorID = user.id.value.toLong(),
                            title = arguments.title,
                            description = arguments.description,
                            imgByteArray = imgBytes,
                            options = pollOptions,
                            singleVote = arguments.singleVote ?: false,
                        )

                        val existingPoll = pollsDb.createPoll(pollCandidate)

                        embeds = mutableListOf(existingPoll.toEmbed())

                        // voting choices:
                        actionRow {
                            existingPoll.options.forEachIndexed { i, opt ->
                                interactionButton(
                                    ButtonStyle.Primary,
                                    InteractionIdType.POLL_VOTE_OPTION.preppendTo("${opt.id}"),
                                ) {
                                    val icon = optionIcons.getOrNull(i)?.plus(" ") ?: ""

                                    label = "$icon${opt.description}".let {
                                        if (it.length > 80) it.take(77) + "..." else it
                                    }
                                }
                            }
                        }

                        // config:
                        actionRow {
                            interactionButton(
                                ButtonStyle.Secondary,
                                InteractionIdType.MANGA_POLL_ENTRIES_MENU.preppendTo(
                                    mangas.joinToString(separator=",", prefix="${mangas.first().id},") { "${it.id}" }
                                )
                            ) {
                                label = "Info. de manga"
                            }

                            interactionButton(
                                ButtonStyle.Danger,
                                InteractionIdType.POLL_FINISH_POLL_MENU.preppendTo("${existingPoll.id}"),
                            ) {
                                label = "Cerrar"
                            }
                        }
                    }
                }
            }
        }

        event<GuildButtonInteractionCreateEvent> {
            action {
                val componentId = event.interaction.componentId
                val metaString = InteractionIdType.removeFromString(componentId)

                val requestType = InteractionIdType.takeFromStringOrNull(componentId)
                    ?: return@action

                when (requestType) {
                    InteractionIdType.POLL_FINISH_POLL_MENU -> {
                        event.interaction.respondEphemeral {
                            content = "¿Cerrar con, o sin anuncios?"

                            val pollIdPlusMsgId = "$metaString#${event.interaction.message.id.value}"

                            actionRow {
                                interactionButton(
                                    ButtonStyle.Danger,
                                    InteractionIdType.POLL_FINISH_POLL_LOUDLY.preppendTo(pollIdPlusMsgId),
                                ) {
                                    label = "Con anuncios"
                                }

                                interactionButton(
                                    ButtonStyle.Danger,
                                    InteractionIdType.POLL_FINISH_POLL_QUIETLY.preppendTo(pollIdPlusMsgId),
                                ) {
                                    label = "Sin anuncios"
                                }
                            }
                        }
                    }

                    InteractionIdType.POLL_VOTE_OPTION -> {
                        val deferredResponse = kord.async { event.interaction.deferEphemeralResponse() }

                        val content: String = try {
                            val optionID = UUID.fromString(metaString)
                            val vote = pollsDb.vote(optionID, event.interaction.user.id.value)
                            val votedPoll = pollsDb.getPollByOptionID(optionID)!!
                            val pollEmbed = votedPoll.toEmbed()

                            val optionDescription = votedPoll.options.first { it.id == optionID }.description

                            event.interaction.message.edit {
                                embeds = embeds?.apply { add(pollEmbed) } ?: mutableListOf(pollEmbed)
                            }

                            "Tu voto por **$optionDescription** fue ${if (vote) "registrado" else "revocado"}"
                        } catch (e: Exception) {
                            when (e) {
                                is IllegalArgumentException,
                                is PollException -> {
                                    kordLogger.error(e) { "Exception during voting process. Probably a mistake coming from the front-end." }
                                    "Lo siento, hubo un error con la votación."
                                }
                                else -> {
                                    deferredResponse.await().respond {
                                        content = "Lo siento, ocurrió un error desconocido durante la votación."
                                    }
                                    throw e
                                }
                            }
                        }

                        deferredResponse.await().respond {
                            this.content = content
                        }
                    }

                    InteractionIdType.POLL_FINISH_POLL_QUIETLY, InteractionIdType.POLL_FINISH_POLL_LOUDLY -> {
                        val memberIsHelper = config.allowedRole in event.interaction.user.asMember().roleIds

                        if (!memberIsHelper) {
                            event.interaction.updateEphemeralMessage {
                                content = "Lo siento, no tienes autorización para cerrar esta votación"
                            }

                            return@action
                        }

                        val quietly = requestType == InteractionIdType.POLL_FINISH_POLL_QUIETLY

                        val (pollID, pollMessageID) = metaString.split('#').let { (a, b) ->
                            a.toLong() to Snowflake(b.toLong())
                        }

                        try {
                            pollsDb.finishPoll(pollID)
                        } catch (e: PollUnsuccessfulOperationException) {
                            event.interaction.updateEphemeralMessage {
                                components = mutableListOf()

                                content = "No pude cerrar la votación. ¿Puede que ya esté cerrada?"
                            }

                            return@action
                        }

                        val finishedPoll = pollsDb.getPoll(pollID)

                        if (finishedPoll !== null && !quietly) {
                            val rankedOptions = finishedPoll.options.groupBy { it.votes }
                                .toSortedMap(compareByDescending { it })
                                .values.flatMapIndexed { rank, pollOptions ->
                                    pollOptions.map { o -> rank to o }
                                }

                            val resultsText = rankedOptions.joinToString(
                                separator="\n",
                                prefix="«${finishedPoll.title}»: votación finalizada\n\n"
                            ) { (rank, option) ->
                                "${if (rank == 0) "\uD83C\uDFC6 " else ""}${option.description} (${option.votes})"
                            }

                            try {
                                scheduler.schedule(
                                    DiscordWebhookMessage(
                                        content=config.announcementRole.value.toMentionOn(kord.getGuild(config.guild)),
                                        embedText=resultsText,
                                        execInstant=Instant.now(),
                                    )
                                )
                            } catch (e: RequestException) {
                                kordLogger.error(e) { "There was a problem trying to resolve a guild for a scheduled message" }
                            }

                            scheduler.schedule(
                                XPost(
                                    text=resultsText,
                                    execInstant=Instant.now(),
                                )
                            )
                        }

                        kord.rest.channel.editMessage(event.interaction.channelId, pollMessageID) {
                            finishedPoll?.toEmbed()?.let { pollEmbed ->
                                embeds = embeds?.apply { add(pollEmbed) } ?: mutableListOf(pollEmbed)
                            }
                            components = mutableListOf()
                        }

                        event.interaction.updateEphemeralMessage {
                            components = mutableListOf()

                            content = if (requestType == InteractionIdType.POLL_FINISH_POLL_LOUDLY) {
                                "Cerrado y anunciado."
                            } else {
                                "Cerrado silenciosamente."
                            }
                        }
                    }

                    InteractionIdType.MANGA_POLL_ENTRIES_MENU -> {
                        event.interaction.respondEphemeral {
                            addMangaOptionsEmbed(metaString)
                        }
                    }

                    InteractionIdType.MANGA_POLL_ENTRY_REQUEST -> {
                        event.interaction.updateEphemeralMessage {
                            addMangaOptionsEmbed(metaString)
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> T.addMangaOptionsEmbed(mangaIds: String)
            where T : MessageCreateBuilder,
                  T : RequestBuilder<MultipartInteractionResponseCreateRequest> {
        val (selectedId, buttonMangaIds) = mangaIds.split(',').let { ids ->
            ids.first() to ids.drop(1)
        }

        val newIdsSuffix = buttonMangaIds.joinToString(",")

        val mangas = db.getMangas(*buttonMangaIds.map(String::toLong).toLongArray())
        val displayedManga = mangas.firstOrNull { it.id == selectedId.toLong() }

        if (displayedManga !== null) {
            embed {
                mangaView(displayedManga)
            }
        } else {
            content = "El manga seleccionado no está disponible."
        }

        actionRow {
            mangas.forEach { manga ->
                interactionButton(
                    ButtonStyle.Secondary,
                    InteractionIdType.MANGA_POLL_ENTRY_REQUEST.preppendTo("${manga.id},$newIdsSuffix")
                ) {
                    val title = manga.title
                    label = if (title.length > 80) title.take(77) + "..." else title
                }
            }
        }
    }
}