package ar.pelotude.ohhsugoi.db.scheduler.platforms

import ar.pelotude.ohhsugoi.db.scheduler.SchedulablePost
import ar.pelotude.ohhsugoi.db.scheduler.SchedulablePostSerializer
import ar.pelotude.ohhsugoi.db.scheduler.SchedulerConfiguration
import ar.pelotude.ohhsugoi.db.scheduler.SerializableStorablePost
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.component.get
import java.time.Instant

@SerializableStorablePost(DiscordWebhookMessage.IDENTIFIER, DiscordWebhookMessageSerializer::class)
class DiscordWebhookMessage(
    val content: String?,
    val embedText: String,
    override val execInstant: Instant,
) : SchedulablePost, KordExKoinComponent {
    companion object {
        const val IDENTIFIER = "DISCORD_WEBHOOK_MESSAGE"
    }

    private val webhookUrl = get<SchedulerConfiguration>().webhook

    override val identifier: String = IDENTIFIER

    override val text: String
        get() = embedText

    override suspend fun process(client: HttpClient): HttpStatusCode {
        return client.post(webhookUrl) {
            contentType(ContentType.Application.Json)

            setBody(
                DiscordHookMessage(
                    content=content,
                    embeds=listOf(
                        DiscordHookMessageEmbed(
                            description=embedText,
                        )
                    )
                )
            )
        }.status
    }
}

class DiscordWebhookMessageSerializer : SchedulablePostSerializer<DiscordWebhookMessage>() {
    override fun fromJson(json: JsonObject, execInstant: Instant): DiscordWebhookMessage {
        return DiscordWebhookMessage(
            content = json["content"]?.jsonPrimitive?.content,
            embedText = json["embedText"]?.jsonPrimitive?.content ?: throw Exception(""), // TODO
            execInstant = execInstant
        )
    }

    override fun toJson(post: DiscordWebhookMessage): JsonObject {
        return buildJsonObject {
            post.content?.let { put("content", it) }
            put("embedText", post.embedText)
        }
    }
}