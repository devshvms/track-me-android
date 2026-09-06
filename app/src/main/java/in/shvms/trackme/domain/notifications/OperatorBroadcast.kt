package `in`.shvms.trackme.domain.notifications

/**
 * SCOPE_1.8.7 §6.3 — a Class D operator broadcast, and the rules for believing one.
 *
 * This is the only content in the app that arrives from the network and goes straight to a
 * HIGH-importance notification channel. Everything else the app says it computed itself from local
 * facts. So the parser is deliberately strict and deliberately silent about *why* it refused: a
 * malformed broadcast is dropped, not repaired, not partially rendered, and not logged back to the
 * sender in a way that would help someone probe it.
 *
 * ### The closed tag vocabulary is the promotional ban
 *
 * §6.3 says "nothing promotional, ever". That is written down in three places and none of them is a
 * document: the admin UI offers only these three tags, the endpoint rejects anything else, and
 * [parse] refuses it here. A rule that lives only in prose is a rule that loses to a good idea on a
 * slow month — and the cost of losing that argument once is that "permission granted" stops being a
 * sufficient basis for delivering any of this.
 *
 * There is no `OTHER`. Adding a fourth tag should require changing three codebases, which is the
 * appropriate amount of friction.
 */
data class OperatorBroadcast(
    val id: String,
    val tag: BroadcastTag,
    val title: String,
    val body: String,
    val createdAtMillis: Long,
    /**
     * Only meaningful for [BroadcastTag.UPDATE]: the newest build the message is *true* for.
     *
     * This is the single filter in the design, and it is about correctness rather than targeting.
     * Telling somebody already running the fixed build to update is noise, and noise on this
     * channel is how people learn to swipe away the one message that mattered. The client decides,
     * not the server — so the filter has exactly one axis and cannot quietly become segmentation.
     */
    val appliesToVersionsAtOrBelow: Int? = null,
    val learnMoreUrl: String? = null,
) {
    /** Whether this message is true for a device running [versionCode]. Inclusive at the boundary. */
    fun appliesTo(versionCode: Int): Boolean {
        val ceiling = appliesToVersionsAtOrBelow ?: return true
        return versionCode <= ceiling
    }

    /**
     * @param lastSeenCreatedAtMillis the newest broadcast the user has already been shown, or null.
     */
    fun isUnread(lastSeenCreatedAtMillis: Long?): Boolean {
        val seen = lastSeenCreatedAtMillis ?: return true
        return createdAtMillis > seen
    }

    companion object {
        /** Longer than the shade shows is a title whose end nobody reads. */
        const val MAX_TITLE_LENGTH = 80

        /** Long enough for "what is wrong, what to do, when it will be fixed". */
        const val MAX_BODY_LENGTH = 480

        /**
         * Parses a broadcast from an untrusted map — an FCM data payload or a Firestore document.
         *
         * Returns null for anything that does not satisfy the contract. Refusing is always safe:
         * the same broadcast is also readable from Firestore on next foreground, so a dropped push
         * costs a delay, while a rendered malformed one costs the channel's credibility.
         */
        fun parse(raw: Map<String, Any?>): OperatorBroadcast? {
            val id = raw.string("id")?.takeIf { it.isNotBlank() } ?: return null
            val tag = BroadcastTag.parse(raw.string("tag")) ?: return null

            val title = raw.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (title.length > MAX_TITLE_LENGTH) return null

            val body = raw.string("body")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (body.length > MAX_BODY_LENGTH) return null

            val createdAt = raw.long("created_at_millis") ?: return null

            val ceiling = raw.int("applies_to_versions_at_or_below")
            // Version filtering has an operational meaning only for an update notice. Anywhere else
            // it is a segmentation lever with no honest use, so the shape forbids it rather than
            // relying on nobody reaching for it.
            if (ceiling != null && tag != BroadcastTag.UPDATE) return null

            val learnMore = raw.string("learn_more_url")?.trim()?.takeIf { it.isNotEmpty() }
            if (learnMore != null && !learnMore.startsWith("https://")) return null

            return OperatorBroadcast(
                id = id,
                tag = tag,
                title = title,
                body = body,
                createdAtMillis = createdAt,
                appliesToVersionsAtOrBelow = ceiling,
                learnMoreUrl = learnMore,
            )
        }

        private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

        /** FCM data payloads are all strings; Firestore hands back numbers. Both must work. */
        private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

        private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
}

/**
 * The three things an operator may say. There is no fourth, and adding one is meant to be
 * inconvenient — see [OperatorBroadcast].
 */
enum class BroadcastTag {
    /** A newer version fixes something the user is living with. */
    UPDATE,

    /** A service the app depends on is degraded or down. */
    MAINTENANCE,

    /** A defect in the running build that the user needs to know about now. */
    URGENT;

    companion object {
        /**
         * Exact match only. Case-insensitive parsing would accept "urgent" from a payload that
         * never came from our admin page, and being lenient about the one field that gates the
         * whole vocabulary defeats the point of having one.
         */
        fun parse(raw: String?): BroadcastTag? = entries.firstOrNull { it.name == raw }
    }
}
