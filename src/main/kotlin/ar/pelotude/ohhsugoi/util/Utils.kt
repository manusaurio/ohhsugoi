package ar.pelotude.ohhsugoi.util

import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

/** This isn't ideal but I'm not gonna add another dependency or write something fancy
 * for something meant to be used among trustworthy people */
fun String.isValidURL(): Boolean = try {
    URL(this).toURI()
    true
} catch(e: Exception) {
    when (e) {
        is java.net.MalformedURLException,
        is java.net.URISyntaxException -> false
        else -> throw e
    }
}

fun randomString(length: Int): String {
    val characters = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    return (0..length).map {
        characters.random()
    }.joinToString("")
}

fun uuidString() = UUID.randomUUID().toString()

fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/** But like in "This is a title", not "This Is A Title" */
fun String.makeTitle() = this.lowercase().capitalize()

inline fun <T> identity(): (T) -> T  = { it }

fun UUID.toByteArray(): ByteArray = ByteBuffer.wrap(ByteArray(16)).apply {
    putLong(this@toByteArray.mostSignificantBits)
    putLong(this@toByteArray.leastSignificantBits)
}.array()

fun ByteArray.toUUIDOrNull(): UUID? = ByteBuffer.wrap(this)!!.runCatching {
    UUID(getLong(), getLong())
}.getOrNull()

fun ByteArray.toUUID(): UUID = ByteBuffer.wrap(this)!!.run {
    UUID(getLong(), getLong())
}

fun ByteArray.calculateSHA256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}