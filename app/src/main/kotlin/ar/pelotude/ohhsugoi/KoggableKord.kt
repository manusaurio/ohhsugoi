package ar.pelotude.ohhsugoi

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.kordLogger
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KoggableKord private constructor(val kord: Kord, config: KoggableConfig) {
    val kogs = config.kogs.toList()

    class KoggableConfig {
        val kogs = mutableListOf<KoggableInputCommand>()

        fun register(kog: KoggableInputCommand) = kogs.add(kog)
    }

    companion object {
        suspend operator fun invoke(token: String, setup: KoggableConfig.() -> Unit = {}): KoggableKord {
            // TODO: Unreadable mess, refactor.
            return KoggableKord(
                Kord(token),
                KoggableConfig().apply {
                    setup()
                }
            ).apply {
                val events = kord.events.buffer(Channel.UNLIMITED)
                    .filterIsInstance<GuildChatInputCommandInteractionCreateEvent>()

                kogs.forEach {
                    kord.createGuildChatInputCommand(
                        it.channel,
                        it.name,
                        it.description,
                        it.command
                    )

                    events.onEach { event ->
                        kord.launch {
                            runCatching {
                                kogs.forEach {
                                    it.handler(event)
                                }
                            }.onFailure {
                                kordLogger.catching(it)
                            }
                        }
                    }.launchIn(kord)
                }
            }
        }
    }
}

open class KoggableInputCommand private constructor(config: KogConfig) {
    val command = config.command
    val handler = config.handler
    val channel = config.channelId
    val name = config.name
    val description = config.description

    class KogConfig {
        // TODO: Validate
        var command: ChatInputCreateBuilder.() -> Unit = { }
        var channelId = Snowflake(0)
        var description: String = ""
        var name: String = ""
        var handler: suspend GuildChatInputCommandInteractionCreateEvent.() -> Unit = {}

        fun command(code: ChatInputCreateBuilder.() -> Unit) {
            command = code
        }

        fun handler(code: suspend GuildChatInputCommandInteractionCreateEvent.() -> Unit) {
            handler = code
        }
    }

    companion object {
        suspend operator fun invoke(config: suspend KogConfig.() -> Unit = {}): KoggableInputCommand {
            return KoggableInputCommand(
                KogConfig().apply { config() }
            )
        }
    }
}