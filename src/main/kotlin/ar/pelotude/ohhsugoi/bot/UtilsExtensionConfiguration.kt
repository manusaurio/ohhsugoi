package ar.pelotude.ohhsugoi.bot

import dev.kord.common.entity.Snowflake

class UtilsExtensionConfiguration(
        val allowedRole: Snowflake,
        val announcementRole: Snowflake,
        generalConfig: GeneralConfiguration,
) : GeneralConfiguration by generalConfig