package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The one durable record of "I am in a group right now" — SCOPE_1.7.0 §6.1 B6.
 *
 * B6 is a blocker, not a nicety: `LiveShareState` lives only in memory, so an OS kill leaves a
 * member **invisible to the group with no way back**, and the server-side session alive until its
 * TTL. The sticky service already restores the *ride*; nothing restores the sharing. Fixing it
 * here for groups is also the shape the pre-existing solo live-share bug needs.
 *
 * Follows the `RideStatsStore` pattern: its own prefs file, one JSON record rather than many
 * loose keys, and fail-closed on anything unreadable.
 *
 * **The invite token is stored.** It is the group's key material, so this is worth being explicit
 * about: it lives in app-private `SharedPreferences`, unreadable by other apps on a
 * non-rooted device, and it is deleted the moment the session ends. Keeping it is unavoidable —
 * without it a restored session cannot decrypt anything and the member is present but blind. It is
 * bounded by the session TTL like everything else, and [clear] is called on leave, on end, and on
 * expiry.
 */
class GroupSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Everything needed to resume a session after process death, and nothing else. No positions,
     * no roster — those are re-fetched on the first sync, and persisting them would be a location
     * history on disk, which §5.1.4 forbids outright.
     */
    data class Record(
        val groupId: String,
        /** The invite token. Key material — see the class note. */
        val token: String,
        val joinCode: String,
        val isLeader: Boolean,
        val expiresAtMillis: Long,
        val maxMembers: Int,
        /** Last roster revision seen, so a resumed session does not refetch the roster needlessly. */
        val rev: Int,

        /**
         * This rider's status, so a "Need help" survives a service restart or process death
         * (SCOPE_1.7.2 §4.4). Null when none is set.
         *
         * Added without a [VERSION] bump on purpose: every field here is optional on read, so an
         * existing 1.7.1 record still loads. Bumping would have made `load` discard it and drop
         * riders out of a live group mid-ride, purely to record a field that can default.
         */
        val statusCode: String? = null,
        /** `SystemClock.elapsedRealtime()` when the status was set. Void after a reboot. */
        val statusSetAtElapsed: Long = 0L,
        /** Wall clock minus elapsed at set-time — stable within a boot, so it detects one. */
        val statusBootEpoch: Long = 0L,
    )

    /** Null when there is no session, or when the stored one has already expired. */
    fun load(nowMillis: Long = System.currentTimeMillis()): Record? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        val record = try {
            val json = JSONObject(raw)
            if (json.optInt(FIELD_VERSION, 0) != VERSION) return clearAndReturnNull()
            Record(
                groupId = json.getString(FIELD_GROUP_ID),
                token = json.getString(FIELD_TOKEN),
                joinCode = json.optString(FIELD_JOIN_CODE, ""),
                isLeader = json.optBoolean(FIELD_IS_LEADER, false),
                expiresAtMillis = json.getLong(FIELD_EXPIRES_AT),
                maxMembers = json.optInt(FIELD_MAX_MEMBERS, 5),
                rev = json.optInt(FIELD_REV, 0),
                statusCode = json.optString(FIELD_STATUS_CODE).takeIf { it.isNotEmpty() },
                statusSetAtElapsed = json.optLong(FIELD_STATUS_SET_AT, 0L),
                statusBootEpoch = json.optLong(FIELD_STATUS_BOOT_EPOCH, 0L),
            )
        } catch (e: Exception) {
            // Fail closed. A record we cannot read is a session we cannot resume, and carrying a
            // half-parsed one forward would leave the user believing they are visible when they
            // are not — the worst direction for this feature to be wrong in.
            return clearAndReturnNull()
        }

        // §5.1.2, expiring by default. A restored session past its TTL is not a session; the relay
        // would 404 it anyway, and holding the token any longer serves nobody.
        if (record.expiresAtMillis <= nowMillis) return clearAndReturnNull()
        return record
    }

    fun save(record: Record) {
        val json = JSONObject().apply {
            put(FIELD_VERSION, VERSION)
            put(FIELD_GROUP_ID, record.groupId)
            put(FIELD_TOKEN, record.token)
            put(FIELD_JOIN_CODE, record.joinCode)
            put(FIELD_IS_LEADER, record.isLeader)
            put(FIELD_EXPIRES_AT, record.expiresAtMillis)
            put(FIELD_MAX_MEMBERS, record.maxMembers)
            put(FIELD_REV, record.rev)
            record.statusCode?.let { put(FIELD_STATUS_CODE, it) }
            if (record.statusSetAtElapsed > 0L) {
                put(FIELD_STATUS_SET_AT, record.statusSetAtElapsed)
                put(FIELD_STATUS_BOOT_EPOCH, record.statusBootEpoch)
            }
        }
        // commit(), not apply(): this is written on the path where the process is about to be
        // killed, which is the exact case B6 exists for. An async write that loses the race is the
        // bug, not a performance win.
        prefs.edit().putString(KEY_RECORD, json.toString()).commit()
    }

    /** Cheap update for the field that changes most often, without rewriting the session. */
    fun updateRev(rev: Int) {
        val current = load() ?: return
        if (current.rev == rev) return
        save(current.copy(rev = rev))
    }

    /**
     * Wipes the session. Called on leave, on group end, on expiry, and on sign-out — every path
     * that ends membership, so the token never outlives it.
     */
    fun clear() {
        prefs.edit().remove(KEY_RECORD).commit()
    }

    private fun clearAndReturnNull(): Record? {
        clear()
        return null
    }

    companion object {
        /** Separate file so group state is trivial to inspect and to clear. */
        const val PREFS_NAME = "trackme_group_session"

        private const val KEY_RECORD = "session"

        /** Bumped when the record shape changes; an older or newer record is discarded, not guessed. */
        const val VERSION = 1

        private const val FIELD_VERSION = "v"
        private const val FIELD_GROUP_ID = "groupId"
        private const val FIELD_TOKEN = "token"
        private const val FIELD_JOIN_CODE = "joinCode"
        private const val FIELD_IS_LEADER = "isLeader"
        private const val FIELD_EXPIRES_AT = "expiresAt"
        private const val FIELD_MAX_MEMBERS = "maxMembers"
        private const val FIELD_REV = "rev"
        private const val FIELD_STATUS_CODE = "st"
        private const val FIELD_STATUS_SET_AT = "stAt"
        private const val FIELD_STATUS_BOOT_EPOCH = "stBoot"
    }
}
