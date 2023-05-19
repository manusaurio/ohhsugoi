package ar.pelotude.ohhsugoi.bot

import dev.kord.common.entity.Snowflake

class MangaExtensionConfiguration(
    val allowedRole: Snowflake,
    val mangaLinkMaxLength: Int,
    val mangaTitleMinLength: Int,
    val mangaTitleMaxLength: Int,
    val mangaDescMinLength: Int,
    val mangaDescMaxLength: Int,
    generalConfig: GeneralConfiguration
) : GeneralConfiguration by generalConfig
