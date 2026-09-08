package `in`.shvms.trackme.data.local

import android.content.Context
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * SCOPE_1.8.7 §6.1.7 — the bulletin's storage.
 *
 * Append-only from the caller's point of view, capped, and ordered newest first. No network
 * dependency of any kind: everything in here was computed on this device or arrived through a
 * channel that already landed.
 *
 * SharedPreferences rather than Room, for the same reasons as `BroadcastStore`: fifty short rows,
 * written from background workers with no database open, and a schema entry is a liability the app
 * carries forever — TASK-309 was three releases of work to remove two of them.
 */
class BulletinStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(readAll())
    val entries: StateFlow<List<BulletinEntry>> = _entries.asStateFlow()

    private val _lastSeenCreatedAt = MutableStateFlow(readLastSeen())
    val lastSeenCreatedAt: StateFlow<Long?> = _lastSeenCreatedAt.asStateFlow()

    /**
     * Adds an entry, ignoring one whose id is already present.
     *
     * Idempotent by id because the same fact reaches here by more than one route — a broadcast
     * arrives by push *and* by the foreground reconcile, a recap is both notified and read in-app.
     * A duplicated row would make the unread badge lie, and the badge is the only thing that makes
     * an un-notified fact discoverable at all.
     *
     * @return true when the entry was new.
     */
    fun add(entry: BulletinEntry): Boolean {
        if (_entries.value.any { it.id == entry.id }) return false
        val updated = (_entries.value + entry)
            .sortedByDescending { it.createdAtMillis }
            .take(BulletinEntry.MAX_RETAINED)
        writeAll(updated)
        _entries.value = updated
        return true
    }

    /** Everything the user has not seen yet. The badge counts these. */
    fun unread(): List<BulletinEntry> =
        _entries.value.filter { it.isUnread(_lastSeenCreatedAt.value) }

    /**
     * Marks everything currently in the feed as seen. Never moves backwards.
     *
     * Called when the bulletin is opened rather than per-row: the feed is short and read at a
     * glance, and a per-row read state would make the badge a to-do list — which is the shape that
     * turns a calm surface into an obligation.
     */
    fun markAllSeen() {
        val newest = _entries.value.maxOfOrNull { it.createdAtMillis } ?: return
        val current = _lastSeenCreatedAt.value
        if (current != null && newest <= current) return
        prefs.edit().putLong(KEY_LAST_SEEN, newest).apply()
        _lastSeenCreatedAt.value = newest
    }

    /** §6.1.7: "capped, clearable". The user can empty it; nothing here is a record we need. */
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).remove(KEY_LAST_SEEN).apply()
        _entries.value = emptyList()
        _lastSeenCreatedAt.value = null
    }

    private fun readLastSeen(): Long? =
        if (prefs.contains(KEY_LAST_SEEN)) prefs.getLong(KEY_LAST_SEEN, 0L) else null

    private fun readAll(): List<BulletinEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val row = array.getJSONObject(index)
                val kind = BulletinKind.parse(row.optString("kind")) ?: return@mapNotNull null
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val facts = row.optJSONObject("facts")
                BulletinEntry(
                    id = id,
                    kind = kind,
                    createdAtMillis = row.optLong("created_at_millis"),
                    facts = facts?.keys()?.asSequence()
                        ?.associateWith { facts.optString(it) }
                        .orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<BulletinEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("kind", entry.kind.name)
                    put("created_at_millis", entry.createdAtMillis)
                    put("facts", JSONObject(entry.facts))
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "trackme_bulletin"
        const val KEY_ENTRIES = "entries"
        const val KEY_LAST_SEEN = "last_seen_created_at"
    }
}
