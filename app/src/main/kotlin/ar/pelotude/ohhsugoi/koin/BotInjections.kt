package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.MangaKogConfiguration
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import dev.kord.common.entity.Snowflake
import org.koin.dsl.module

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
}