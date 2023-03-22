package ar.pelotude.ohhsugoi.koggable

import dev.kord.core.Kord
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.core.kordLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KoggableKord private constructor(val kord: Kord, config: KoggableKogConfig) {
    val inputCommands = config.inputCommands.toList()
    val kogs = config.kogs.toList()

    class KoggableKogConfig {
        val inputCommands = mutableListOf<InputCommand>()
        val kogs = mutableSetOf<Kog>()
        fun register(cmd: InputCommand) = inputCommands.add(cmd)
        fun register(kog: Kog) = kogs.add(kog)
    }

    companion object {
        suspend operator fun invoke(token: String, setup: KoggableKogConfig.() -> Unit = {}): KoggableKord {
            // TODO: Unreadable mess, refactor.
            return KoggableKord(
                Kord(token),
                KoggableKogConfig().apply {
                    setup()
                }
            ).apply {
                val events = kord.events.buffer(Channel.UNLIMITED)
                    .filterIsInstance<GuildChatInputCommandInteractionCreateEvent>()

                kogs.forEach {
                    it.setup()
                }

                val allInputCommands: List<InputCommand> = inputCommands + kogs.flatMap { it.commands }

                allInputCommands.forEach { cmd ->
                    kord.createGuildChatInputCommand(
                        cmd.channel,
                        cmd.name,
                        cmd.description,
                        cmd.command
                    )
                }

                events.onEach { event ->
                    kord.launch {
                        runCatching {
                            allInputCommands.forEach { cmd ->
                                cmd.handler(event)
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

