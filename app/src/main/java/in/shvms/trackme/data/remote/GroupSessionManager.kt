package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.crypto.GroupCrypto
import `in`.shvms.trackme.domain.group.AlertPolicy
import `in`.shvms.trackme.domain.group.GroupPresencePolicy
import `in`.shvms.trackme.domain.group.RiderStatusCodec
import android.os.SystemClock
import `in`.shvms.trackme.data.local.GroupSessionStore
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.GroupJoinFailure
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
    /**
     * The invite token, for building the share link only.
     *
     * It is key material, so it must never be logged, put in a query string, or written to a
     * notification — the only place it belongs is the FRAGMENT of the share link (amendment A6).
     */
    val inviteToken: String? = null,
    val groupName: String? = null,
    /** §2.9: shown as a pin — a pin is a fact, not an estimate. Null when none was set. */
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
    /** The leader's optional scheduled start (D6). Null means "--" in the UI, never a guess. */
    val startAtMillis: Long? = null,
    val isLeader: Boolean = false,
    val expiresAtMillis: Long = 0L,
    val maxMembers: Int = 0,
    val rev: Int = 0,
    /**
     * The relay's current cadence. §2.6 defines staleness as "2× the CURRENT sync interval", so
     * the marker rules need the server's live value, not a client constant — when the relay slows
     * everyone down under load (§7.2), "stale" has to slow down with it or every marker greys out.
     */
    val syncIntervalSec: Int = GroupBackoff.DEFAULT_SYNC_INTERVAL_SEC,
    val positions: List<GroupWire.MemberPosition> = emptyList(),
    val roster: List<GroupWire.RosterEntry> = emptyList(),
    /** Set while [GroupSessionStatus.DEGRADED]; drives the honest "retrying" banner. */
    val degradedSince: Long? = null,
    val consecutiveFailures: Int = 0,
    /** When this member joined, for §9's co-presence and time-to-first-leave metrics. */
    val joinedAtMillis: Long = 0L,
    /**
     * False when we are in the group but have nothing to send — §8's revoked-permission case.
     *
     * §8 requires this to be surfaced, not hidden: *"Stop pushing, stay in the group as a viewer.
     * Honest banner: 'You're not sharing your location. Others can't see you.' — symmetry made
     * visible, not hidden."* A member who silently believes they are visible is the single worst
     * way for this feature to be wrong.
     */
    val isSharingPosition: Boolean = false,

    // --- SCOPE_1.7.2 -----------------------------------------------------------------------

    /** Other members' statuses, raw. Parsing is `RiderStatusCodec`'s job (§4.2). */
    val statuses: List<GroupWire.MemberStatus> = emptyList(),
    /** This rider's own status code, or null. Survives process death via `GroupSessionStore`. */
    val selfStatusCode: String? = null,
    /** False until the relay echoes our status back. Drives `Not sent yet` — never optimistic. */
    val selfStatusAcknowledged: Boolean = false,
    /**
     * The relay's clock from the last sync, and the anchor every age is measured against (**A32**).
     * Zero when the relay predates the field, in which case callers fall back to the device clock.
     */
    val serverNowMillis: Long = 0L,
    /** `SystemClock.elapsedRealtime()` alongside [serverNowMillis]. */
    val syncReceivedAtElapsed: Long = 0L,
    /** When this session became active. The staleness reference before any sync has succeeded. */
    val sessionStartedElapsed: Long = 0L,
    /** Last valid authenticated sync response. Drives the Home pill (§4.5). */
    val lastSuccessfulSyncElapsed: Long? = null,
    /**
     * Last time the relay **accepted a new position** — not the same fact as a successful sync.
     * Drives "Last shared" (§4.4); a healthy network with a frozen GPS must not read as fresh.
     */
    val lastOwnPositionAckElapsed: Long? = null,
    val lastSyncFailureKind: GroupPresencePolicy.FailureKind? = null,
) {
    val isActive: Boolean
        get() = status == GroupSessionStatus.PREPARING ||
            status == GroupSessionStatus.LIVE ||
            status == GroupSessionStatus.DEGRADED
}

/**
 * Why a group stopped — §8 wants each of these to have an *observed, non-silent* behaviour.
 *
 * The distinction is not pedantry: "the leader ended it" and "you were removed" are very different
 * things to be told, and §8 requires the expiry case to say explicitly that the ride is still
 * recording, because a map going blank mid-ride otherwise reads as the app breaking.
 */
enum class GroupEndReason {
    /** The leader ended it, or the relay reported ENDED. */
    ENDED,

    /** The session TTL fired. §5.1.2's backstop, and always expected — there was a countdown. */
    EXPIRED,

    /** The relay returned 403: this member is no longer in the group (§5.2). */
    REMOVED,
}

/**
 * A one-shot notice that outlives the session it describes.
 *
 * The session state itself goes inactive the instant a group ends, so a UI reading only that state
 * has nothing left to explain what happened — the member simply finds themselves out of a group,
 * with the map blank and no reason given. §8 requires a *"clear notice"*, so the reason survives
 * separately until the user has seen it.
 */
data class GroupEndNotice(
    val reason: GroupEndReason,
    /** §8: "Session TTL expires mid-ride → Group Mode off; **ride keeps recording**." */
    val rideStillRecording: Boolean,
)

