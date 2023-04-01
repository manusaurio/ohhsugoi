package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.MangaDatabaseSQLite
import com.kotlindiscord.kord.extensions.ExtensibleBot

suspend fun main() {
    val database = MangaDatabaseSQLite()

    ExtensibleBot(System.getenv("KORD_TOKEN")) {
        extensions {
            add { MangaExtension(database) }
        }

        i18n {
            defaultLocale = java.util.Locale.forLanguageTag("es")
        }
    }.start()
}
