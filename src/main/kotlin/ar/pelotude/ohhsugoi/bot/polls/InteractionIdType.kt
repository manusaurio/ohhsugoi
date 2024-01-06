package ar.pelotude.ohhsugoi.bot.polls

enum class InteractionIdType(val prefix: String) {
    POLL_VOTE_OPTION("p-vo#"),
    POLL_FINISH_POLL_MENU("p-fpm#"),
    POLL_FINISH_POLL_QUIETLY("p-fpq#"),
    POLL_FINISH_POLL_LOUDLY("p-fpl#"),
    MANGA_POLL_ENTRIES_MENU("mp-em#"),
    MANGA_POLL_ENTRY_REQUEST("mp-er#"),
    ;

    init {
        InteractionIdType.entries.groupingBy { it.prefix }
            .eachCount().entries.find { it.value > 1 }
            ?.let {
                throw IllegalStateException("Prefix ${it.key} was assigned to multiple constants.")
            }
    }

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