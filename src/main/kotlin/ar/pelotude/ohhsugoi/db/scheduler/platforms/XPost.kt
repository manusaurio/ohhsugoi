package ar.pelotude.ohhsugoi.db.scheduler.platforms

import ar.pelotude.ohhsugoi.db.scheduler.SchedulablePost
import ar.pelotude.ohhsugoi.db.scheduler.SchedulablePostSerializer
import ar.pelotude.ohhsugoi.db.scheduler.SchedulerConfiguration
import ar.pelotude.ohhsugoi.db.scheduler.SerializableStorablePost
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.component.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SerializableStorablePost(XPost.IDENTIFIER, XPostSerializer::class)
class XPost(
    override val text: String,
    override val execInstant: Instant,
) : SchedulablePost, KordExKoinComponent {
    companion object {
        const val IDENTIFIER = "X_POST_WEBHOOK_MESSAGE"
    }

    override val identifier: String = IDENTIFIER

    private val config = get<SchedulerConfiguration>()

    private val endPoint = "https://api.twitter.com/2/tweets"

    override suspend fun process(client: HttpClient): HttpStatusCode {
        val nonce = ByteArray(32).apply {
            SecureRandom().nextBytes(this)
        }.encodeBase64()

        val oauthParams = listOf(
            "oauth_consumer_key" to config.xConsumerKey,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (Instant.now().toEpochMilli() / 1000).toString(),
            "oauth_token" to config.xAccessToken,
            "oauth_version" to "1.0",
        )

        // encode oauth key and params (we skip the params since the output is the same),
        // then we transform them into a `k=v` format separated by `"%26"` (encoded '&')
        // and attach the whole string at the end of the prefix that can be seen ahead
        val signatureBase = oauthParams
            .map { (k, v) -> k to URLEncoder.encode(v, StandardCharsets.UTF_8) }
            .joinToString(
            separator="%26",
            prefix="POST&${URLEncoder.encode(endPoint, StandardCharsets.UTF_8)}&"
        ) { (k, v) ->
            URLEncoder.encode("$k=$v", StandardCharsets.UTF_8)
        }

        val signingKey = "${config.xConsumerKeySecret}&${config.xAccessTokenSecret}"
        val mac = Mac.getInstance("HmacSHA1")
        val hmacKey = SecretKeySpec(signingKey.toByteArray(), "HmacSHA1")
        mac.init(hmacKey)
        val hmacData = mac.doFinal(signatureBase.toByteArray()).encodeBase64()

        return client.post(endPoint) {
            contentType(ContentType.Application.Json)

            header(
                "Authorization",
                (oauthParams + ("oauth_signature" to hmacData))
                    .sortedBy { it.first }
                    .joinToString(",", prefix="OAuth ") { (k, v) ->
                    "$k=\"${URLEncoder.encode(v, StandardCharsets.UTF_8)}\""
                }
            )

            setBody(
                Tweet(text)
            )
        }.status
    }
}

class XPostSerializer : SchedulablePostSerializer<XPost>() {
    override fun fromJson(json: JsonObject, execInstant: Instant): XPost {
        return XPost(
            text=json["text"]?.jsonPrimitive?.content!!,
            execInstant=execInstant,
        )
    }

    override fun toJson(post: XPost): JsonObject {
        return buildJsonObject {
            put("text", post.text)
        }
    }
}