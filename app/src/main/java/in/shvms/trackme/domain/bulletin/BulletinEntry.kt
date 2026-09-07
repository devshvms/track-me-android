package `in`.shvms.trackme.domain.bulletin

/**
 * SCOPE_1.8.7 §6.1.7 scenario 32 — the in-app bulletin, and the release's backbone.
 *
 * ### Why this is the load-bearing piece
 *
 * §6.0 caps proactive notifications at one per seven days. That cap is only survivable — rather
 * than limiting — because everything it refuses has somewhere else to go. The bulletin is that
 * somewhere: levels reached, milestones unlocked, weekly recaps, sync problems, exports, version
 * notes, and a copy of every notification actually sent. It is what lets the app be interesting
 * **without spending the interruption budget**, and it is the only surface here that scales, since
 * every future fact lands in it for free.
 *
 * ### Why entries store facts, not sentences
 *
 * The obvious design is to store the finished text — it is what the notification already produced.
 * It is also wrong here: TrackMe ships in seven languages and lets people change language in the
 * app, so a bulletin of stored sentences would be a feed that is permanently in whatever language
 * the user happened to be using when each thing happened. §6.1.7 says "derived from local facts on
 * read where possible", and this is why.
 *
 * So an entry carries [facts] — numbers and identifiers as strings — and `BulletinCopy` renders
 * them at read time in the current language and unit system.
 *
 * [BulletinKind.BROADCAST] is the deliberate exception and the only one: an operator wrote those
 * words, in one language, and there is nothing to re-derive them from. It stores its text.
 *
 * ### What must never be in here
 *
 * No coordinates, no ride titles, no names, no email addresses. The bulletin is a local feed, but
 * it is rendered on a screen someone may hand to a friend, and a ride title can be anything a user
 * typed. Facts are counts, distances, timestamps and ids.
 */
data class BulletinEntry(
    /** Stable and unique; a re-derived or re-delivered fact must not appear twice. */
    val id: String,
    val kind: BulletinKind,
    val createdAtMillis: Long,
    /** Kind-specific facts. Numbers and ids only — see the note above about sentences and PII. */
    val facts: Map<String, String> = emptyMap(),
) {
    fun isUnread(lastSeenCreatedAtMillis: Long?): Boolean {
        val seen = lastSeenCreatedAtMillis ?: return true
        return createdAtMillis > seen
    }

    fun fact(key: String): String? = facts[key]?.takeIf { it.isNotBlank() }
    fun longFact(key: String): Long? = facts[key]?.toLongOrNull()
    fun intFact(key: String): Int? = facts[key]?.toIntOrNull()
    fun doubleFact(key: String): Double? = facts[key]?.toDoubleOrNull()

    companion object {
        /**
         * The feed is capped. A bulletin that grows forever becomes a log nobody scrolls, and the
         * facts at the bottom are ones the user could not act on months ago either.
         */
        const val MAX_RETAINED = 50

        // Fact keys, named once so the writer and the renderer cannot disagree about them.
        const val FACT_TITLE = "title"
        const val FACT_BODY = "body"
        const val FACT_TAG = "tag"
        const val FACT_LINK = "link"
        const val FACT_RIDE_COUNT = "ride_count"
        const val FACT_DISTANCE_METERS = "distance_meters"
        const val FACT_STREAK_WEEKS = "streak_weeks"
        const val FACT_ENDED_AT_MILLIS = "ended_at_millis"
        const val FACT_LEVEL_NAME = "level_name"
        const val FACT_MILESTONE_COUNT = "milestone_count"
        const val FACT_UNSYNCED_COUNT = "unsynced_count"
        const val FACT_SINCE_MILLIS = "since_millis"
        const val FACT_DAYS_AWAY = "days_away"
    }
}

/**
 * What a bulletin entry is about.
 *
 * Ordered roughly by how much the user would care if they saw only one — used nowhere as a
 * priority today, but the order is the answer to "which of these earns the top of an empty list".
 */
enum class BulletinKind {
    /** §6.3 operator broadcast. The only kind whose words are stored rather than derived. */
    BROADCAST,

    /** §6.1.1 #1 — a ride the app finished for you after a crash or force-stop. */
    RIDE_SAVED,

    /** §6.1.5 #23 — cloud backup has been failing. */
    SYNC_PROBLEM,

    /** §6.1.2 #8 — the weekly recap, kept after the notification is gone. */
    WEEKLY_RECAP,

    /** §6.1.2 #9 — a level reached. Notifying would be redundant; the reveal already ran. */
    LEVEL_REACHED,

    /** §6.1.2 #9 — a milestone unlocked, same reasoning. */
    MILESTONE,

    /** §6.1.5 #26 — a new version. Already an in-app prompt; never escalated to the shade. */
    VERSION_NOTE,

    /**
     * §6.1.3 #13 — the return-after-absence notice.
     *
     * Present because §6.1.7's contract is "a copy of every notification actually sent". A Class C
     * notice that interrupted someone and then cannot be found in the feed is the exact failure the
     * bulletin exists to prevent — they saw it, swiped it, and it is gone.
     */
    RETURN_NOTICE;

    companion object {
        /** Exact match only, for the same reason `BroadcastTag.parse` is strict. */
        fun parse(raw: String?): BulletinKind? = entries.firstOrNull { it.name == raw }
    }
}
