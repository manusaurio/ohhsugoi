package ar.pelotude.ohhsugoi.bot

import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.converters.ConverterToOptional
import com.kotlindiscord.kord.extensions.commands.converters.OptionalConverter
import com.kotlindiscord.kord.extensions.commands.converters.SingleConverter
import com.kotlindiscord.kord.extensions.commands.converters.builders.ConverterBuilder
import com.kotlindiscord.kord.extensions.commands.converters.builders.OptionalConverterBuilder

class LongListConverter(
        minLength: Int? = null,
        maxLength: Int? = null,
        override val required: Boolean = false
) : GenericListConverter<Long>(String::toLongOrNull, minLength, maxLength, required) {
    override val errorTranslationKey: String = "converters.longListConverter.error.parse"
    override val signatureTypeString: String = "converters.longListConverter.signatureType"
}

open class LongListConverterBuilder : ConverterBuilder<List<Long>>() {
    var maxLength: Int? = null

    override fun build(arguments: Arguments): SingleConverter<List<Long>> {
        return arguments.arg(
                name,
                description,
                LongListConverter(maxLength=maxLength, required=true)
                        .withBuilder(this)
        )
    }
}

class OptionalLongListConverterBuilder : OptionalConverterBuilder<List<Long>>() {
    var maxLength: Int? = null

    @OptIn(ConverterToOptional::class)
    override fun build(arguments: Arguments): OptionalConverter<List<Long>> {
        return arguments.arg(
                name,
                description,
                LongListConverter(maxLength=maxLength, required=false)
                        .toOptional()
                        .withBuilder(this)
        )
    }
}

fun Arguments.longList(
        body: LongListConverterBuilder.() -> Unit,
): SingleConverter<List<Long>> {
    val converterBuilder = LongListConverterBuilder()
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}

fun Arguments.optionalLongList(
        body: OptionalLongListConverterBuilder.() -> Unit,
): OptionalConverter<List<Long>> {
    val converterBuilder = OptionalLongListConverterBuilder()
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}