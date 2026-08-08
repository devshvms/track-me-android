package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.crypto.GroupCrypto
import `in`.shvms.trackme.data.local.GroupSessionStore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

enum class GroupSessionStatus {
    /** Not in a group. */
    IDLE,

    /** In a group; the leader has not started it. Lobby only, no map presence. */
    PREPARING,

    /** Group Mode. Everyone is visible to everyone. */
    LIVE,

    /**
     * In a group, but the relay is not answering. §8: back off with jitter, keep retrying, keep
     * the user's own ride recording **completely unaffected**. Never a silent failure.
     */
    DEGRADED,

    /** The group ended or expired. Terminal; the client tears down and returns to IDLE. */
    ENDED,
}

data class GroupSessionState(
    val status: GroupSessionStatus = GroupSessionStatus.IDLE,
    val groupId: String? = null,
    val joinCode: String? = null,
    val groupName: String? = null,
    val isLeader: Boolean = false,
    val expiresAtMillis: Long = 0L,
    val maxMembers: Int = 0,
    val rev: Int = 0,
    val positions: List<GroupWire.MemberPosition> = emptyList(),
    val roster: List<GroupWire.RosterEntry> = emptyList(),
    /** Set while [GroupSessionStatus.DEGRADED]; drives the honest "retrying" banner. */
    val degradedSince: Long? = null,
    val consecutiveFailures: Int = 0,
) {
    val isActive: Boolean
        get() = status == GroupSessionStatus.PREPARING ||
            status == GroupSessionStatus.LIVE ||
            status == GroupSessionStatus.DEGRADED
}

class GroupHttpException(val statusCode: Int, val code: String?) :
    Exception("Group request failed with HTTP $statusCode${code?.let { " ($it)" } ?: ""}")

/**
 * Group Ride session lifecycle and the sync loop — SCOPE_1.7.0 §4.6.
 *
 * Mirrors `LiveShareManager`'s `HttpURLConnection` + `JSONObject` shape so the two are consistent,
 * but fixes three things the audit calls out about that file rather than inheriting them:
 *
 * - **§6.2 H1, no retry or backoff anywhere.** Every failure goes through [GroupBackoff]:
 *   exponential, capped, jittered, and it distinguishes "the relay is down, keep trying" from
 *   "you were removed from this group, stop".
 * - **§6.2 H2, `getIdToken(true)` on every request.** At a 10s cadence a forced refresh is a real
 *   per-request cost and an unnecessary dependency on Firebase being reachable. This uses
 *   `getIdToken(false)` and force-refreshes **only after a 401** — the pattern iOS already got
 *   right.
 * - **§6.1 B6, in-memory session state.** Every mutation goes through [GroupSessionStore], so an
 *   OS kill cannot leave a member invisible with no way back.
 *
 * **Position pushes are fed in, not pulled.** [updatePosition] is called by `TrackingService`
 * (GR-09); this class never touches location APIs. That keeps presence and the ride recorder
 * independent, which is the §8 invariant the whole failure table rests on: *a group failure must
 * never affect the user's own ride.*
 */
