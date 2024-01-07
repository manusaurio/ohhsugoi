package ar.pelotude.ohhsugoi.bot.polls

enum class InteractionIdType(val prefix: String) {
    /** Id that should be followed by the option id, which is expected to be a UUID */
    POLL_VOTE_OPTION("p-vo#"),

    /** Id that should be followed by the poll id, which is expected to be a [Long] */
    POLL_FINISH_POLL_MENU("p-fpm#"),

    /** Id that should be followed by the poll id then a `#` and then the message id:
     * `\[POST_ID#MESSAGE_ID\]`
     *
     * The post id is expected to be a [Long], and the message id a [dev.kord.common.entity.Snowflake]*/
    POLL_FINISH_POLL_QUIETLY("p-fpq#"),

    /** Id that should be followed by the poll id then a `#` and then the message id:
     * `\[POST_ID#MESSAGE_ID\]`
     *
     * The post id is expected to be a [Long], and the message id a [dev.kord.common.entity.Snowflake] */
    POLL_FINISH_POLL_LOUDLY("p-fpl#"),

    /** Id that should be followed by comma-separated manga ids, expected to be [Long].
     *
     * The first value indicates the entry to be displayed. E.g.: `"3,3,6,9"` */
    MANGA_POLL_ENTRIES_MENU("mp-em#"),

    /** Id that should be followed by comma-separated manga ids, expected to be [Long].
     *
     * The first value indicates the entry to be displayed. E.g.: `"3,3,6,9"` */
    MANGA_POLL_ENTRY_REQUEST("mp-er#"),
    ;

    fun preppendTo(str: String) = "$prefix$str"

    companion object {
        init {
            InteractionIdType.entries.groupingBy { it.prefix }
                .eachCount().entries.find { it.value > 1 }
                ?.let {
                    throw IllegalStateException("Prefix ${it.key} was assigned to multiple constants.")
                }
        }

        fun takeFromStringOrNull(str: String): InteractionIdType? {
            return entries.find {
                str.startsWith(it.prefix)
            }
        }

        fun removeFromString(str: String) = str.substringAfter('#')
    }
}
