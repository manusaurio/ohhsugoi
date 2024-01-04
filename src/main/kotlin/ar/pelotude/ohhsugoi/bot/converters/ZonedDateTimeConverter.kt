package ar.pelotude.ohhsugoi.bot.converters

import ar.pelotude.ohhsugoi.db.UsersDatabase
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
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.parser.StringParser
import dev.kord.core.entity.interaction.OptionValue
import dev.kord.core.entity.interaction.StringOptionValue
import dev.kord.rest.builder.interaction.OptionsBuilder
import dev.kord.rest.builder.interaction.StringChoiceBuilder
import org.koin.core.component.inject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class ZonedDateTimeConverter(override val required: Boolean) : SingleConverter<ZonedDateTime>(), KordExKoinComponent {
    override var validator: Validator<ZonedDateTime> = null
    override val signatureTypeString: String = "converters.zdt.signatureType"

    private val pattern: String = "d/M/yyyy H:mm"

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern)

    private val userDatabase: UsersDatabase by inject()

    override suspend fun parse(parser: StringParser?, context: CommandContext, named: String?): Nothing {
        throw UnsupportedOperationException("This converter only works with slash commands.")
    }

    override suspend fun parseOption(context: CommandContext, option: OptionValue<*>): Boolean {
        val optionValue = (option as? StringOptionValue)?.value ?: return false

        val zoneId: ZoneId = userDatabase.getUser(context.getUser()!!.id.value)?.zone
            ?: throw DiscordRelayedException(context.translate("converters.zdt.error.missingzid"))

        val zonedFormatter = formatter.withZone(zoneId)

        val nSlashes = optionValue.count { it == '/' }

        try {
            // hacky but easy to implement
            parsed = if (nSlashes == 2) {
                ZonedDateTime.parse(optionValue, zonedFormatter)
            } else {
                val now = ZonedDateTime.now(zoneId)

                val valueWithYear: String = if (nSlashes == 1) {
                    optionValue.split(' ').joinToString("/${now.year} ")
                } else {
                    now.format(dateFormatter) + ' ' + optionValue.split(' ').last()
                }

                val parsedWithYear = ZonedDateTime.parse(valueWithYear, zonedFormatter)

                if (parsedWithYear.isBefore(now)) parsedWithYear.withYear(now.year+1)
                else parsedWithYear
            }
        } catch (e: DateTimeParseException) {
            throw DiscordRelayedException(
                context.translate("converters.zdt.error.invalid", replacements = arrayOf(pattern))
            )
        }

        return true
    }

    override suspend fun toSlashOption(arg: Argument<*>): OptionsBuilder {
        return StringChoiceBuilder(arg.displayName, arg.description).apply {
            maxLength = 25
            this@apply.required = this@ZonedDateTimeConverter.required
        }
    }
}

class ZonedDateTimeConverterBuilder : ConverterBuilder<ZonedDateTime>() {
    override fun build(arguments: Arguments): SingleConverter<ZonedDateTime> {
        return arguments.arg(
            name,
            description,
            ZonedDateTimeConverter(required=true)
                .withBuilder(this)
        )
    }
}

class OptionalZonedDateTimeConverterBuilder : OptionalConverterBuilder<ZonedDateTime>() {
    @OptIn(ConverterToOptional::class)
    override fun build(arguments: Arguments): OptionalConverter<ZonedDateTime> {
        return arguments.arg(
            name,
            description,
            ZonedDateTimeConverter(required=false)
                .toOptional()
                .apply {
                    required
                }
                .withBuilder(this)
        )
    }
}

fun Arguments.date(body: ZonedDateTimeConverterBuilder.() -> Unit): SingleConverter<ZonedDateTime> {
    val converterBuilder = ZonedDateTimeConverterBuilder()
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}

fun Arguments.optionalDate(body: OptionalZonedDateTimeConverterBuilder.() -> Unit): OptionalConverter<ZonedDateTime> {
    val converterBuilder = OptionalZonedDateTimeConverterBuilder()
    converterBuilder.body()
    converterBuilder.validateArgument()

    return converterBuilder.build(this)
}