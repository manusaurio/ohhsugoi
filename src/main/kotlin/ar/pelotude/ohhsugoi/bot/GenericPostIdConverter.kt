package ar.pelotude.ohhsugoi.bot

import com.kotlindiscord.kord.extensions.commands.Argument
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.CommandContext
import com.kotlindiscord.kord.extensions.commands.converters.SingleConverter
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.commands.converters.builders.ConverterBuilder
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.parser.StringParser
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.core.entity.interaction.StringOptionValue
import dev.kord.rest.builder.interaction.OptionsBuilder
import dev.kord.rest.builder.interaction.StringChoiceBuilder
import org.koin.core.component.get

class GenericPostIdConverter<T : Any>(
        override var validator: Validator<T> = null,
        valueToIdConverter: (suspend (String, CommandContext) -> T)? = null

) : SingleConverter<T>(), KordExKoinComponent {
    override val signatureTypeString = "converters.genericPostId.signatureType"

    private val valueToIdConverter: suspend (String, CommandContext) -> T = valueToIdConverter ?: get()

    override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
        throw UnsupportedOperationException("This converter only works with slash commands.")
    }

    override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val optionValue = (option as? StringOptionValue)?.value ?: return false

        parsed = valueToIdConverter(optionValue, context)

        return true
    }

    override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder {
        return StringChoiceBuilder(arg.displayName, arg.description).apply {
            maxLength = 100
            required = true
        }
    }
}

class GenericPostIdConverterBuilder<T : Any> : ConverterBuilder<T>() {
    override fun build(arguments: Arguments): SingleConverter<T> {
        return arguments.arg(name, description, GenericPostIdConverter<T>().withBuilder(this))
    }
}

fun <T : Any> Arguments.postId(body: GenericPostIdConverterBuilder<T>.() -> Unit): SingleConverter<T> {
    val converterBuilder = GenericPostIdConverterBuilder<T>()
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}