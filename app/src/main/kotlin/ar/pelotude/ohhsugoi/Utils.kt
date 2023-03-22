package ar.pelotude.ohhsugoi

import java.net.URL

/** This isn't ideal but I'm not gonna add another dependency or write something fancy
 * for something meant to be used among trustworthy people */
fun String.isValidURL() = try {
    URL(this).toURI()
    true
} catch(e: java.net.MalformedURLException) {
    false
}