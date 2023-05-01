package ar.pelotude.ohhsugoi.db.scheduler

import kotlinx.serialization.Serializable

@Serializable
data class DiscordHookMessage(val username: String="Sheska", val content: String)