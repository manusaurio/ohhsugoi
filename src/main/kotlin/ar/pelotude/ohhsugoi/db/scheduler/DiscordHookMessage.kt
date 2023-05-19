package ar.pelotude.ohhsugoi.db.scheduler

import kotlinx.serialization.Serializable

@Serializable
data class DiscordHookMessageEmbed(
        val title: String? = null,
        val description: String,
)

@Serializable
data class DiscordHookMessage(
        val username: String = "Sheska",
        val content: String? = null,
        val embeds: Collection<DiscordHookMessageEmbed>? = null,
)