class GroupHttpException(val statusCode: Int, val code: String?) :
    Exception("Group request failed with HTTP $statusCode${code?.let { " ($it)" } ?: ""}")

/**
 * Typed so a signed-out join is distinguishable from a relay refusal without matching on the
 * message, which is user-facing prose and will be translated.
 */
class GroupSignedOutException : Exception("You must be signed in to ride together.")

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

    private val _endNotice = MutableStateFlow<GroupEndNotice?>(null)

    /** Null until a group ends unexpectedly for this member. Cleared by [acknowledgeEndNotice]. */
    val endNotice: StateFlow<GroupEndNotice?> = _endNotice.asStateFlow()

    /** Called once the user has been told. */
    fun acknowledgeEndNotice() {
        _endNotice.value = null
    }

    /** The group key, derived from the invite token. Never persisted — always re-derived. */
    @Volatile private var groupKey: ByteArray? = null

    @Volatile private var inviteToken: String? = null

    /** Latest fix from `TrackingService`, or null when the member is not sharing (§8). */
    @Volatile private var pendingPosition: String? = null

    /**
     * The sealed status envelope waiting to be acknowledged, and the monotonic instant it was set.
     *
     * **The envelope is retained, not re-sealed on retry.** A34's idempotency rule keys on the
     * relay seeing byte-identical ciphertext; re-sealing would mint a fresh nonce, the relay would
     * treat it as new, and the status would get *younger* every time the network flapped. The
     * natural implementation re-seals, which is exactly why this is a field rather than a
     * recomputation.
     */
    @Volatile private var pendingStatusEnvelope: String? = null
    @Volatile private var pendingStatusOp: String = ""
    @Volatile private var selfStatusSetAtElapsed: Long = 0L
    @Volatile private var selfStatusBootEpoch: Long = 0L

    /**
     * The relay's timestamp on our own last accepted position.
     *
     * Needed because A34 deliberately does *not* advance that timestamp for an unchanged resend —
     * so comparing against the previous value is the only way to tell a fresh acceptance from an
     * idempotent echo.
     */
    @Volatile private var lastOwnPositionServerTs: Long = 0L

    /** Holds a severity-1 status back for its undo window (O12). */
    private var undoJob: Job? = null

    /**
     * What each member's status was on the previous sync, and whether *this device* raised an alert
     * for it — §5.2's "once per transition" rule and §3.7's "only people who saw the alarm see the
     * resolution", both of which need memory the sync response does not carry.
     *
     * Sync is a **state** snapshot, not an event stream: without this, the same "Need help" arriving
     * every ten seconds would re-alert every ten seconds, which is the fastest way to make the tier
     * worthless.
     */
    private val lastSeenStatus = mutableMapOf<String, String?>()
    private val alertRaisedFor = mutableSetOf<String>()

    /** Set by the UI (§5.2). Session-scoped, because a group is ephemeral by construction. */
    @Volatile var alertsMuted: Boolean = false

    /** Wired by the service so the domain layer never learns what a notification is. */
    @Volatile var onAlertSignal: ((AlertPolicy.Signal, String, String) -> Unit)? = null

    /**
     * Progress toward the group's destination, when one was set.
     *
     * §2.9's payoff: the estimator ships dark but MEASURED. Without this the calibration event
     * never fires, 1.8 has no error distribution to calibrate against, and the whole reason to
     * build ETA a release early evaporates — "the difference between deferring a feature and
     * wasting a release".
     */
    @Volatile private var destinationProgress: `in`.shvms.trackme.domain.group.DestinationProgress? = null

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
                inviteToken = record.token,
                isLeader = record.isLeader,
                expiresAtMillis = record.expiresAtMillis,
                maxMembers = record.maxMembers,
                rev = record.rev,
                degradedSince = System.currentTimeMillis(),
                sessionStartedElapsed = SystemClock.elapsedRealtime(),
                // A restored status outlives process death (§4.4). Its age may not — the boot epoch
                // is what decides that, and `statusAgeSeconds()` checks it before every send.
                selfStatusCode = record.statusCode,
                selfStatusAcknowledged = false,
            )
            selfStatusSetAtElapsed = record.statusSetAtElapsed
            selfStatusBootEpoch = record.statusBootEpoch
            record.statusCode?.let { resealPendingStatus(it) }
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
        destinationLat: Double? = null,
        destinationLng: Double? = null,
        startAtMillis: Long? = null,
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
                put("meta", GroupCrypto.seal(key, GroupWire.encodeMeta(groupName, displayName, destinationLat, destinationLng, startAtMillis), GroupCrypto.Purpose.Meta))
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
            AnalyticsManager.trackGroupCreated(
                durationMinutes = durationMinutes,
                maxMembers = maxMembers,
                hasDestination = destinationLat != null && destinationLng != null,
                hasStartTime = startAtMillis != null,
            )
            _state.value = GroupSessionState(
                status = GroupSessionStatus.PREPARING,
                joinedAtMillis = System.currentTimeMillis(),
                sessionStartedElapsed = SystemClock.elapsedRealtime(),
                groupId = created.groupId,
                joinCode = created.joinCode,
                inviteToken = token,
                groupName = groupName,
                destinationLat = destinationLat,
                destinationLng = destinationLng,
                startAtMillis = startAtMillis,
                isLeader = true,
                expiresAtMillis = created.expiresAtMillis,
                maxMembers = created.maxMembers,
                rev = created.rev,
                syncIntervalSec = created.syncIntervalSec,
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
        AnalyticsManager.trackGroupInviteOpened(viaCode = true)
        try {
            val code = GroupCrypto.normalizeJoinCode(rawCode)
                ?: return@withContext failJoin(GroupJoinFailure.MALFORMED_CODE, viaCode = true) {
                    Exception("That code doesn't look right.")
                }

            val resolved = GroupWire.parseResolve(
                get("${AppConfig.GROUP_RESOLVE_ENDPOINT}?c=$code", authenticated = false),
            )
            val wrapped = resolved.wrappedToken
                ?: return@withContext failJoin(GroupJoinFailure.EXPIRED, viaCode = true) {
                    Exception("This invite has expired.")
                }

            val token = GroupCrypto.unwrapTokenWithCode(code, wrapped)
            joinWithToken(token, code, resolved.groupId, displayName, photoUrl, viaCode = true)
        } catch (e: Exception) {
            AnalyticsManager.trackGroupJoinFailed(classifyJoinFailure(e), viaCode = true)
            Result.failure(e)
        }
    }

    /**
     * Joins from a shared link, where all we hold is the token.
     *
     * The token proves the invitation but says nothing about *which* group, so it resolves first —
     * by `sha256(token)`, never the token itself, because a raw token in a query string lands in an
     * access log and §10 forbids that outright (A6).
     */
    suspend fun joinByToken(
        token: String,
        displayName: String?,
        photoUrl: String?,
    ): Result<GroupSessionState> = withContext(Dispatchers.IO) {
        try {
            val hash = GroupCrypto.groupTokenHash(token)
            val resolved = GroupWire.parseResolve(
                get("${AppConfig.GROUP_RESOLVE_ENDPOINT}?t=$hash", authenticated = false),
            )
            // The code comes back on the ?t= path so a link-joiner can still re-share it. Empty
            // is survivable — they simply have no code to pass on — but it is there, so use it.
            joinWithToken(
                token,
                resolved.joinCode.orEmpty(),
                resolved.groupId,
                displayName,
                photoUrl,
                viaCode = false,
            )
        } catch (e: Exception) {
            AnalyticsManager.trackGroupJoinFailed(classifyJoinFailure(e), viaCode = false)
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
        viaCode: Boolean,
    ): Result<GroupSessionState> = withContext(Dispatchers.IO) {
        try {
            val key = GroupCrypto.deriveGroupKey(token)
            val body = JSONObject().apply {
                put("groupId", groupId)
                put("tokenHash", GroupCrypto.groupTokenHash(token))
                put("roster", sealRoster(key, displayName, photoUrl))
                // Both join paths funnel through here, so this was reporting every link-join as a
                // code-join — to the relay as well as to analytics.
                put("viaCode", viaCode)
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
            AnalyticsManager.trackGroupMemberJoined(joined.memberCount, viaCode = viaCode)
            _state.value = GroupSessionState(
                status = statusFor(joined.state),
                joinedAtMillis = System.currentTimeMillis(),
                sessionStartedElapsed = SystemClock.elapsedRealtime(),
                groupId = joined.groupId,
                joinCode = joinCode,
                inviteToken = token,
                groupName = joined.meta?.name,
                destinationLat = joined.meta?.destLat,
                destinationLng = joined.meta?.destLng,
                startAtMillis = joined.meta?.startAtMillis,
                isLeader = false,
                expiresAtMillis = joined.expiresAtMillis,
                maxMembers = joined.maxMembers,
                rev = joined.rev,
                syncIntervalSec = joined.syncIntervalSec,
            )
            startSyncLoop()
            Result.success(_state.value)
        } catch (e: Exception) {
            // Tracked here rather than in the callers: this returns `Result.failure` instead of
            // throwing, so a relay refusal — GROUP_FULL being the common one — never reaches their
            // catch blocks.
            AnalyticsManager.trackGroupJoinFailed(classifyJoinFailure(e), viaCode = viaCode)
            Result.failure(e)
        }
    }

    /** Leader-only: `PREPARING → LIVE`. */
    suspend fun startGroup(): Result<Unit> = setState("LIVE")

    /** Leader-only: ends the group for everyone and deletes all server-side state. */
    suspend fun endGroup(): Result<Unit> {
        emitSessionMetrics(_state.value, leftDeliberately = false, reason = "leader_ended")
        return setState("ENDED").also { teardown() }
    }

    /**
     * Leaves the group.
     *
     * §5.1.3 — *the exit is sacred and silent*. The local teardown happens **regardless of what the
     * relay says**: a member who has decided to go dark must not stay visible because a request
     * failed. The server-side removal is attempted, and its outcome is not allowed to block the
     * user's exit.
     */
    suspend fun leaveGroup(): Result<Unit> = withContext(Dispatchers.IO) {
        val before = _state.value
        val groupId = before.groupId
        emitSessionMetrics(before, leftDeliberately = true)
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

    /**
     * Leader edits the destination and scheduled start.
     *
     * Both are nullable and both are honoured as written — passing null CLEARS them. §8 requires a
     * destination change to be "visible to all, never silent", and the relay's rev bump is what
     * makes that true: every member refetches meta on their next sync.
     *
     * The name is not editable here. It is baked into the share message and the join sheet, and a
     * group renaming itself under people who already joined is confusion for no gain.
     */
    suspend fun updateMeta(
        destinationLat: Double?,
        destinationLng: Double?,
        startAtMillis: Long?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _state.value
        val groupId = session.groupId ?: return@withContext Result.failure(Exception("Not in a group."))
        val key = groupKey ?: return@withContext Result.failure(Exception("Not in a group."))
        if (!session.isLeader) return@withContext Result.failure(Exception("Only the leader can do that."))

        try {
            val meta = GroupCrypto.seal(
                key,
                GroupWire.encodeMeta(
                    name = session.groupName.orEmpty(),
                    ownerDisplayName = null,
                    destLat = destinationLat,
                    destLng = destinationLng,
                    startAtMillis = startAtMillis,
                ),
                GroupCrypto.Purpose.Meta,
            )
            post(
                AppConfig.GROUP_META_ENDPOINT,
                JSONObject().put("groupId", groupId).put("meta", meta).toString(),
            )
            // Applied locally at once rather than waiting a sync: the leader just pressed save, and
            // a UI that ignores that for ten seconds reads as the edit having failed.
            _state.value = _state.value.copy(
                destinationLat = destinationLat,
                destinationLng = destinationLng,
                startAtMillis = startAtMillis,
            )
            // The estimator is bound to a fixed destination, so a change invalidates it.
            destinationProgress = null
            // `group_created` froze both of these as they stood at creation, so a group that gains
            // a destination later would otherwise be counted forever as one that never had one —
            // and §2.9 sizes the ETA work off exactly that number.
            AnalyticsManager.trackGroupMetaUpdated(
                hasDestination = destinationLat != null && destinationLng != null,
                hasStartTime = startAtMillis != null,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Leader removes a member.
     *
     * The removed member is told — their next sync 403s and the client says "You're no longer in
     * this group." Silent removal would be its own dishonesty, and §5.1's whole posture is that
     * people know where they stand.
     *
     * Forces a roster refetch on the next sync by clearing it locally: the relay only sends the
     * roster when the caller's rev is stale, and the removal bumps rev server-side — but waiting
     * for that round trip would leave the removed member visible in the leader's own list.
     */
    suspend fun removeMember(uid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _state.value
        val groupId = session.groupId ?: return@withContext Result.failure(Exception("Not in a group."))
        if (!session.isLeader) return@withContext Result.failure(Exception("Only the leader can do that."))
        try {
            post(
                AppConfig.GROUP_REMOVE_ENDPOINT,
                JSONObject().put("groupId", groupId).put("uid", uid).toString(),
            )
            _state.value = _state.value.copy(
                roster = _state.value.roster.filterNot { it.uid == uid },
                positions = _state.value.positions.filterNot { it.uid == uid },
            )
            // Count only, after the roster has shrunk. Never the uid — §9 forbids any member
            // relationship, and who removed whom is precisely that.
            AnalyticsManager.trackGroupMemberRemoved(_state.value.roster.size)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun setState(next: String): Result<Unit> = withContext(Dispatchers.IO) {
        val groupId = _state.value.groupId ?: return@withContext Result.failure(Exception("Not in a group."))
        try {
            post(
                AppConfig.GROUP_STATE_ENDPOINT,
                JSONObject().put("groupId", groupId).put("state", next).toString(),
            )
            if (next == "LIVE") {
                _state.value = _state.value.copy(status = GroupSessionStatus.LIVE)
                AnalyticsManager.trackGroupStarted(_state.value.roster.size)
            }
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
        isSelfRiding = riding
        trackDestinationProgress(lat, lng, speedMps)
        val key = groupKey
        val uid = currentUid()
        if (key == null || uid == null || lat == null || lng == null) {
            pendingPosition = null
            markSharing(false)
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
        markSharing(pendingPosition != null)
    }

    /**
     * Feeds one fix to the destination estimator and emits the calibration sample on arrival.
     *
     * Runs regardless of [GroupFeatureFlags.SHOW_ETA] — the flag gates the *display*, not the
     * measurement (§2.9). Nothing here reaches the UI in 1.7.x.
     */
    private fun trackDestinationProgress(lat: Double?, lng: Double?, speedMps: Float?) {
        val session = _state.value
        val destLat = session.destinationLat
        val destLng = session.destinationLng
        if (lat == null || lng == null || destLat == null || destLng == null) {
            destinationProgress = null
            return
        }
        val progress = destinationProgress
            ?: `in`.shvms.trackme.domain.group.DestinationProgress(destLat, destLng, currentPersona)
                .also { destinationProgress = it }

        val sample = progress.onPosition(
            lat = lat,
            lng = lng,
            speedMps = speedMps?.toDouble(),
            nowMillis = System.currentTimeMillis(),
        )
        // §2.9: two durations and a persona. No coordinates, no destination, no group identity.
        if (sample != null) AnalyticsManager.trackGroupEtaCalibration(sample)
    }

    /** Set by `TrackingService`, which is the only thing that knows the ride's persona. */
    @Volatile var currentPersona: String? = null

    /**
     * Tracks whether we actually have something to send, so §8's honest banner can exist.
     *
     * Only meaningful while in a group — outside one, "not sharing" is not a warning, it is the
     * normal state.
     */
    // --- Rider status (§2.4, §3.7) ---------------------------------------------------------------

    /**
     * Turns a sync response into at most one signal per member (§5.2, §3.8).
     *
     * Deliberately here rather than in the UI: the sync loop runs in the tracking service, which is
     * alive with the screen off — and a status nobody notices while riding is decoration.
     */
    private fun evaluateAlerts(result: GroupWire.SyncResult, selfUid: String, receivedAt: Long) {
        val session = _state.value
        val incoming = result.statuses.associateBy { it.uid }
        val sinceJoin = if (session.sessionStartedElapsed > 0L) {
            receivedAt - session.sessionStartedElapsed
        } else {
            Long.MAX_VALUE
        }

        val seen = mutableSetOf<String>()
        for (entry in session.roster) {
            val memberUid = entry.uid
            seen += memberUid
            val currentCode = incoming[memberUid]?.code
            val previousCode = lastSeenStatus[memberUid]
            if (currentCode == previousCode) continue

            val current = currentCode?.let { RiderStatusCodec.parse(it) }
            val previous = previousCode?.let { RiderStatusCodec.parse(it) }

            // Freshness of their POSITION, not of their status — an alert riding on a
            // four-minute-old fix is history, not news.
            val position = result.positions.firstOrNull { it.uid == memberUid }
            val stale = position == null || result.serverNowMillis <= 0L ||
                (result.serverNowMillis - position.serverTsMillis) >=
                (session.syncIntervalSec.coerceAtLeast(1) * 2L * 1000L)

            val signal = AlertPolicy.signalFor(
                AlertPolicy.Input(
                    memberUid = memberUid,
                    selfUid = selfUid,
                    previous = previous,
                    current = current,
                    raisedForPrevious = memberUid in alertRaisedFor,
                    senderStale = stale,
                    muted = alertsMuted,
                    millisSinceJoin = sinceJoin,
                ),
            )

            when (signal) {
                AlertPolicy.Signal.ALERT_RAISED -> {
                    alertRaisedFor += memberUid
                    AnalyticsManager.trackGroupAlert("shown")
                }
                AlertPolicy.Signal.ALERT_RESOLVED -> alertRaisedFor -= memberUid
                AlertPolicy.Signal.NONE -> Unit
            }
            lastSeenStatus[memberUid] = currentCode

            if (signal != AlertPolicy.Signal.NONE) {
                val name = entry.displayName ?: entry.initials ?: ""
                val code = (current ?: previous)?.code.orEmpty()
                onAlertSignal?.invoke(signal, name, code)
            }
        }
        // A member who left takes their history with them, so rejoining is a clean slate rather
        // than a suppressed alert.
        lastSeenStatus.keys.retainAll(seen)
        alertRaisedFor.retainAll(seen)
    }

    private fun selfStatusCodeOrNull(): String? = _state.value.selfStatusCode

    /**
     * Re-seals a status restored from disk, so it can be re-sent after process death.
     *
     * This is the one place a re-seal is correct: the envelope bytes did not survive the restart,
     * so there is nothing to resend idempotently — unlike a retry, where re-sealing would make the
     * status get younger every time the network flapped (A34). The age comes from
     * [statusAgeSeconds], which returns null when a reboot voided the monotonic base.
     */
    private fun resealPendingStatus(code: String) {
        val key = groupKey ?: return
        val uid = currentUid() ?: return
        pendingStatusEnvelope = try {
            GroupCrypto.seal(
                key,
                GroupWire.encodeStatus(code, statusAgeSeconds()),
                GroupCrypto.Purpose.Status(uid),
            )
        } catch (e: Exception) {
            null
        }
        if (pendingStatusEnvelope != null) pendingStatusOp = "set"
    }

    /**
     * Sets or replaces this rider's status.
     *
     * Works with no position, no GPS fix, and no location permission — that independence is the
     * entire reason status has its own slot (§4.7), and the rider who cannot share a position is
     * precisely the one most likely to need the alert tier.
     *
     * The envelope is sealed **once, here**, and the same bytes are resent until the relay
     * acknowledges (A34). `stAge` is measured from a monotonic clock, so it is a duration rather
     * than an instant and a skewed device clock cannot distort it.
     */
    fun setStatus(code: String) {
        val key = groupKey ?: return
        val uid = currentUid() ?: return
        if (RiderStatusCodec.parse(code) == null) return

        val now = SystemClock.elapsedRealtime()
        selfStatusSetAtElapsed = now
        selfStatusBootEpoch = System.currentTimeMillis() - now
        pendingStatusEnvelope = try {
            GroupCrypto.seal(key, GroupWire.encodeStatus(code, 0L), GroupCrypto.Purpose.Status(uid))
        } catch (e: Exception) {
            null
        }
        if (pendingStatusEnvelope == null) return

        // O12 / §3.3 — undo beats confirm. The status shows locally at once, but for severity 1 the
        // outbound write is HELD for a few seconds, so a mis-tap never leaves the device and no
        // notification fires that would then have to be un-rung. It costs nothing that matters: the
        // sync interval is ~10s, so the hold sits inside jitter riders already experience, and in
        // the common case adds zero latency because the next sync had not fired yet.
        //
        // Tiers 2 and 3 go immediately — they raise no notification, so there is nothing to buy.
        val parsed = RiderStatusCodec.parse(code)
        val holdMillis = if (parsed?.isAlert == true) ALERT_UNDO_WINDOW_MS else 0L
        pendingStatusOp = if (holdMillis > 0L) "" else "set"
        _state.value = _state.value.copy(selfStatusCode = code, selfStatusAcknowledged = false)
        persistStatus(code)
        parsed?.let { AnalyticsManager.trackGroupStatusSet(it.severity.digit) }

        if (holdMillis > 0L) {
            undoJob?.cancel()
            undoJob = scope.launch {
                delay(holdMillis)
                // Still the same status? Then the rider meant it, and it goes.
                if (_state.value.selfStatusCode == code) pendingStatusOp = "set"
            }
        }
    }

    /**
     * Withdraws a status inside its undo window (§3.7).
     *
     * **This is not a clear.** Nothing was sent, so there is nothing to withdraw from the relay and
     * no resolution to broadcast — the status simply never existed for anyone else.
     */
    fun undoStatus() {
        undoJob?.cancel()
        undoJob = null
        pendingStatusEnvelope = null
        pendingStatusOp = ""
        selfStatusSetAtElapsed = 0L
        selfStatusBootEpoch = 0L
        _state.value = _state.value.copy(selfStatusCode = null, selfStatusAcknowledged = false)
        persistStatus(null)
    }

    /**
     * Clears this rider's status.
     *
     * An explicit relay op, not an absent field — absence has to mean "unchanged" so a status
     * survives syncs that carry nothing new. Until the relay confirms, the UI reads `Clearing…`:
     * a rider who believes they have withdrawn "Need help" while the group still sees it is the
     * same class of failure as one who believes they are sharing when they are not.
     */
    fun clearStatus() {
        undoJob?.cancel()
        undoJob = null
        AnalyticsManager.trackGroupStatusCleared(byUser = true)
        pendingStatusEnvelope = null
        pendingStatusOp = "clear"
        selfStatusSetAtElapsed = 0L
        selfStatusBootEpoch = 0L
        _state.value = _state.value.copy(selfStatusCode = null, selfStatusAcknowledged = false)
        persistStatus(null)
    }

    /**
     * How long this rider has held their status, in whole seconds, or null when that is unknowable.
     *
     * Monotonic clocks survive process death but **reset across reboot**, so a persisted elapsed
     * value is meaningless afterwards. `bootEpoch` (wall clock minus elapsed, stable within a boot)
     * detects that. When it has moved, the honest answer is that we know *what* they said and not
     * *when* — so the age is dropped rather than fabricated, and the status itself is kept, because
     * dropping a "Need help" because we lost its clock would be far worse than either.
     */
    private fun statusAgeSeconds(): Long? {
        if (selfStatusSetAtElapsed <= 0L) return null
        val currentBootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (kotlin.math.abs(currentBootEpoch - selfStatusBootEpoch) > BOOT_EPOCH_TOLERANCE_MS) return null
        return (SystemClock.elapsedRealtime() - selfStatusSetAtElapsed) / 1000L
    }

    private fun persistStatus(code: String?) {
        val record = store.load() ?: return
        store.save(
            record.copy(
                statusCode = code,
                statusSetAtElapsed = selfStatusSetAtElapsed,
                statusBootEpoch = selfStatusBootEpoch,
            ),
        )
    }

    private fun markSharing(sharing: Boolean) {
        val current = _state.value
        if (current.isActive && current.isSharingPosition != sharing) {
            _state.value = current.copy(isSharingPosition = sharing)
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
                    finish(GroupEndReason.EXPIRED)
                    break
                }

                val delayMs = runCatching { syncOnce() }.getOrElse { failure ->
                    val status = (failure as? GroupHttpException)?.statusCode
                    if (!GroupBackoff.isRetryable(status)) {
                        // 403 = removed from the group, 404 = the group is gone. Both are state
                        // changes the user needs to SEE — the whole point of the notice.
                        finish(if (status == 403) GroupEndReason.REMOVED else GroupEndReason.ENDED)
                        return@launch
                    }
                    val failures = _state.value.consecutiveFailures + 1
                    if (_state.value.status != GroupSessionStatus.DEGRADED) {
                        // Only on the transition. §8 has clients absorb an outage with backoff, so
                        // without this a relay outage every client handled gracefully would never
                        // appear in the §9 ops metrics — but one event per retry would drown them.
                        AnalyticsManager.trackGroupDegraded(failures)
                    }
                    _state.value = _state.value.copy(
                        status = GroupSessionStatus.DEGRADED,
                        consecutiveFailures = failures,
                        degradedSince = _state.value.degradedSince ?: System.currentTimeMillis(),
                        // Names the pill's explanation only, never whether there is a problem —
                        // that is decided by the sync having gone unanswered (§4.5).
                        lastSyncFailureKind = failureKindFor(failure),
                    )
                    GroupBackoff.delayMillis(failures)
                }
                if (delayMs <= 0L) break
                delay(delayMs)
            }
        }
    }

    /**
     * Which sentence the Home pill uses. §4.5: the cause selects the remedy — "check your signal"
     * versus "wait, it's ours" — and getting it backwards blames the rider for our outage.
     */
    private fun failureKindFor(failure: Throwable): GroupPresencePolicy.FailureKind {
        val status = (failure as? GroupHttpException)?.statusCode
        return when {
            status == 401 || status == 403 -> GroupPresencePolicy.FailureKind.AUTH
            status != null && status >= 500 -> GroupPresencePolicy.FailureKind.SERVICE_UNAVAILABLE
            failure is GroupWire.WireException -> GroupPresencePolicy.FailureKind.PROTOCOL
            failure is java.io.IOException -> GroupPresencePolicy.FailureKind.NO_INTERNET
            else -> GroupPresencePolicy.FailureKind.SERVICE_UNAVAILABLE
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
            // THE ROSTER BUG. The relay only sends the roster when the client's rev is STALE
            // (§4.5, to keep the hot path near §7.3's 1.5 KB budget) — but `join` hands back the
            // server's CURRENT rev, so the first sync after joining reported an up-to-date
            // revision and the relay correctly sent nothing. The roster then stayed empty forever,
            // until some unrelated member happened to join and bump it.
            //
            // Result: a joiner saw nobody, and a creator saw not even themselves. Asking by the
            // roster we actually hold, rather than by the revision we were told, is self-correcting
            // — it also recovers a roster lost to a restore, a decrypt failure, or a dropped sync.
            put("rev", if (current.roster.isEmpty()) FORCE_ROSTER_REV else current.rev)
            // §4.7: an empty op means "unchanged", which is why clearing has to be explicit rather
            // than an absent field — a status must survive every sync that carries no new one.
            if (pendingStatusOp.isNotEmpty()) {
                put("statusOp", pendingStatusOp)
                pendingStatusEnvelope?.let { put("status", it) }
            }
        }

        val result = GroupWire.parseSync(post(AppConfig.GROUP_SYNC_ENDPOINT, body.toString()), key, uid)

        if (result.state == "ENDED") {
            finish()
            return 0L
        }

        store.updateRev(result.rev)
        val receivedAt = SystemClock.elapsedRealtime()

        // A13's echo, finally read. The relay returns our own entry deliberately — it is the only
        // proof a client has that its push landed — and 1.7.0 discarded it. Comparing what came back
        // with what we sent is what separates "the relay is reachable" from "the relay took my
        // position", which the Home pill and the self roster row need to tell apart (§4.4).
        // A34 makes an unchanged resend keep its original relay timestamp, so "the relay echoed a
        // position back" is NOT the same as "the relay accepted a new one". Only a timestamp that
        // actually moved proves the group has something newer about us — anything looser would put
        // the frozen-GPS lie back on the self row after we just removed it from the map.
        val ownTs = result.ownPosition?.serverTsMillis ?: 0L
        val positionAccepted = ownTs > 0L && ownTs > lastOwnPositionServerTs
        if (positionAccepted) lastOwnPositionServerTs = ownTs
        val statusAcknowledged = when {
            pendingStatusOp == "clear" -> result.ownStatus == null
            pendingStatusOp == "set" -> result.ownStatus?.code == RiderStatusCodec.parse(
                selfStatusCodeOrNull(),
            )?.code
            else -> current.selfStatusAcknowledged || result.ownStatus != null
        }
        // Stop retrying only once the relay agrees with us. Until then the same bytes go again.
        if (statusAcknowledged) {
            pendingStatusOp = ""
            pendingStatusEnvelope = null
        }

        evaluateAlerts(result, uid, receivedAt)

        _state.value = current.copy(
            status = statusFor(result.state),
            expiresAtMillis = result.expiresAtMillis.takeIf { it > 0 } ?: current.expiresAtMillis,
            rev = result.rev,
            maxMembers = result.maxMembers,
            positions = result.positions,
            // A rev-gated roster arrives only when it changed; keep the last one otherwise.
            roster = result.roster ?: current.roster,
            groupName = result.meta?.name ?: current.groupName,
            // Wholesale when meta arrives, not field-by-field with `?:`. Clearing a destination
            // has to propagate, and an elvis would have kept the old one forever — a group told to
            // meet somewhere that is no longer the plan.
            destinationLat = if (result.meta != null) result.meta.destLat else current.destinationLat,
            destinationLng = if (result.meta != null) result.meta.destLng else current.destinationLng,
            startAtMillis = if (result.meta != null) result.meta.startAtMillis else current.startAtMillis,
            syncIntervalSec = result.nextSyncInSec.coerceAtLeast(1),
            consecutiveFailures = 0,
            degradedSince = null,
            statuses = result.statuses,
            selfStatusAcknowledged = statusAcknowledged,
            serverNowMillis = result.serverNowMillis,
            syncReceivedAtElapsed = receivedAt,
            lastSuccessfulSyncElapsed = receivedAt,
            lastOwnPositionAckElapsed = if (positionAccepted) receivedAt else current.lastOwnPositionAckElapsed,
            lastSyncFailureKind = null,
        )
        return GroupBackoff.nextDelayMillis(0, result.nextSyncInSec)
    }

    /** The group ended, expired, or we were removed. Terminal, and the same for all three. */
    private fun finish(reason: GroupEndReason = GroupEndReason.ENDED) {
        val session = _state.value
        // Only worth telling someone about a group they were actually in.
        if (session.isActive) {
            _endNotice.value = GroupEndNotice(reason = reason, rideStillRecording = isSelfRiding)
        }
        emitSessionMetrics(session, leftDeliberately = false, reason = reason.name.lowercase())
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
        // No notice: leaving and ending are deliberate acts. Telling someone what they just did is
        // noise, and §3.5 wants leaving to be "neutral and unremarkable in tone".
        _endNotice.value = null
        syncJob?.let { runCatching { it.cancelAndJoin() } }
        syncJob = null
        store.clear()
        clearLocal()
    }

    /**
     * §9's co-presence minutes and its safety counter-metrics, emitted once per session end.
     *
     * `group_left` is a **safety** metric: §9 is explicit that heavy use of the exit is healthy and
     * that nobody should be tasked with reducing it. It carries seconds-in-group so
     * time-to-first-leave is derivable.
     */
    private fun emitSessionMetrics(
        session: GroupSessionState,
        leftDeliberately: Boolean,
        reason: String = "left",
    ) {
        if (!session.isActive || session.joinedAtMillis <= 0L) return
        val seconds = ((System.currentTimeMillis() - session.joinedAtMillis) / 1000L).toInt()
        if (seconds <= 0) return
        AnalyticsManager.trackGroupCoPresence(
            minutes = seconds / 60,
            memberCount = session.roster.size,
        )
        if (leftDeliberately) {
            AnalyticsManager.trackGroupLeft(secondsInGroup = seconds, wasLeader = session.isLeader)
        } else {
            AnalyticsManager.trackGroupEnded(
                secondsAlive = seconds,
                memberCount = session.roster.size,
                reason = reason,
            )
        }
    }

    private fun clearLocal() {
        inviteToken = null
        groupKey = null
        pendingPosition = null
        _state.value = GroupSessionState()
    }

    /** Set by the UI so the server can pick the right cadence (§7.1). */
    @Volatile var isForeground: Boolean = false

    /**
     * Whether *we* are recording a ride, set by `TrackingService` alongside each position.
     *
     * The Community roster derives every other member's status from their position envelope, but
     * our own is filtered out of the sync response by design (§4.1) — so our row reads from here
     * rather than from what came back.
     */
    @Volatile var isSelfRiding: Boolean = false
        private set

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

    /**
     * Records the failure, then builds the Result the caller returns — so the reason sits next to
     * the condition that produced it and the two cannot drift apart.
     */
    private fun failJoin(
        reason: GroupJoinFailure,
        viaCode: Boolean,
        error: () -> Exception,
    ): Result<GroupSessionState> {
        AnalyticsManager.trackGroupJoinFailed(reason, viaCode)
        return Result.failure(error())
    }

    /**
     * Maps a thrown failure onto the closed analytics vocabulary.
     *
     * Relay codes pass through by name so a spike reads directly against the server's own logs.
     * The exception *message* is deliberately never inspected: it is prose, it gets translated,
     * and it could carry server text into an analytics property.
     */
    private fun classifyJoinFailure(e: Exception): GroupJoinFailure = when {
        e is GroupSignedOutException -> GroupJoinFailure.SIGNED_OUT
        e is GroupHttpException -> when (e.code) {
            "GROUP_FULL" -> GroupJoinFailure.GROUP_FULL
            "GROUP_NOT_FOUND" -> GroupJoinFailure.GROUP_NOT_FOUND
            "JOIN_RATE_LIMITED" -> GroupJoinFailure.JOIN_RATE_LIMITED
            else -> GroupJoinFailure.UNKNOWN
        }
        // Covers UnknownHost/SocketTimeout/etc — the request never got an answer.
        e is java.io.IOException -> GroupJoinFailure.NETWORK
        else -> GroupJoinFailure.UNKNOWN
    }

    private fun sealRoster(key: ByteArray, displayName: String?, photoUrl: String?): String {
        val uid = currentUid() ?: throw GroupSignedOutException()
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
        /** No real revision is negative, so this always reads as stale and forces a roster send. */
        const val FORCE_ROSTER_REV = -1

        /**
         * How far the derived boot epoch may drift before the monotonic base is treated as void.
         *
         * Generous on purpose: NTP corrections of a second or two are routine and must not be
         * mistaken for a reboot, while an actual reboot moves this by the length of the uptime.
         */
        const val BOOT_EPOCH_TOLERANCE_MS = 10_000L

        /**
         * How long a severity-1 status is held before it is allowed onto the wire (O12).
         *
         * Well inside the ~10s sync interval, so it is invisible in the common case and buys a
         * mis-tap that never reaches another device.
         */
        const val ALERT_UNDO_WINDOW_MS = 4_000L

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
