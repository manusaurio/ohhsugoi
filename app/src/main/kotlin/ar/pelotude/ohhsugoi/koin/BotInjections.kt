package ar.pelotude.ohhsugoi.koin

import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import org.koin.dsl.module

val botModule = module {
    single<MangaDatabase> { MangaDatabaseSQLite() }
}