package `in`.shvms.trackme.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.data.remote.GroupSessionManager
import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.data.remote.GroupWire
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A member as the Community roster shows them — SCOPE_1.7.0 §2.5 as amended by **A18**.
 *
 * §2.5 originally made the roster identity-only and called the tab *"a waiting room, not a feed."*
 * A18 replaces that: the roster is live for the whole session and carries each member's current
 * status. That also settles §2.5's contradiction with §3.6, which requires the roster to be *"the
 * accessible equivalent of the map"* and therefore to carry status a TalkBack user cannot get any
 * other way.
 */
data class RosterMember(
    val uid: String,
    val displayName: String?,
    val initials: String?,
    val photoUrl: String?,
    val isLeader: Boolean,
    val isSelf: Boolean,
    val status: MemberStatus,
)

enum class MemberStatus {
    /** Sharing a fresh position, and has started a ride. */
    RIDING,

    /** Sharing a fresh position, but has not set off yet. */
    JOINED_NOT_STARTED,

    /**
     * In the group, but with no fresh position.
     *
     * **Deliberately one state, not two.** A member with no position either revoked location
     * permission (§8) or went quiet long enough for the relay's 10-minute ghost sweep to drop
     * them, and the client cannot tell those apart from a sync response. "Not sharing" would be an
     * accusation; this is simply true either way, and it does not blame someone who has only lost
     * signal.
     */
    NO_RECENT_LOCATION,
}

data class CommunityUiState(
    val signedIn: Boolean = false,
    val session: GroupSessionState = GroupSessionState(),
    val roster: List<RosterMember> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val inGroup: Boolean get() = session.isActive
    val canStart: Boolean
        get() = session.isLeader &&
            session.status == GroupSessionStatus.PREPARING &&
            roster.size >= 2
    /** §8: "A group of one never enters LIVE; there is nothing to be co-present with." */
    val aloneInGroup: Boolean get() = session.isLeader && roster.size < 2
}

class CommunityViewModel(
    private val groupSessionManager: GroupSessionManager,
    private val currentUid: () -> String?,
    private val displayName: () -> String?,
    private val photoUrl: () -> String?,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private data class LocalState(val busy: Boolean = false, val error: String? = null)

    /**
     * Auth state as a flow, not a one-shot read.
     *
     * `currentUid()` inside `combine` only re-evaluates when one of the *other* sources emits — so
     * signing in elsewhere (Settings) left this screen stuck on the signed-out state until
     * something unrelated happened to tick. Since signing in is the one thing the signed-out state
     * asks the user to go and do, that was the whole flow broken.
     */
    private val authState = callbackFlow {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    val uiState: StateFlow<CommunityUiState> =
        combine(groupSessionManager.state, local, authState) { session, localState, uid ->
            CommunityUiState(
                signedIn = uid != null,
                session = session,
                roster = buildRoster(session),
                busy = localState.busy,
                error = localState.error,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CommunityUiState(signedIn = currentUid() != null),
        )

    /**
     * Merges the roster (who is in the group) with the positions (what they are doing).
     *
     * The roster is the authoritative membership list — A19 makes the map a *nearby* view, so a
     * member off-screen or without a fresh fix still has to appear here or they appear nowhere.
     */
    private fun buildRoster(session: GroupSessionState): List<RosterMember> {
        val uid = currentUid()
        val positions: Map<String, GroupWire.MemberPosition> = session.positions.associateBy { it.uid }

        val entries = session.roster.map { entry ->
            RosterMember(
                uid = entry.uid,
                displayName = entry.displayName,
                initials = entry.initials,
                photoUrl = entry.photoUrl,
                isLeader = session.isLeader && entry.uid == uid,
                isSelf = entry.uid == uid,
                status = statusFor(entry.uid, uid, positions),
            )
        }
        // Self first, then everyone else alphabetically — a stable order, so a roster that updates
        // every few seconds does not reshuffle under the reader's finger.
        return entries.sortedWith(compareByDescending<RosterMember> { it.isSelf }
            .thenBy { it.displayName?.lowercase() ?: it.uid })
    }

    private fun statusFor(
        memberUid: String,
        selfUid: String?,
        positions: Map<String, GroupWire.MemberPosition>,
    ): MemberStatus {
        // Our own position is filtered out of `positions` by design (§4.1), so self is derived from
        // what we are actually doing rather than from what came back.
        if (memberUid == selfUid) {
            return if (groupSessionManager.isSelfRiding) MemberStatus.RIDING
            else MemberStatus.JOINED_NOT_STARTED
        }
        val position = positions[memberUid] ?: return MemberStatus.NO_RECENT_LOCATION
        return if (position.riding) MemberStatus.RIDING else MemberStatus.JOINED_NOT_STARTED
    }

    fun createGroup(name: String, durationMinutes: Int, maxMembers: Int) = run {
        local.value = LocalState(busy = true)
        viewModelScope.launch {
            val result = groupSessionManager.createGroup(
                groupName = name,
                durationMinutes = durationMinutes,
                maxMembers = maxMembers,
                displayName = displayName(),
                photoUrl = photoUrl(),
            )
            local.value = LocalState(error = result.exceptionOrNull()?.message)
        }
    }

    fun joinByCode(code: String) {
        local.value = LocalState(busy = true)
        viewModelScope.launch {
            val result = groupSessionManager.joinByCode(code, displayName(), photoUrl())
            local.value = LocalState(error = result.exceptionOrNull()?.message)
        }
    }

    fun startGroup() {
        viewModelScope.launch {
            local.value = LocalState(busy = true)
            val result = groupSessionManager.startGroup()
            local.value = LocalState(error = result.exceptionOrNull()?.message)
        }
    }

    fun endGroup() {
        viewModelScope.launch {
            local.value = LocalState(busy = true)
            groupSessionManager.endGroup()
            local.value = LocalState()
        }
    }

    /**
     * §5.1.3 — leaving is one tap and silent. No confirmation is imposed on a member; the leader
     * gets one only because leaving ends the group for everyone (§8), which is a different fact
     * about a different action, not confirmation guilt.
     */
    fun leaveGroup() {
        viewModelScope.launch {
            local.value = LocalState(busy = true)
            groupSessionManager.leaveGroup()
            local.value = LocalState()
        }
    }

    /** §8's "clear notice" — survives the session going inactive so it can actually be read. */
    val endNotice = groupSessionManager.endNotice

    fun acknowledgeEndNotice() = groupSessionManager.acknowledgeEndNotice()

    fun updateMeta(destLat: Double?, destLng: Double?, startAtMillis: Long?) {
        viewModelScope.launch {
            local.value = LocalState(busy = true)
            val result = groupSessionManager.updateMeta(destLat, destLng, startAtMillis)
            local.value = LocalState(error = result.exceptionOrNull()?.message)
        }
    }

    fun removeMember(uid: String) {
        viewModelScope.launch {
            local.value = LocalState(busy = true)
            val result = groupSessionManager.removeMember(uid)
            local.value = LocalState(error = result.exceptionOrNull()?.message)
        }
    }

    fun clearError() {
        local.value = local.value.copy(error = null)
    }
}

/** Hand-written factory: the app has no DI (§6.2 H5). */
class CommunityViewModelFactory(
    private val groupSessionManager: GroupSessionManager,
    private val currentUid: () -> String?,
    private val displayName: () -> String?,
    private val photoUrl: () -> String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CommunityViewModel(groupSessionManager, currentUid, displayName, photoUrl) as T
}
