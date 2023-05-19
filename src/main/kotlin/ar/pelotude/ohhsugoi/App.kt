package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.bot.MangaExtension
import ar.pelotude.ohhsugoi.bot.UtilsExtension
import ar.pelotude.ohhsugoi.db.scheduler.Scheduler
import ar.pelotude.ohhsugoi.koin.botModule
import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.utils.getKoin
import java.util.Locale.forLanguageTag

suspend fun main() {
    val kordEx = ExtensibleBot(System.getenv("KORD_TOKEN")) {
        extensions {
            add(::MangaExtension)
            add { UtilsExtension<Long>() }
        }

        i18n {
            defaultLocale = forLanguageTag("es")
        }

        hooks {
            afterKoinSetup {
                getKoin().loadModules(listOf(botModule))
            }

            afterExtensionsAdded {
                getKoin().get<Scheduler<*>>().synchronize()
            }
        }
    }

    kordEx.start()
}