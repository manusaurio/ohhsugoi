package ar.pelotude.ohhsugoi.bot

import dev.kord.common.entity.Snowflake

interface GeneralConfiguration {
    val guild: Snowflake
}

class GeneralConfigurationImpl(
    override val guild: Snowflake
) : GeneralConfiguration