package `in`.shvms.trackme.data.local

import android.content.Context
import `in`.shvms.trackme.domain.notifications.BroadcastTag
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * SCOPE_1.8.7 §6.3 — the durable half of an operator broadcast.
 *
 * A notification is something you swipe away at a traffic light. If the shade were the only place a
 * broadcast existed, then "we told everyone" would mean "we told everyone who happened to be
 * looking", which is not a claim worth making about a message that says the build they are running
 * has a defect.
 *
 * So every broadcast lands here as well, whichever route it arrived by, and the in-app surface
 * reads from here. This is also the seed of §6.1.7's bulletin: the same store, with more kinds of
 * fact in it, is the whole feature.
 *
 * SharedPreferences rather than Room deliberately. The corpus is a handful of short rows that are
 * pruned aggressively, and it has to be readable from
 * [in.shvms.trackme.service.notifications.TrackMeMessagingService] on a background thread with no
 * database open. A schema migration for this would cost more than it could ever buy.
 */
class BroadcastStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _broadcasts = MutableStateFlow(readAll())
    val broadcasts: StateFlow<List<OperatorBroadcast>> = _broadcasts.asStateFlow()

    private val _lastSeenCreatedAt = MutableStateFlow(readLastSeen())

    /** The newest broadcast the user has actually been shown, or null. */
    val lastSeenCreatedAt: StateFlow<Long?> = _lastSeenCreatedAt.asStateFlow()

    /**
     * Stores a broadcast, replacing any earlier copy with the same id.
     *
     * Idempotent by id because the same broadcast genuinely arrives twice: once by push, once by
     * the foreground read of the `broadcasts` collection. Two copies in the list would show the
     * user the same problem twice and make the unread badge lie.
     *
     * @return true when this was new — the caller uses that to decide whether to post a
     *   notification, so a duplicate arrival never re-interrupts.
     */
    fun store(broadcast: OperatorBroadcast): Boolean {
        val existing = _broadcasts.value
        if (existing.any { it.id == broadcast.id }) return false
        val updated = (existing + broadcast)
            .sortedByDescending { it.createdAtMillis }
            .take(MAX_RETAINED)
        writeAll(updated)
        _broadcasts.value = updated
        return true
    }

    /** Marks everything up to [createdAtMillis] as seen. Never moves backwards. */
    fun markSeen(createdAtMillis: Long) {
        val current = _lastSeenCreatedAt.value
        if (current != null && createdAtMillis <= current) return
        prefs.edit().putLong(KEY_LAST_SEEN, createdAtMillis).apply()
        _lastSeenCreatedAt.value = createdAtMillis
    }

    /** Broadcasts that are both unread and true for this build. */
    fun unread(versionCode: Int, lastSeen: Long? = _lastSeenCreatedAt.value): List<OperatorBroadcast> =
        _broadcasts.value.filter { it.isUnread(lastSeen) && it.appliesTo(versionCode) }

    private fun readLastSeen(): Long? =
        if (prefs.contains(KEY_LAST_SEEN)) prefs.getLong(KEY_LAST_SEEN, 0L) else null

    private fun readAll(): List<OperatorBroadcast> {
        val raw = prefs.getString(KEY_BROADCASTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val row = array.getJSONObject(index)
                // Re-validated on read, not trusted because we wrote it. A downgrade, a restore
                // from a different build, or a hand-edited prefs file all land here, and the parser
                // is the only thing standing between them and a HIGH-importance notification.
                OperatorBroadcast.parse(
                    row.keys().asSequence().associateWith { key ->
                        if (row.isNull(key)) null else row.get(key)
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(broadcasts: List<OperatorBroadcast>) {
        val array = JSONArray()
        broadcasts.forEach { broadcast ->
            array.put(
                JSONObject().apply {
                    put("id", broadcast.id)
                    put("tag", broadcast.tag.name)
                    put("title", broadcast.title)
                    put("body", broadcast.body)
                    put("created_at_millis", broadcast.createdAtMillis)
                    broadcast.appliesToVersionsAtOrBelow?.let {
                        put("applies_to_versions_at_or_below", it)
                    }
                    broadcast.learnMoreUrl?.let { put("learn_more_url", it) }
                }
            )
        }
        prefs.edit().putString(KEY_BROADCASTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "trackme_broadcasts"
        private const val KEY_BROADCASTS = "broadcasts"
        private const val KEY_LAST_SEEN = "last_seen_created_at"

        /**
         * Operational messages go stale. Twenty is far more than an honest operator will ever have
         * outstanding, and keeping an unbounded list would turn a preference file into a log.
         */
        const val MAX_RETAINED = 20

        /** Convenience for the tag, so callers do not import the enum just to switch on it. */
        fun isUrgent(broadcast: OperatorBroadcast): Boolean = broadcast.tag == BroadcastTag.URGENT
    }
}
