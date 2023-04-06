package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.bot.MangaExtension
import ar.pelotude.ohhsugoi.koin.botModule
import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.utils.getKoin
import java.util.Locale.forLanguageTag

suspend fun main() {
    val kordEx = ExtensibleBot(System.getenv("KORD_TOKEN")) {
        extensions {
            add(::MangaExtension)
        }

        i18n {
            defaultLocale = forLanguageTag("es")
        }
    }

    getKoin().loadModules(listOf(botModule))

    kordEx.start()
}