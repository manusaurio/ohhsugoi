package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import ar.pelotude.ohhsugoi.koggable.KoggableKord

suspend fun main() {
    val database = MangaDatabaseSQLite()

    KoggableKord(System.getenv("KORD_TOKEN")) {
        register(MangaKog(database))
    }.kord.login()
}
