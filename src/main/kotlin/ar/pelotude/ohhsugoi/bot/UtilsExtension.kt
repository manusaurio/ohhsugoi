package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.Poll
import ar.pelotude.ohhsugoi.db.PollException
import ar.pelotude.ohhsugoi.db.PollOption
import ar.pelotude.ohhsugoi.db.PollUnsuccessfulOperationException
import ar.pelotude.ohhsugoi.db.PollsDatabase
import ar.pelotude.ohhsugoi.db.UserData
import ar.pelotude.ohhsugoi.db.UsersDatabase
import ar.pelotude.ohhsugoi.db.scheduler.*
import ar.pelotude.ohhsugoi.util.image.asJpgByteArray
import ar.pelotude.ohhsugoi.util.image.stitch
import com.kotlindiscord.kord.extensions.checks.hasRole
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.long
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalLong
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalRole
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalString
import com.kotlindiscord.kord.extensions.commands.converters.impl.role
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondingPaginator
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.behavior.interaction.updateEphemeralMessage
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.exception.EntityNotFoundException
import dev.kord.core.kordLogger
import dev.kord.rest.builder.message.create.actionRow
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.request.RestRequestException
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

enum class InteractionIDType(val prefix: String) {
    POLL_VOTE_OPTION("p-vo#"),
    POLL_FINISH_POLL_MENU("p-fpm#"),
    POLL_FINISH_POLL_QUIETLY("p-fpq#"),
    POLL_FINISH_POLL_LOUDLY("p-fpl#");

    fun preppendTo(str: String) = "$prefix$str"

    companion object {
        fun takeFromStringOrNull(str: String): InteractionIDType? {
            // TODO: use enumEntries() (Kotlin >= 1.9)
            return values().find {
                str.startsWith(it.prefix)
            }
        }

        fun removeFromString(str: String) = str.substringAfter('#')
    }
}

class UtilsExtension<T : Any> : Extension(), KordExKoinComponent, SchedulerEventHandler<T> {
    override val name = "utils"

    private val db: MangaDatabase by inject()
    private val pollsDb: PollsDatabase by inject()

    private val scheduler: Scheduler<T> = get<Scheduler<T>>().apply { subscribe(this@UtilsExtension) }
    private val usersDatabase: UsersDatabase by inject()
    private val config: UtilsExtensionConfiguration by inject()
    private val titleAutoCompletionIdNamePairs:
            (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit)?
            by inject(named("mangaIdAutoCompletion"))

    private val busyMutex = Mutex()

    private lateinit var loggerChannel: TextChannel

    private val availableZoneIds = ZoneId.getAvailableZoneIds().toSortedSet()

    // it might  be wiser to convert ULongs to RoleBehaviour and use their `.mention`
    private fun ULong?.toMentionOn(guild: Guild): String? {
        return when (this) {
            null -> null
            guild.id.value -> "@everyone"
            else -> "<@&$this>"
        }
    }

    inner class StitchCoversArguments : Arguments() {
            val firstCover by long {
                name = "primera"
                description = "Primera portada"

                autoCompleteCallback = titleAutoCompletionIdNamePairs
            }

            val secondCover by long {
                name = "segunda"
                description = "Segunda portada"

                autoCompleteCallback = titleAutoCompletionIdNamePairs
            }

            val thirdCover by optionalLong {
                name = "tercera"
                description = "Tercera portada"

                autoCompleteCallback = titleAutoCompletionIdNamePairs
            }

            val fourthCover by optionalLong {
                name = "cuarta"
                description = "Cuarta portada"

                autoCompleteCallback = titleAutoCompletionIdNamePairs
            }
        }

    inner class ScheduleArguments : Arguments() {
        val date by date {
            name = "fecha"
            description = "Fecha de envío. Formato día/mes/año hora:minuto. Puedes omitir el año o la fecha por completo."
        }

        val mention by optionalRole {
            name = "mención"
            description = "Rol al que mencionar."
        }
    }

    inner class ZoneIdArguments : Arguments() {
        val zoneId by string {
            name = "zona"
            description = "Tu zona horaria."

            maxLength = availableZoneIds.maxBy(String::length).length

            autoComplete {
                val typedIn = focusedOption.value
                val matches = availableZoneIds.asSequence().filter { it.contains(typedIn, true) }.take(10)

                suggestString {
                    matches.forEach { id -> choice(id, id) }
                }
            }

            validate {
                failIfNot(value in availableZoneIds, "La zona no es válida.")
            }
        }
    }

