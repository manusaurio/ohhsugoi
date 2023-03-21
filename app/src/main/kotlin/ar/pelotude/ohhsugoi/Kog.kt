package ar.pelotude.ohhsugoi


abstract class Kog {
    abstract val commands: MutableList<InputCommand>
    open suspend fun setup() {

    }

    suspend inline fun inputCommand(
        noinline config: suspend InputCommand.InputCommandConfig.() -> Unit = {}
    ): InputCommand {
        return InputCommand(config).apply(commands::add)
    }
}