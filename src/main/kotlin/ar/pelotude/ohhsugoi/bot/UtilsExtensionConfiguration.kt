package ar.pelotude.ohhsugoi.bot

import dev.kord.common.entity.Snowflake

class UtilsExtensionConfiguration(
        val schedulerRole: Snowflake,
        generalConfig: GeneralConfiguration,
) : GeneralConfiguration by generalConfig