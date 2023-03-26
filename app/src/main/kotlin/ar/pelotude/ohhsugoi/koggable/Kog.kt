package ar.pelotude.ohhsugoi.koggable

import dev.kord.common.entity.Snowflake

abstract class Kog(val defaultServer: Snowflake? = null) {
    protected open val commandPool: MutableList<InputCommand> = mutableListOf()

    val commands: List<InputCommand>
        get() = commandPool.toList()

    open suspend fun setup() {

    }

    protected fun inputCommand(
        config: InputCommand.InputCommandConfig.() -> Unit = {}
    ): InputCommand {
        return InputCommand {
            config()
            serverId =  serverId ?: defaultServer!!
        }.apply(commandPool::add)
    }
}