class GroupSessionManager(
    private val store: GroupSessionStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(GroupSessionState())
    val state: StateFlow<GroupSessionState> = _state.asStateFlow()

    /** The group key, derived from the invite token. Never persisted — always re-derived. */
    @Volatile private var groupKey: ByteArray? = null

    @Volatile private var inviteToken: String? = null

    /** Latest fix from `TrackingService`, or null when the member is not sharing (§8). */
    @Volatile private var pendingPosition: String? = null

    private var syncJob: Job? = null

    // --- Session restore ------------------------------------------------------------------------

    /**
     * Rebuilds a session after process death (B6). Called from `TrackMeApp.onCreate` and from the
     * sticky service restart, both of which run before any UI exists.
     *
     * Returns true when a session was restored, so the caller knows to restart presence.
     */
    fun restore(): Boolean {
        val record = store.load() ?: return false
        return try {
            inviteToken = record.token
            groupKey = GroupCrypto.deriveGroupKey(record.token)
            _state.value = GroupSessionState(
                // Deliberately DEGRADED, not LIVE: we have not spoken to the relay since the
                // restart, so claiming to be live would be asserting a visibility we have not
                // confirmed. The first successful sync corrects it.
                status = GroupSessionStatus.DEGRADED,
                groupId = record.groupId,
                joinCode = record.joinCode,
                isLeader = record.isLeader,
                expiresAtMillis = record.expiresAtMillis,
                maxMembers = record.maxMembers,
                rev = record.rev,
                degradedSince = System.currentTimeMillis(),
            )
            startSyncLoop()
            true
        } catch (e: Exception) {
            // An unusable token means an unusable session. Better to drop it than to sit in a
            // group we cannot read.
            store.clear()
            clearLocal()
            false
        }
    }

    // --- Lifecycle ------------------------------------------------------------------------------

    /**
     * Creates a group. The client mints **both** the invite token and the join code, derives the
     * group key, and sends the relay only `sha256(token)` plus ciphertext — a relay that could see
     * either would be able to read every position it stores (§5.3, and the crypto contract §1).
     */
    suspend fun createGroup(
        groupName: String,
        durationMinutes: Int,
        maxMembers: Int,
        displayName: String?,
        photoUrl: String?,
    ): Result<GroupSessionState> = withContext(Dispatchers.IO) {
        try {
            val token = GroupCrypto.generateInviteToken()
            val key = GroupCrypto.deriveGroupKey(token)
            val joinCode = GroupCrypto.generateJoinCode()

            val body = JSONObject().apply {
                put("tokenHash", GroupCrypto.groupTokenHash(token))
                put("joinCode", joinCode)
                put("wrappedToken", GroupCrypto.wrapTokenForCode(joinCode, token))
                put("durationMinutes", durationMinutes)
                put("maxMembers", maxMembers)
                put("meta", GroupCrypto.seal(key, GroupWire.encodeMeta(groupName, displayName), GroupCrypto.Purpose.Meta))
                put("roster", sealRoster(key, displayName, photoUrl))
            }

            val response = post(AppConfig.GROUP_CREATE_ENDPOINT, body.toString())
            val created = GroupWire.parseCreate(response)

            inviteToken = token
            groupKey = key
            store.save(
                GroupSessionStore.Record(
                    groupId = created.groupId,
                    token = token,
                    joinCode = created.joinCode,
                    isLeader = true,
                    expiresAtMillis = created.expiresAtMillis,
                    maxMembers = created.maxMembers,
                    rev = created.rev,
                ),
            )
            _state.value = GroupSessionState(
                status = GroupSessionStatus.PREPARING,
                groupId = created.groupId,
                joinCode = created.joinCode,
                groupName = groupName,
                isLeader = true,
                expiresAtMillis = created.expiresAtMillis,
                maxMembers = created.maxMembers,
                rev = created.rev,
            )
            startSyncLoop()
            Result.success(_state.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Joins by 6-character code.
     *
     * The code is not the group key, so this is a two-step exchange: resolve the code to get the
     * token **wrapped under it**, unwrap locally, and only then join. The relay never holds the
     * code or the token (crypto contract §2b).
     */
    suspend fun joinByCode(
        rawCode: String,
        displayName: String?,
        photoUrl: String?,
    ): Result<GroupSessionState> = withContext(Dispatchers.IO) {
        try {
            val code = GroupCrypto.normalizeJoinCode(rawCode)
                ?: return@withContext Result.failure(Exception("That code doesn't look right."))

            val resolved = GroupWire.parseResolve(
                get("${AppConfig.GROUP_RESOLVE_ENDPOINT}?c=$code", authenticated = false),
            )
            val wrapped = resolved.wrappedToken
                ?: return@withContext Result.failure(Exception("This invite has expired."))

            val token = GroupCrypto.unwrapTokenWithCode(code, wrapped)
            joinWithToken(token, code, resolved.groupId, displayName, photoUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Joins with an invite token taken from a share link's fragment. Used by the App Links path in
     * 1.7.1; exposed now because [joinByCode] resolves to exactly this.
     */
    suspend fun joinWithToken(
        token: String,
        joinCode: String,
        groupId: String,
        displayName: String?,
        photoUrl: String?,
    ): Result<GroupSessionState> = withContext(Dispatchers.IO) {
        try {
            val key = GroupCrypto.deriveGroupKey(token)
            val body = JSONObject().apply {
                put("groupId", groupId)
                put("tokenHash", GroupCrypto.groupTokenHash(token))
                put("roster", sealRoster(key, displayName, photoUrl))
                put("viaCode", true)
            }
            val joined = GroupWire.parseJoin(post(AppConfig.GROUP_JOIN_ENDPOINT, body.toString()), key)

            inviteToken = token
            groupKey = key
            store.save(
                GroupSessionStore.Record(
                    groupId = joined.groupId,
                    token = token,
                    joinCode = joinCode,
                    isLeader = false,
                    expiresAtMillis = joined.expiresAtMillis,
                    maxMembers = joined.maxMembers,
                    rev = joined.rev,
                ),
            )
            _state.value = GroupSessionState(
                status = statusFor(joined.state),
                groupId = joined.groupId,
                joinCode = joinCode,
                groupName = joined.meta?.name,
                isLeader = false,
                expiresAtMillis = joined.expiresAtMillis,
                maxMembers = joined.maxMembers,
                rev = joined.rev,
            )
            startSyncLoop()
            Result.success(_state.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Leader-only: `PREPARING → LIVE`. */
    suspend fun startGroup(): Result<Unit> = setState("LIVE")

    /** Leader-only: ends the group for everyone and deletes all server-side state. */
    suspend fun endGroup(): Result<Unit> = setState("ENDED").also { teardown() }

    /**
     * Leaves the group.
     *
     * §5.1.3 — *the exit is sacred and silent*. The local teardown happens **regardless of what the
     * relay says**: a member who has decided to go dark must not stay visible because a request
     * failed. The server-side removal is attempted, and its outcome is not allowed to block the
     * user's exit.
     */
    suspend fun leaveGroup(): Result<Unit> = withContext(Dispatchers.IO) {
        val groupId = _state.value.groupId
        teardown()
        if (groupId == null) return@withContext Result.success(Unit)
        try {
            post(AppConfig.GROUP_LEAVE_ENDPOINT, JSONObject().put("groupId", groupId).toString())
            Result.success(Unit)
        } catch (e: Exception) {
            // Already gone locally. The relay's TTL is the backstop for its own state.
            Result.success(Unit)
        }
    }

    private suspend fun setState(next: String): Result<Unit> = withContext(Dispatchers.IO) {
        val groupId = _state.value.groupId ?: return@withContext Result.failure(Exception("Not in a group."))
        try {
            post(
                AppConfig.GROUP_STATE_ENDPOINT,
                JSONObject().put("groupId", groupId).put("state", next).toString(),
            )
            if (next == "LIVE") _state.value = _state.value.copy(status = GroupSessionStatus.LIVE)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Position feed --------------------------------------------------------------------------

    /**
     * Called by `TrackingService` with the latest fix. Sealed immediately so a plaintext coordinate
     * never sits in this object's memory longer than it must.
     *
     * Passing null marks the member as present-but-not-sharing (§8, revoked location permission):
     * they stay in the group, see everyone, and are honestly shown as not sharing.
     */
    fun updatePosition(
        lat: Double?,
        lng: Double?,
        speedMps: Float?,
        headingDeg: Float?,
        batteryPercent: Int?,
        moving: Boolean,
        riding: Boolean = false,
    ) {
        val key = groupKey
        val uid = currentUid()
        if (key == null || uid == null || lat == null || lng == null) {
            pendingPosition = null
            return
        }
        pendingPosition = try {
            GroupCrypto.seal(
                key,
                GroupWire.encodePosition(lat, lng, speedMps, headingDeg, batteryPercent, moving, riding),
                GroupCrypto.Purpose.Position(uid),
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- The sync loop --------------------------------------------------------------------------

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val current = _state.value
                if (!current.isActive || current.groupId == null) break

                // §5.1.2: the TTL is the backstop and it always fires. Do not wait for the relay
                // to tell us about an expiry we can see ourselves.
                if (current.expiresAtMillis in 1..System.currentTimeMillis()) {
                    finish()
                    break
                }

                val delayMs = runCatching { syncOnce() }.getOrElse { failure ->
                    val status = (failure as? GroupHttpException)?.statusCode
                    if (!GroupBackoff.isRetryable(status)) {
                        // 403 = removed from the group, 404 = the group is gone. Both are state
                        // changes the user needs to see, not errors to retry into.
                        finish()
                        return@launch
                    }
                    val failures = _state.value.consecutiveFailures + 1
                    _state.value = _state.value.copy(
                        status = GroupSessionStatus.DEGRADED,
                        consecutiveFailures = failures,
                        degradedSince = _state.value.degradedSince ?: System.currentTimeMillis(),
                    )
                    GroupBackoff.delayMillis(failures)
                }
                if (delayMs <= 0L) break
                delay(delayMs)
            }
        }
    }

    /** One sync. Returns the delay before the next, or 0 to stop. */
    private suspend fun syncOnce(): Long {
        val current = _state.value
        val key = groupKey ?: return 0L
        val uid = currentUid() ?: return 0L

        val body = JSONObject().apply {
            put("groupId", current.groupId)
            pendingPosition?.let { put("pos", it) }
            put("moving", current.positions.isNotEmpty())
            put("foreground", isForeground)
            put("rev", current.rev)
        }

        val result = GroupWire.parseSync(post(AppConfig.GROUP_SYNC_ENDPOINT, body.toString()), key, uid)

        if (result.state == "ENDED") {
            finish()
            return 0L
        }

        store.updateRev(result.rev)
        _state.value = current.copy(
            status = statusFor(result.state),
            expiresAtMillis = result.expiresAtMillis.takeIf { it > 0 } ?: current.expiresAtMillis,
            rev = result.rev,
            maxMembers = result.maxMembers,
            positions = result.positions,
            // A rev-gated roster arrives only when it changed; keep the last one otherwise.
            roster = result.roster ?: current.roster,
            groupName = result.meta?.name ?: current.groupName,
            consecutiveFailures = 0,
            degradedSince = null,
        )
        return GroupBackoff.nextDelayMillis(0, result.nextSyncInSec)
    }

    /** The group ended, expired, or we were removed. Terminal, and the same for all three. */
    private fun finish() {
        _state.value = _state.value.copy(
            status = GroupSessionStatus.ENDED,
            positions = emptyList(),
        )
        store.clear()
        inviteToken = null
        groupKey = null
        pendingPosition = null
    }

    private suspend fun teardown() {
        syncJob?.let { runCatching { it.cancelAndJoin() } }
        syncJob = null
        store.clear()
        clearLocal()
    }

    private fun clearLocal() {
        inviteToken = null
        groupKey = null
        pendingPosition = null
        _state.value = GroupSessionState()
    }

    /** Set by the UI so the server can pick the right cadence (§7.1). */
    @Volatile var isForeground: Boolean = false

    // --- HTTP -----------------------------------------------------------------------------------

    /**
     * §6.2 H2: `getIdToken(false)` uses the cached token and only hits the network when it has
     * genuinely expired. `LiveShareManager` forces a refresh on every single request, which at a
     * 10s group cadence would be a per-sync round trip to Firebase for no benefit.
     */
    private suspend fun idToken(forceRefresh: Boolean = false): String =
        FirebaseAuth.getInstance().currentUser
            ?.getIdToken(forceRefresh)
            ?.await()
            ?.token
            ?: throw Exception("You must be signed in to ride together.")

    private fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private suspend fun post(endpoint: String, body: String): String =
        request("POST", endpoint, body, authenticated = true, retryOn401 = true)

    private suspend fun get(endpoint: String, authenticated: Boolean): String =
        request("GET", endpoint, null, authenticated, retryOn401 = authenticated)

    private suspend fun request(
        method: String,
        endpoint: String,
        body: String?,
        authenticated: Boolean,
        retryOn401: Boolean,
        forceTokenRefresh: Boolean = false,
    ): String {
        val conn = (URL(AppConfig.GROUP_BASE_URL + endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (authenticated) setRequestProperty("Authorization", "Bearer ${idToken(forceTokenRefresh)}")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        body?.let { OutputStreamWriter(conn.outputStream).use { w -> w.write(it) } }

        val status = conn.responseCode
        if (status == HttpURLConnection.HTTP_OK) {
            return conn.inputStream.bufferedReader().use { it.readText() }
        }

        val errorBody = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()

        // H2's other half: force a refresh *only* here, and only once, so an expired token costs
        // one extra round trip rather than one on every request.
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED && retryOn401 && !forceTokenRefresh) {
            return request(method, endpoint, body, authenticated, retryOn401 = false, forceTokenRefresh = true)
        }

        throw GroupHttpException(status, GroupWire.errorCode(errorBody))
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun sealRoster(key: ByteArray, displayName: String?, photoUrl: String?): String {
        val uid = currentUid() ?: throw Exception("You must be signed in to ride together.")
        return GroupCrypto.seal(
            key,
            GroupWire.encodeRoster(displayName, initialsOf(displayName), photoUrl),
            GroupCrypto.Purpose.Roster(uid),
        )
    }

    private fun statusFor(state: String): GroupSessionStatus = when (state) {
        "LIVE" -> GroupSessionStatus.LIVE
        "ENDED" -> GroupSessionStatus.ENDED
        else -> GroupSessionStatus.PREPARING
    }

    companion object {
        /**
         * First letter of the first and last word — §3.3's rule. Kept here rather than in the UI
         * because it is sealed into the roster envelope, so every client must agree on it.
         */
        fun initialsOf(displayName: String?): String? {
            val words = displayName?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            if (words.isNullOrEmpty()) return null
            val first = words.first().first().uppercaseChar()
            val last = if (words.size > 1) words.last().first().uppercaseChar() else null
            return if (last != null) "$first$last" else "$first"
        }
    }
}
