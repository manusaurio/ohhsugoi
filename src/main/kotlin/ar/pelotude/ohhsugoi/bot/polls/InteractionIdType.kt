package ar.pelotude.ohhsugoi.bot.polls

enum class InteractionIdType(val prefix: String) {
    POLL_VOTE_OPTION("p-vo#"),
    POLL_FINISH_POLL_MENU("p-fpm#"),
    POLL_FINISH_POLL_QUIETLY("p-fpq#"),
    POLL_FINISH_POLL_LOUDLY("p-fpl#");

    fun preppendTo(str: String) = "$prefix$str"

    companion object {
        fun takeFromStringOrNull(str: String): InteractionIdType? {
            return entries.find {
                str.startsWith(it.prefix)
            }
        }

        fun removeFromString(str: String) = str.substringAfter('#')
    }
}