package ar.pelotude.ohhsugoi.bot

import dev.kord.common.entity.Snowflake

class MangaKogConfiguration(
    val guild: Snowflake,
    val allowedRole: Snowflake,
    val mangaLinkMaxLength: Int,
    val mangaTitleMinLength: Int,
    val mangaTitleMaxLength: Int,
    val mangaDescMinLength: Int,
    val mangaDescMaxLength: Int,
)