    inner class SearchScheduledMessagesArguments : Arguments() {
        val statusFilter by stringChoice {
            name = "estado"
            description = "Estado por el cual filtrar."

            choices(statusFilters)
        }
    }

    inner class DiscordMessageModal : ModalForm() {
        override var title: String = "Mensaje programado de Discord."

        val text= paragraphText {
            label = "Mensaje de Discord."
            maxLength = 1900
            minLength = 1
        }
    }

    open inner class ScheduledPostArguments : Arguments() {
        val postId by postId<T> {
            name = "id"
            description = "Id de la publicación."
        }
    }

    inner class EditDateArguments : ScheduledPostArguments() {
        val date by date {
            name = "fecha"
            description = "Nueva fecha."
        }
    }

    inner class EditMentionRoleArguments : ScheduledPostArguments() {
        val mention by role {
            name = "mención"
            description = "Rol al que mencionar."
        }
    }

    inner class WrittenItems : ModalForm() {
        override var title: String = "Aleatorizar."

        val itemsText= paragraphText {
            label = "Un item por línea."
            maxLength = 900
            minLength = 3
        }
    }

    inner class NewPollArguments : Arguments () {
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

    private val EVERYTHING = "EVERYTHING"

    private val statusFilters = mapOf(
        "pendiente" to Status.PENDING.name,
        "fallido" to Status.FAILED.name,
        "mandado" to Status.SENT.name,
        "cancelado" to Status.CANCELLED.name,
        "todo" to EVERYTHING
    )

    private fun statusFilterMap(name: String): Status? {
        return when (name) {
            EVERYTHING -> null
            else -> Status.valueOf(name)
        }
    }

    override suspend fun setup() {
        loggerChannel = kord.getChannelOf<TextChannel>(get(named("loggerChannel")))
            ?: throw EntityNotFoundException("Logger channel could not be found.")

        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")

        ephemeralSlashCommand {
            name = "mensajes"
            description = "Administra mensajes automatizados."
            guild(config.guild)

            check {
                hasRole(config.allowedRole)
            }

            publicSubCommand(::SearchScheduledMessagesArguments) {
                name = "buscar"
                description = "Lista los mensajes programados"

                action {
                    val posts = statusFilterMap(arguments.statusFilter).let { filter ->
                        scheduler.getPosts(filter)
                    }.sortedByDescending(ScheduledPostMetadataImpl<*>::execInstant)

                    if (posts.isEmpty()) {
                        @OptIn(EphemeralOrPublicView::class)
                        respondWithInfo("No se encontraron mensajes programados.")
                        return@action
                    }

                    respondingPaginator {
                        timeoutSeconds = 60

                        posts.chunked(5).forEach { postsChunk ->
                            owner = user

                            page {
                                title = "Encontrados ${posts.size} mensaje${if (posts.size > 1) 's' else ""} de Discord"

                                postsChunk.forEach { post ->
                                    val status = when (post.status) {
                                        Status.SENT -> "Enviado"
                                        Status.PENDING -> "Pendiente"
                                        Status.CANCELLED -> "Cancelado"
                                        Status.FAILED -> "Fallido"
                                    }

                                    field {
                                        name = "[#${post.id}] <t:${post.execInstant.epochSecond}:t> <t:${post.execInstant.epochSecond}:d> [$status]"
                                        // TODO: avoid printing multiple newlines
                                        value = (if (post.text.length >= 50) post.text.slice(0 until 47) + "..." else post.text)
                                        inline = false
                                    }
                                }
                                color = colors.success
                            }
                        }
                    }.send()
                }
            }

            publicSubCommand(::ScheduleArguments, ::DiscordMessageModal) {
                name = "crear"
                description = "Programa un mensaje para mandar a Discord a cierta hora."

                action { modal ->
                    val dateInstant = arguments.date.toInstant()
                    val scheduledMsg = scheduler.schedule(modal!!.text.value!!, dateInstant, arguments.mention?.id?.value)

                    @OptIn(EphemeralOrPublicView::class)
                    respondWithSuccess("""Mensaje de Discord [#${scheduledMsg.id}] programado exitosamente
                        |
                        |Momento de envío: <t:${dateInstant.epochSecond}:R> (<t:${dateInstant.epochSecond}:F>)
                    """.trimMargin(), public=true)
                }
            }

            publicSubCommand(::ScheduledPostArguments) {
                name = "ver"
                description = "Muestra detalles de un mensaje programado de Discord"

                action {
                    val post = scheduler.get(arguments.postId)

                    @OptIn(EphemeralOrPublicView::class)
                    if (post == null) respondWithError("No existe la publicación `${arguments.postId}`")
                    else respond {
                        val status = when (post.status) {
                            Status.SENT -> "Enviado"
                            Status.PENDING -> "Pendiente"
                            Status.CANCELLED -> "Cancelado"
                            Status.FAILED -> "Fallido"
                        }

                        embed {
                            title = "Mensaje de Discord [#${post.id}]"
                            description = post.text

                            field {
                                name = "Mención"
                                value = post.mentionId.toMentionOn(guild!!.asGuild()) ?: "Nadie"
                            }

                            field {
                                name = "Fecha de envío"
                                value = "<t:${post.execInstant.epochSecond}:F>"
                            }

                            field {
                                name = "Estado"
                                value = status
                            }
                        }
                    }
                }
            }

            publicSubCommand(::ScheduledPostArguments) {
                name = "cancelar"
                description = "Cancela un mensaje programado de Discord"

                @OptIn(EphemeralOrPublicView::class)
                action {
                    if (scheduler.cancel(arguments.postId)) {
                        respondWithSuccess("Cancelado")
                    } else {
                        respondWithError("No se pudo cancelar ninguna publicación con esa id." +
                                    " Verifica que existe y que su estado esté pendiente de envío.")
                    }
                }
            }

            publicSubCommand(::ScheduledPostArguments, ::DiscordMessageModal) {
                name = "editartexto"
                description = "Edita el texto de un mensaje programado de Discord"

                action {modal ->
                    val submittedEdition = modal!!.text.value!!

                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(arguments.postId, submittedEdition).let { success ->
                        if (success) respondWithSuccess("Editado el texto a:\n\n${submittedEdition}", public=true)
                        else {
                            val userGotDm = try {
                                user.getDmChannel().createMessage {
                                    embed {
                                        title = "Texto de edición fallida para #${arguments.postId}."
                                        description = submittedEdition
                                        color = colors.error
                                    }
                                }
                                true
                            } catch (e: RestRequestException) {
                                false
                            }

                            val description = "No se pudo cambiar el texto del mensaje #${arguments.postId}" + if (!userGotDm) {
                                ". Cópialo para evitar perderlo:\n\n${submittedEdition}"
                            } else {
                                ". Tu texto te fue envíado por mensaje directo."
                            }

                            respondWithError(description)
                        }
                    }
                }
            }

            publicSubCommand(::EditDateArguments) {
                name = "editarfecha"
                description = "Edita la fecha de un mensaje programado de Discord"

                action {
                    val newExecInstant = arguments.date.toInstant()

                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(arguments.postId, newExecInstant=newExecInstant).let { success ->
                        if (success) {
                            respondWithSuccess("Fecha de publicación del mensaje #${arguments.postId} cambiada a <t:${newExecInstant.epochSecond}:F>.")
                        } else {
                            respondWithError("No se pudo cambiar la fecha de publicación del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }

            publicSubCommand(::EditMentionRoleArguments) {
                name = "editarmención"
                description = "Edita el rol a mencionar en un mensaje."

                action {
                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(k=arguments.postId, newMentionRole=arguments.mention.id.value).let { success ->
                        if (success) {
                            respondWithSuccess("Rol a mencionar de #${arguments.postId} cambiado a ${arguments.mention.mention}.")
                        } else {
                            respondWithError("No se pudo cambiar el rol del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }

            publicSubCommand(::ScheduledPostArguments) {
                name = "removermención"
                description = "Remueve el rol a mencionar en un mensaje."

                action {
                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.removeMention(arguments.postId).let { success ->
                        if (success) {
                            respondWithSuccess("Rol a mencionar de la publicación #${arguments.postId} removido.")
                        } else {
                            respondWithError("No se pudo remover el rol del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }
        }

        ephemeralSlashCommand {
            name = "zonahoraria"
            description = "Cambia o chequea tu zona horaria."
            guild(config.guild)

            ephemeralSubCommand(::ZoneIdArguments) {
                name = "cambiar"
                description = "Cambia tu zona horaria."

                action {
                    val zoneId = ZoneId.of(arguments.zoneId)
                    val userData = UserData(user.id.value, zoneId)
                    usersDatabase.setUser(userData)

                    @OptIn(EphemeralOrPublicView::class)
                    respondWithSuccess("""Zona cambiada a `${arguments.zoneId}`.
                    |
                    |Tu horario actual debería ser `${ZonedDateTime.now(zoneId).format(hourFormatter)}`
                    |""".trimMargin(), public=true)
                }
            }

            ephemeralSubCommand {
                name = "ver"
                description = "Mira cuál es tu zona horaria."

                action {
                    val zoneIdStr: String? = usersDatabase.getUser(user.id.value)?.zone?.toString()

                    if (zoneIdStr != null) {
                        val zoneId = ZoneId.of(zoneIdStr)

                        @OptIn(EphemeralOrPublicView::class)
                        respondWithInfo(
                            """Tu zona es `$zoneIdStr`
                            |
                            |Tu horario actual debería ser `${ZonedDateTime.now(zoneId).format(hourFormatter)}`
                            |""".trimMargin()
                        )
                    } else @OptIn(EphemeralOrPublicView::class) {
                        respondWithInfo("No tengo registrada tu zona horaria.")
                    }
                }
            }
        }

        publicSlashCommand(::WrittenItems) {
            name = "aleatorizar"
            description = "Devuelve un elemento al azar de una lista proveída."
            guild(config.guild)

            action { modal ->
                val lines = modal?.itemsText?.value?.split('\n')?.filter(String::isNotBlank)

                if (!lines.isNullOrEmpty()) {
                    val embed = quickEmbed(
                            "Aleatorización",
                            "Estos elementos fueron mezclados y uno fue tomado al azar",
                            colors.info,
                    ).apply {
                        field {
                            name = "Elementos mezclados"
                            inline = false
                            value = lines.shuffled().joinToString("\n")
                        }

                        field {
                            name = "Elemento al azar"
                            inline = false
                            value = lines.random()
                        }
                    }

                    respond {
                        embeds.add(embed)
                    }

                } else @OptIn(EphemeralOrPublicView::class) {
                    respondWithError("Hubo un error con la lista proveída. ¿Había suficientes opciones?")
                }
            }
        }

        publicSlashCommand(::StitchCoversArguments) {
            name = "combinar"
            description = "Combina hasta 4 portadas en una imagen horizontal."
            guild(config.guild)

            check {
                hasRole(config.allowedRole)
            }

            action {
                respond {
                    if (busyMutex.tryLock()) try {
                        val ids = with (arguments) { listOfNotNull(firstCover, secondCover, thirdCover, fourthCover).distinct() }

                        // it's just 4 elements...
                        val mangas = db.getMangas(*ids.toLongArray()).let { mangas ->
                            ids.mapNotNull { id ->
                                mangas.firstNotNullOfOrNull {
                                    m -> m.takeIf { id == m.id }?.imgURLSource
                                }
                            }
                        }

                        if (mangas.size < 2) {
                            content = "Lo siento, no he encontrado suficientes portadas para unir en una sola imagen."
                        } else {
                            val bytes = withContext(Dispatchers.IO) {
                                stitch(mangas).asJpgByteArray()
                            }

                            addFile(
                                    "imagen.jpg",
                                    ChannelProvider(bytes.size.toLong()) { ByteReadChannel(bytes) },
                            )
                        }
                    } finally {
                        busyMutex.unlock()
                    } else {
                        content = "Lo siento, estoy ocupada procesando algo más."
                    }
                }
            }
        }

        publicSlashCommand(::NewPollArguments) {
            name = "votación"
            description = "Crea una votación"
            guild(config.guild)

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
                        return@action
                    }

                    // if it's already busy let's not stitch the covers
                    // I don't want the RPi die out of an OOM error or a busy and slow swap
                    val imgBytes = if (busyMutex.tryLock()) try {
                        withContext(Dispatchers.IO) {
                            mangas.mapNotNull {
                                it.imgURLSource ?: this@UtilsExtension.javaClass.getResource("no-cover.jpg")
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

                    embeds.add(existingPoll.toEmbed())

                    // voting choices:
                    actionRow {
                        existingPoll.options.forEachIndexed { i, opt ->
                            interactionButton(
                                ButtonStyle.Primary,
                                InteractionIDType.POLL_VOTE_OPTION.preppendTo("${opt.id}"),
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
                            ButtonStyle.Danger,
                            InteractionIDType.POLL_FINISH_POLL_MENU.preppendTo("${existingPoll.id}"),
                        ) {
                            label = "Cerrar"
                        }
                    }
                }
            }
        }

        event<GuildButtonInteractionCreateEvent> {
            action {
                val componentId = event.interaction.componentId
                val id = InteractionIDType.removeFromString(componentId)

                val requestType = InteractionIDType.takeFromStringOrNull(componentId)
                    ?: return@action

                when (requestType) {
                    InteractionIDType.POLL_FINISH_POLL_MENU -> {
                        event.interaction.respondEphemeral {
                            content = "¿Cerrar con, o sin anuncios?"

                            val pollIdPlusMsgId = "$id#${event.interaction.message.id.value}"

                            actionRow {
                                interactionButton(
                                    ButtonStyle.Danger,
                                    InteractionIDType.POLL_FINISH_POLL_LOUDLY.preppendTo(pollIdPlusMsgId),
                                ) {
                                    label = "Con anuncios"
                                }

                                interactionButton(
                                    ButtonStyle.Danger,
                                    InteractionIDType.POLL_FINISH_POLL_QUIETLY.preppendTo(pollIdPlusMsgId),
                                ) {
                                    label = "Sin anuncios"
                                }
                            }
                        }
                    }

                    InteractionIDType.POLL_VOTE_OPTION -> {
                        val deferredResponse = kord.async { event.interaction.deferEphemeralResponse() }

                        val content: String = try {
                            val optionID = UUID.fromString(id)
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

                    InteractionIDType.POLL_FINISH_POLL_QUIETLY, InteractionIDType.POLL_FINISH_POLL_LOUDLY -> {
                        val memberIsHelper = config.allowedRole in event.interaction.user.asMember().roleIds

                        if (!memberIsHelper) {
                            event.interaction.updateEphemeralMessage {
                                content = "Lo siento, no tienes autorización para cerrar esta votación"
                            }

                            return@action
                        }

                        val quietly = requestType == InteractionIDType.POLL_FINISH_POLL_QUIETLY

                        val (pollID, pollMessageID) = id.split('#').let { (a, b) ->
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
                            val groupedOptions = finishedPoll.options.groupBy { it.votes }.maxByOrNull { (k, _) -> k }!!.value
                            val totalVotes = finishedPoll.options.sumOf { it.votes }

                            val totalSharedVotes = groupedOptions.sumOf { it.votes }
                            val optsProportions = totalSharedVotes.toDouble() / totalVotes
                            val optPercentage = (optsProportions * 100).takeIf { !it.isNaN() } ?: 0.0

                            val resultsText = groupedOptions.joinToString(
                                prefix=(if (groupedOptions.size > 1) "Ganadores" else "Ganador")
                                        + " con $totalSharedVotes voto${if (totalSharedVotes > 1) "s" else ""} ($optPercentage%):\n",
                                separator="\n",
                            ) {
                                it.description
                            }

                            scheduler.schedule(
                                execInstant=Instant.now(),
                                mentionId=config.announcementRole.value,
                                text="**«${finishedPoll.title}»: votación finalizada**"
                                        + "\n\n"
                                        + resultsText,
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

                            content = if (requestType == InteractionIDType.POLL_FINISH_POLL_LOUDLY) {
                                "Cerrado y anunciado."
                            } else {
                                "Cerrado silenciosamente."
                            }
                        }
                    }
                }
            }
        }
    }

    override fun handle(e: ScheduleEvent<T>) {
        kord.launch {
            if (e is Failure<T>) {
                loggerChannel.createMessage {
                    content="Hubo un problema mandando un mensaje: `<id=${e.post.id}, description=${e.reason}>`"
                }
            }
        }
    }
}
