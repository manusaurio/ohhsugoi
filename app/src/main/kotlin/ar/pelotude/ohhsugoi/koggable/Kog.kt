package ar.pelotude.ohhsugoi.koggable

abstract class Kog {
    protected open val commandPool: MutableList<InputCommand> = mutableListOf()

    val commands: List<InputCommand>
        get() = commandPool.toList()

    open suspend fun setup() {

    }

    protected fun inputCommand(
        config: InputCommand.InputCommandConfig.() -> Unit = {}
    ): InputCommand {
        return InputCommand(config).apply(commandPool::add)
    }
}