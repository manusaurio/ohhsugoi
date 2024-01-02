package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.bot.GeneralConfiguration
import ar.pelotude.ohhsugoi.bot.GeneralConfigurationImpl
import ar.pelotude.ohhsugoi.bot.MangaExtensionConfiguration
import ar.pelotude.ohhsugoi.bot.UtilsExtensionConfiguration
import ar.pelotude.ohhsugoi.db.DatabaseConfiguration
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import ar.pelotude.ohhsugoi.db.PollsDatabase
import ar.pelotude.ohhsugoi.db.UsersDatabase
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledRegistry
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import ar.pelotude.ohhsugoi.db.scheduler.SchedulerConfiguration
import ar.pelotude.ohhsugoi.db.scheduler.platforms.DiscordWebhookMessage
import ar.pelotude.ohhsugoi.db.scheduler.platforms.XPost
import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.CommandContext
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import io.ktor.http.*
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.createDirectories

val botModule = module {
    single { MangaDatabaseSQLite() } binds arrayOf(
        MangaDatabase::class,
        ScheduledRegistry::class,
        UsersDatabase::class,
        PollsDatabase::class,
    )

    single<(suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit)?>(
            named("mangaIdAutoCompletion")
    ) {
        val db: MangaDatabase = get()

        return@single {
            val typedIn = focusedOption.value

            val results = db.searchMangaTitle(typedIn)

            suggestString {
                results.forEach { r -> choice("[#${r.first}] " + r.second, r.first.toString()) }
            }
        }
    }

    single<suspend (String, CommandContext) -> Long> {
        { value, context ->
            try {
                value.toLong()
            } catch (e: NumberFormatException) {
                throw DiscordRelayedException(context.translate("converters.zdt.error.missingzid"))
            }
        }
    }

    single<GeneralConfiguration> {
        GeneralConfigurationImpl(
            Snowflake(System.getenv("KORD_WEEB_SERVER")!!),
        )
    }

    single<MangaExtensionConfiguration> {
        MangaExtensionConfiguration(
            Snowflake(System.getenv("KORD_WEEB_ROLE")!!),
            mangaLinkMaxLength=256,
            mangaTitleMinLength=1,
            mangaTitleMaxLength=100,
            mangaDescMinLength=20,
            mangaDescMaxLength=512,
            get<GeneralConfiguration>(),
        )
    }

    single<DatabaseConfiguration> {
        DatabaseConfiguration(
            mangaCoversWidth=225,
            mangaCoversHeight=340,
            webpage=Url(System.getenv("WEBPAGE")),
            mangaImageDirectory=Path.of(System.getenv("MANGA_IMAGE_DIRECTORY")),
            mangaCoversUrlPath=System.getenv("MANGA_COVERS_URL_SUBDIRECTORY"),
            sqlitePath=Path.of(System.getenv("SQLITE_FILE_PATH")!!).apply { parent.createDirectories() }.toString(),
            pollImagesDirectory=Path.of(System.getenv("POLLS_IMAGE_DIRECTORY")),
            pollImagesUrlPath=System.getenv("POLL_IMAGES_URL_SUBDIRECTORY"),
        )
    }

    single<UtilsExtensionConfiguration> {
        UtilsExtensionConfiguration(
            allowedRole=Snowflake(System.getenv("DISCORD_HELPER_ROLE")!!),
            announcementRole=Snowflake(System.getenv("KORD_WEEB_ROLE")!!),
            generalConfig=get(),
        )
    }

    single<SchedulerConfiguration> {
        SchedulerConfiguration(
            webhook=System.getenv("DISCORD_WEBHOOK"),
            xConsumerKey=System.getenv("X_CONSUMER_KEY"),
            xConsumerKeySecret=System.getenv("X_CONSUMER_SECRET"),
            xAccessToken=System.getenv("X_ACCESS_TOKEN"),
            xAccessTokenSecret=System.getenv("X_ACCESS_TOKEN_SECRET"),
        )
    }

    single<Snowflake>(named("loggerChannel")) {
        Snowflake(System.getenv("DISCORD_LOGGER_CHANNEL").toLong())
    }
}