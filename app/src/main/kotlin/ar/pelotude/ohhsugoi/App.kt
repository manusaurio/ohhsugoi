package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.koggable.KoggableKord

suspend fun main() {
    KoggableKord(System.getenv("KORD_TOKEN")) {
        register(MangaKog())
    }.kord.login()
}
