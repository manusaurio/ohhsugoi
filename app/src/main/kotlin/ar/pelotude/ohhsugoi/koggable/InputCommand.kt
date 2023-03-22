package ar.pelotude.ohhsugoi.koggable

import dev.kord.common.entity.Snowflake
import dev.kord.core.event.interaction.GuildChatInputCommandInteractionCreateEvent
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder

open class InputCommand protected constructor(config: InputCommandConfig) {
    val command = config.command
    val handler = config.handler
    val channel = config.channelId
    val name = config.name
    val description = config.description

    class InputCommandConfig {
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
        suspend operator fun invoke(config: suspend InputCommandConfig.() -> Unit = {}): InputCommand {
            return InputCommand(
                InputCommandConfig().apply { config() }
            )
        }
    }
}