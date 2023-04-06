package ar.pelotude.ohhsugoi

import dev.kord.common.entity.Snowflake

class MangaKogConfiguration(
    val guild: Snowflake,
    val mangaTitleMinLength: Int,
    val mangaTitleMaxLength: Int,
    val mangaDescMinLength: Int,
    val mangaDescMaxLength: Int,
)
