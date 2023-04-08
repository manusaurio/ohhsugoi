package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.bot.MangaKogConfiguration
import ar.pelotude.ohhsugoi.db.DatabaseConfiguration
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import dev.kord.common.entity.Snowflake
import io.ktor.http.*
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.createDirectories

val botModule = module {
    single<MangaDatabase> { MangaDatabaseSQLite() }
    single<MangaKogConfiguration> {
        MangaKogConfiguration(
            Snowflake(System.getenv("KORD_WEEB_SERVER")!!),
            1,
            100,
            20,
            256,
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
}