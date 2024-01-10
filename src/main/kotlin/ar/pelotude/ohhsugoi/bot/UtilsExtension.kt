package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.bot.converters.date
import ar.pelotude.ohhsugoi.bot.converters.postId
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.PollsDatabase
import ar.pelotude.ohhsugoi.db.UserData
import ar.pelotude.ohhsugoi.db.UsersDatabase
import ar.pelotude.ohhsugoi.db.scheduler.Failure
import ar.pelotude.ohhsugoi.db.scheduler.SchedulablePost
import ar.pelotude.ohhsugoi.db.scheduler.ScheduleEvent
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import ar.pelotude.ohhsugoi.db.scheduler.SchedulerEventHandler
import ar.pelotude.ohhsugoi.db.scheduler.Status
import ar.pelotude.ohhsugoi.db.scheduler.platforms.DiscordWebhookMessage
import ar.pelotude.ohhsugoi.db.scheduler.platforms.XPost
import ar.pelotude.ohhsugoi.util.image.asJpgByteArray
import ar.pelotude.ohhsugoi.util.image.stitch
import ar.pelotude.ohhsugoi.util.toMentionOn
import com.kotlindiscord.kord.extensions.checks.hasRole
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.long
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalLong
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalRole
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.exception.EntityNotFoundException
import dev.kord.rest.builder.message.embed
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

    inner class WrittenItems : ModalForm() {
        override var title: String = "Aleatorizar."

        val itemsText= paragraphText {
            label = "Un item por línea."
            maxLength = 900
            minLength = 3
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
                    }.sortedByDescending { it.execInstant }

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
                                title = "Encontrados ${posts.size} mensaje${if (posts.size > 1) 's' else ""}"

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

                    val msg = DiscordWebhookMessage(
                        content=arguments.mention?.id?.value?.toMentionOn(guild!!.asGuild()),
                        embedText=modal!!.text.value!!,
                        execInstant=dateInstant,
                    )

                    val scheduledMsg = scheduler.schedule(msg)

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
                    val post = scheduler.get<SchedulablePost>(arguments.postId)

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
                            title = "Mensaje [#${post.id}]"
                            description = post.text

                            field {
                                name = "Tipo"
                                value = when (post.post) {
                                    is DiscordWebhookMessage -> "Discord"
                                    is XPost -> "X"
                                    else -> "Desconocido"
                                }
                            }

                            if (post.post is DiscordWebhookMessage) {
                                post.post.content?.let {
                                    field {
                                        name = "Contenido/Mención"
                                        value = it
                                    }
                                }
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
                        embeds = mutableListOf(embed)
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