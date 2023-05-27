package ar.pelotude.ohhsugoi.bot

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.Argument
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.CommandContext
import com.kotlindiscord.kord.extensions.commands.converters.ConverterToOptional
import com.kotlindiscord.kord.extensions.commands.converters.OptionalConverter
import com.kotlindiscord.kord.extensions.commands.converters.SingleConverter
import com.kotlindiscord.kord.extensions.commands.converters.Validator
import com.kotlindiscord.kord.extensions.commands.converters.builders.ConverterBuilder
import com.kotlindiscord.kord.extensions.commands.converters.builders.OptionalConverterBuilder
import com.kotlindiscord.kord.extensions.parser.StringParser
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.core.entity.interaction.StringOptionValue
import dev.kord.rest.builder.interaction.OptionsBuilder
import dev.kord.rest.builder.interaction.StringChoiceBuilder

open class GenericListConverter<T: Any> (
        val convertOrNull: (String) -> T?,
        val minLength: Int? = null,
        val maxLength: Int? = null,
        override var validator: Validator<List<T>> = null,
        override val required: Boolean = false
): SingleConverter<List<T>>() {
    open val errorTranslationKey: String = "converters.unspecifiedListConverter.error.parse"
    override val signatureTypeString: String = "converters.unspecifiedListConverter.signatureType"

    override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Boolean {
        throw UnsupportedOperationException("This converter only works with slash commands.")
    }

    override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val optionValue = (option as? StringOptionValue)?.value ?: return false

        val converted = optionValue.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { str ->
                    convertOrNull(str)
                            ?: throw DiscordRelayedException(
                                    context.translate(errorTranslationKey, replacements = arrayOf(str))
                            )
                }

        parsed = converted

        return true
    }

    override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder {
        return StringChoiceBuilder(arg.displayName, arg.description).apply {
            minLength = this@GenericListConverter.minLength
            maxLength = this@GenericListConverter.maxLength
            required = this@GenericListConverter.required
        }
    }
}

open class GenericListConverterBuilder<T: Any>(private val convertOrNull: (String) -> T?) : ConverterBuilder<List<T>>() {
    var minLength: Int? = null
    var maxLength: Int? = null

    override fun build(arguments: Arguments): SingleConverter<List<T>> {
        return arguments.arg(
                name,
                description,
                GenericListConverter(
                        convertOrNull,
                        minLength=minLength,
                        maxLength=maxLength,
                        validator=validator,
                        required=true
                )
                        .withBuilder(this)
        )
    }
}

class OptionalGenericListConverterBuilder<T: Any>(private val convertOrNull: (String) -> T?) : OptionalConverterBuilder<List<T>>() {
    var minLength: Int? = null
    var maxLength: Int? = null

    @OptIn(ConverterToOptional::class)
    override fun build(arguments: Arguments): OptionalConverter<List<T>> {
        return arguments.arg(
                name,
                description,
                GenericListConverter(
                        convertOrNull,
                        minLength=minLength,
                        maxLength=maxLength,
                        required=false
                )
                        .toOptional(outputError=true, nestedValidator=validator)
                        .withBuilder(this)
        )
    }
}

fun <T: Any> Arguments.list(
        convertOrNullFunction: (String) -> T?,
        body: GenericListConverterBuilder<T>.() -> Unit,
): SingleConverter<List<T>> {
    val converterBuilder = GenericListConverterBuilder(convertOrNullFunction)
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}

fun <T: Any> Arguments.optionalList(
        convertOrNullFunction: (String) -> T?,
        body: OptionalGenericListConverterBuilder<T>.() -> Unit,
): OptionalConverter<List<T>> {
    val converterBuilder = OptionalGenericListConverterBuilder(convertOrNullFunction)
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}