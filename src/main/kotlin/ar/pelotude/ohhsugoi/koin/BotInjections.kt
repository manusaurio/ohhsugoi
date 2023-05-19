package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.bot.GeneralConfiguration
import ar.pelotude.ohhsugoi.bot.GeneralConfigurationImpl
import ar.pelotude.ohhsugoi.bot.MangaExtensionConfiguration
import ar.pelotude.ohhsugoi.bot.UtilsExtensionConfiguration
import ar.pelotude.ohhsugoi.db.DatabaseConfiguration
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import ar.pelotude.ohhsugoi.db.UsersDatabase
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledRegistry
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import ar.pelotude.ohhsugoi.db.scheduler.SchedulerConfiguration
import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.commands.CommandContext
import dev.kord.common.entity.Snowflake
import io.ktor.http.*
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.createDirectories

val botModule = module {
    single { MangaDatabaseSQLite() } binds arrayOf(MangaDatabase::class, ScheduledRegistry::class, UsersDatabase::class)

    single<Scheduler<Long>> { Scheduler(get()) }

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
            Url(System.getenv("WEBPAGE")),
            Path.of(System.getenv("MANGA_IMAGE_DIRECTORY")),
            System.getenv("MANGA_COVERS_URL_SUBDIRECTORY"),
            Path.of(System.getenv("SQLITE_FILE_PATH")!!).apply { parent.createDirectories() }.toString(),
        )
    }

    single<UtilsExtensionConfiguration> {
        UtilsExtensionConfiguration(
                Snowflake(System.getenv("DISCORD_HELPER_ROLE")!!),
                get(),
        )
    }

    single<SchedulerConfiguration> {
        SchedulerConfiguration(
                System.getenv("KORD_WEEB_SERVER")!!.toULong(),
                System.getenv("DISCORD_WEBHOOK"),
        )
    }

    single<Snowflake>(named("loggerChannel")) {
        Snowflake(System.getenv("DISCORD_LOGGER_CHANNEL").toLong())
    }
}