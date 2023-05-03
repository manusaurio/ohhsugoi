package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.bot.MangaExtensionConfiguration
import ar.pelotude.ohhsugoi.db.DatabaseConfiguration
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import ar.pelotude.ohhsugoi.db.scheduler.ScheduledRegistry
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import dev.kord.common.entity.Snowflake
import io.ktor.http.*
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.createDirectories

val botModule = module {
    single { MangaDatabaseSQLite() }.binds(arrayOf(MangaDatabase::class, ScheduledRegistry::class))

    single<Scheduler<Long>> { Scheduler(get()) }

    single<MangaExtensionConfiguration> {
        MangaExtensionConfiguration(
            Snowflake(System.getenv("KORD_WEEB_SERVER")!!),
            Snowflake(System.getenv("KORD_WEEB_ROLE")!!),
            256,
            1,
            100,
            20,
            512,
        )
    }

    single<DatabaseConfiguration> {
        DatabaseConfiguration(
            225,
            340,
            Url(System.getenv("WEBPAGE")),
            Path.of(System.getenv("MANGA_IMAGE_DIRECTORY")),
            System.getenv("MANGA_COVERS_URL_SUBDIRECTORY"),
            Path.of(System.getenv("SQLITE_FILE_PATH")!!).apply { parent.createDirectories() }.toString(),
        )
    }

    single<String>(named("hookToken")) {
        System.getenv("DISCORD_WEBHOOK")
    }
}