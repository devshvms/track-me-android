package `in`.shvms.trackme.ui.community

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.layout.heightIn
import `in`.shvms.trackme.domain.group.MemberDirections
import `in`.shvms.trackme.domain.group.PresenceAge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Community tab — SCOPE_1.7.0 §2.2, §2.5, as amended by **A18**.
 *
 * Three states: signed out, signed in with no group, and in a group. The third is a **live
 * roster for the whole session**, not a pre-start waiting room: it keeps showing who has set off
 * and who has not, for every member, while the ride runs.
 *
 * §3.6 leans on this hard — the roster is the accessible equivalent of the map, and under A19 the
 * map only shows who is *nearby*, so this list is the complete picture of the group. It is a
 * first-class surface, not a fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    onNavigateToSignIn: () -> Unit,
    onOpenHome: () -> Unit = {},
    viewModel: CommunityViewModel = viewModel(
        factory = with(LocalContext.current.applicationContext as TrackMeApp) {
            CommunityViewModelFactory(
                groupSessionManager = groupSessionManager,
                currentUid = { FirebaseAuth.getInstance().currentUser?.uid },
                displayName = { FirebaseAuth.getInstance().currentUser?.displayName },
                photoUrl = { FirebaseAuth.getInstance().currentUser?.photoUrl?.toString() },
            )
        },
    ),
) {
    val state by viewModel.uiState.collectAsStateCompat()
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val app = context.applicationContext as TrackMeApp

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    // Removal is confirmed, unlike leaving. §3.5 keeps leaving free of confirmation guilt because
    // it is your own choice about yourself; removing someone else is a decision about another
    // person, and the dialog says what they will be told.
    var pendingRemoval by remember { mutableStateOf<RosterMember?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var prefilledCode by remember { mutableStateOf<String?>(null) }
    var showStatusPicker by remember { mutableStateOf(false) }
    // §5.2's per-group mute. Session-scoped on purpose: it mutes interruption for this group, and a
    // group is ephemeral by construction — carrying it across groups would silence a future one the
    // rider never chose to silence.
    var alertsMuted by remember(state.session.groupId) { mutableStateOf(false) }

    // An invite that arrived from a shared link (§2.4's growth loop).
    //
    // A token joins outright — it is the real credential, so asking someone to retype a code they
    // never saw would be ceremony. A code opens the join sheet PRE-FILLED rather than joining
    // silently: §2.4 requires the join sheet to state what the user is agreeing to before they
    // tap, and a link that joined on its own would share their location without them ever seeing
    // that sentence.
    val pendingInvite by app.pendingGroupInvite.collectAsStateCompat()
    LaunchedEffect(pendingInvite, state.signedIn, state.inGroup) {
        val invite = pendingInvite ?: return@LaunchedEffect
        if (!state.signedIn || state.inGroup) return@LaunchedEffect
        if (invite.hasToken) {
            viewModel.joinByToken(invite.token!!)
        } else {
            prefilledCode = invite.code
            showJoin = true
        }
        app.consumePendingGroupInvite()
    }

    // A group that ends while the tab is open must close its sheets, or the user is left typing
    // into a group that no longer exists.
    LaunchedEffect(state.session.status) {
        if (state.session.status == GroupSessionStatus.ENDED ||
            state.session.status == GroupSessionStatus.IDLE
        ) {
            showCreate = false
            showJoin = false
        }
    }

    // A real top bar, matching HistoryScreen. Without it the roster ran straight into the status
    // bar, and the tab read as a fragment of a screen rather than a destination.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session.groupName?.takeIf { state.inGroup } ?: strings.navCommunity) },
                actions = {
                    // Edit for the leader, share for everyone else. The leader already has "Share
                    // invite" in the body (§2.5's "re-share the link"), so promoting it twice
                    // spends the one top-bar slot on something they can already reach — whereas
                    // editing the destination and start time had nowhere to live at all.
                    if (state.inGroup) {
                        if (state.session.isLeader) {
                            IconButton(onClick = { showEdit = true }) {
                                Icon(Icons.Default.Edit, contentDescription = strings.groupEdit)
                            }
                        } else {
                            IconButton(onClick = { shareInvite(context, state, strings) }) {
                                Icon(Icons.Default.Share, contentDescription = strings.groupShare)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
    Surface(
        modifier = Modifier.fillMaxSize().padding(padding),
        color = MaterialTheme.colorScheme.background,
    ) {
        // §8's "clear notice". This sits OUTSIDE the when-branches on purpose: by the time a group
        // has ended the session is already inactive, so a notice rendered inside the in-group
        // branch would never be seen — which is exactly how a member ended up watching the map go
        // blank with no explanation.
        val endNotice by viewModel.endNotice.collectAsStateCompat()
        Column(modifier = Modifier.fillMaxSize()) {
            endNotice?.let { notice ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            groupEndNoticeText(notice, strings),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.acknowledgeEndNotice() },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(strings.groupNoticeDismiss) }
                    }
                }
            }
        when {
            !state.signedIn -> SignedOutState(strings, onNavigateToSignIn)
            state.inGroup -> GroupRoster(
                state = state,
                strings = strings,
                onStart = viewModel::startGroup,
                onEnd = viewModel::endGroup,
                onLeave = viewModel::leaveGroup,
                onShare = { shareInvite(context, state, strings) },
                onRemoveMember = { pendingRemoval = it },
                onShowOnMap = onOpenHome,
                onDirections = { member ->
                    member.freshPosition?.let { (lat, lng) -> openDirections(context, lat, lng, strings) }
                },
                onSetStatus = { showStatusPicker = true },
                onToggleMute = { alertsMuted = !alertsMuted },
                alertsMuted = alertsMuted,
            )
            else -> NoGroupState(
                strings = strings,
                busy = state.busy,
                error = state.error,
                onCreate = { showCreate = true },
                onJoin = { showJoin = true },
                onDismissError = viewModel::clearError,
            )
        }
        }
    }

    }
    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            text = {
                Text(
                    String.format(
                        Locale.getDefault(),
                        strings.groupRemoveConfirm,
                        target.displayName ?: target.initials ?: "",
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(target.uid)
                    pendingRemoval = null
                }) { Text(strings.groupRemoveMember, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(strings.cancel) }
            },
        )
    }

    if (showEdit) {
        GroupEditSheet(
            session = state.session,
            strings = strings,
            onDismiss = { showEdit = false },
            onSave = { lat, lng, startAt ->
                viewModel.updateMeta(lat, lng, startAt)
                showEdit = false
            },
            currentLocation = { lastKnownLocation(context) },
        )
    }

    if (showStatusPicker) {
        StatusPickerSheet(
            persona = viewModel.selfPersona,
            currentCode = state.session.selfStatusCode,
            strings = strings,
            onSelect = { code ->
                showStatusPicker = false
                viewModel.setStatus(code)
            },
            onClear = {
                showStatusPicker = false
                viewModel.clearStatus()
            },
            onDismiss = { showStatusPicker = false },
        )
    }

    if (showCreate) {
        CreateGroupSheet(
            strings = strings,
            defaultName = defaultGroupName(strings),
            onDismiss = { showCreate = false },
            onCreate = { name, hours, size ->
                showCreate = false
                viewModel.createGroup(name, TimeUnit.HOURS.toMinutes(hours.toLong()).toInt(), size)
            },
        )
    }
    if (showJoin) {
        JoinGroupSheet(
            strings = strings,
            initialCode = prefilledCode,
            onDismiss = { showJoin = false; prefilledCode = null },
            onJoin = { code ->
                showJoin = false
                prefilledCode = null
                viewModel.joinByCode(code)
            },
        )
    }
}

/**
 * Opens a route preview, never turn-by-turn (§5.3, A30).
 *
 * The https `dir/?api=1` form resolves to whichever app claims maps links, and falls back to a
 * browser on a device with none — rather than to nothing.
 */
private fun openDirections(context: Context, lat: Double, lng: Double, strings: AppStrings) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(MemberDirections.routePreviewUrl(lat, lng))),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, strings.groupNoMapsAppDirections, Toast.LENGTH_SHORT).show()
    }
}

// --- Signed out ---------------------------------------------------------------------------------

/**
 * §2.2: *"a single explanatory state — what Ride Together is, the privacy promise in plain
 * language, and a sign-in CTA. Group membership requires a stable identity; that is a deliberate
 * cost and it must be **explained**, not just enforced."*
 */
@Composable
private fun SignedOutState(strings: AppStrings, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            strings.groupSignedOutTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            strings.groupSignedOutBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignIn) { Text(strings.groupSignIn) }
        Spacer(Modifier.height(32.dp))
        PrivacyPromise(strings)
    }
}

// --- Signed in, no group -------------------------------------------------------------------------

@Composable
private fun NoGroupState(
    strings: AppStrings,
    busy: Boolean,
    error: String?,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            strings.groupSignedOutTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(strings.groupCreate)
            }
            Spacer(Modifier.height(12.dp))
            // §2.4: the code is the guaranteed path, and in 1.7.x the only one — App Links are
            // deferred to 1.7.1 (§15.1).
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
                Text(strings.groupJoinWithCode)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onDismissError) { Text(strings.ok) }
        }

        Spacer(Modifier.height(32.dp))
        PrivacyPromise(strings)
    }
}

/**
 * §2.2: *"This is consent education, not a disclaimer — the research is explicit that young users
 * misread location sharing as care, and that plain-language 'who can see you and for how long' is
 * the differentiator."*
 */
@Composable
private fun PrivacyPromise(strings: AppStrings) {
    Text(
        strings.groupHowItWorks,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// --- In a group: the live roster -------------------------------------------------------------------

@Composable
private fun GroupRoster(
    state: CommunityUiState,
    strings: AppStrings,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onLeave: () -> Unit,
    onShare: () -> Unit,
    onRemoveMember: (RosterMember) -> Unit,
    onShowOnMap: () -> Unit,
    onDirections: (RosterMember) -> Unit,
    onSetStatus: () -> Unit,
    onToggleMute: () -> Unit,
    alertsMuted: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            GroupHeader(state, strings)
            Spacer(Modifier.height(8.dp))
            // Shown to everyone, not just the leader: "where are we going and when" is the group's
            // shared plan, and a member who cannot see it has to ask.
            DestinationRow(state.session, strings, onShowOnMap = onShowOnMap)
            StartTimeRow(state.session, strings)
            Spacer(Modifier.height(16.dp))
        }

        // §8: "Location permission revoked mid-session — stop pushing, stay in the group as a
        // viewer. Honest banner: 'You're not sharing your location. Others can't see you.' —
        // symmetry made visible, not hidden."
        //
        // A member who silently believes they are visible is the single worst way for this feature
        // to be wrong, so this sits above the roster rather than at the bottom of it.
        if (state.inGroup && !state.session.isSharingPosition) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.groupNotSharing,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // §8: "Redis unreachable — 'Group sharing is temporarily unavailable — retrying.' Own ride
        // recording is completely unaffected." Never a silent failure.
        if (state.session.status == GroupSessionStatus.DEGRADED) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.groupDegraded,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (state.aloneInGroup) {
            item {
                Text(
                    strings.groupOnlyOne,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // **A31**: severity-1 members pin above the roster in their own section rather than sorting
        // into it, so A18's stable order is never silently disturbed. The section is absent
        // entirely when nobody is at severity 1 — never an empty "Needs the group" header, which
        // would read as an unanswered question.
        if (state.needsAttention.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        strings.groupNeedsTheGroup,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SeverityAlert,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onToggleMute) {
                        Text(if (alertsMuted) strings.groupUnmuteAlerts else strings.groupMuteAlerts)
                    }
                }
                HorizontalDivider(color = SeverityAlert)
            }
            items(state.needsAttention, key = { "alert-${'$'}{it.uid}" }) { member ->
                RosterRow(member, strings, onDirections, null)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    strings.groupInThisGroup,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        items(state.everyoneElse, key = { it.uid }) { member ->
            RosterRow(
                member = member,
                strings = strings,
                onDirections = onDirections,
                // Only the leader, and never against themselves — removing yourself is `leave`,
                // which ends the group for everyone (§8), and routing it through here would end
                // the group by a path whose confirm dialog never said so.
                onRemove = if (state.session.isLeader && !member.isSelf) {
                    { onRemoveMember(member) }
                } else null,
                onSetStatus = if (member.isSelf) onSetStatus else null,
                statusPending = member.isSelf && !state.session.selfStatusAcknowledged,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item {
            Spacer(Modifier.height(24.dp))
            GroupControls(state, strings, onStart, onEnd, onLeave, onShare)
        }
    }
}

@Composable
private fun GroupHeader(state: CommunityUiState, strings: AppStrings) {
    Column {
        Text(
            state.session.groupName ?: strings.groupSignedOutTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        // §2.5: "Both roles get the same persistent, honest summary: 'You are visible to N people
        // until HH:MM.'" §3.5: always state duration and audience together.
        Text(
            String.format(
                Locale.getDefault(),
                strings.groupVisibleUntil,
                (state.roster.size - 1).coerceAtLeast(0),
                formatClockTime(state.session.expiresAtMillis),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.session.joinCode?.let { code ->
            Spacer(Modifier.height(8.dp))
            Text(
                "${strings.groupCodeLabel}: $code",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One member row.
 *
 * §3.6: *"Roster rows collapse to one spoken sentence."* The whole row is a single merged
 * semantics node, so TalkBack reads "Alice Kaur, leader, riding" rather than walking three
 * separate nodes.
 */
@Composable
private fun RosterRow(
    member: RosterMember,
    strings: AppStrings,
    onDirections: (RosterMember) -> Unit,
    onRemove: (() -> Unit)? = null,
    onSetStatus: (() -> Unit)? = null,
    statusPending: Boolean = false,
) {
    val statusText = when (member.status) {
        MemberStatus.RIDING -> strings.groupStatusRiding
        MemberStatus.JOINED_NOT_STARTED -> strings.groupStatusJoined
        MemberStatus.NO_RECENT_LOCATION -> strings.groupStatusNoLocation
    }
    val name = member.displayName ?: member.initials ?: ""
    val ageText = strings.ageText(member.positionAge)
    val riderStatus = member.riderStatus
    val riderStatusLabel = riderStatus?.let { strings.statusLabel(it) }
    // §2.3: absent, not disabled. A directions button routing to a nine-minute-old point is not a
    // degraded feature, it is a wrong answer, and §3.9's tone rules do not permit shipping one
    // with a caveat under it.
    val canRoute = member.freshPosition != null && !member.isSelf

    // §3.6 of 1.7.0, A18: one merged node, so TalkBack reads a whole sentence rather than walking
    // five children. Directions is a custom ACTION on that node, not a sixth focusable child.
    val spoken = listOfNotNull(
        name.takeIf { it.isNotBlank() },
        strings.groupLeaderBadge.takeIf { member.isLeader },
        riderStatusLabel,
        strings.statusAgeText(member.riderStatusAge)?.let { age -> riderStatusLabel?.let { age } },
        statusText,
        ageText,
    ).joinToString(", ")

    val rowSemantics = Modifier.clearAndSetSemantics {
        contentDescription = spoken
        if (canRoute) {
            customActions = listOf(
                CustomAccessibilityAction(strings.groupDirections) { onDirections(member); true },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .then(rowSemantics),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name.ifBlank { strings.groupStatusNoLocation },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (member.isSelf) FontWeight.Bold else FontWeight.Normal,
                )
                if (member.isLeader) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        strings.groupLeaderBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (riderStatus != null && riderStatusLabel != null) {
                Spacer(Modifier.height(4.dp))
                StatusChip(
                    label = riderStatusLabel,
                    severity = riderStatus.severity,
                    // "Not sent yet" / "Clearing…" outrank the age: a rider who believes they have
                    // been heard when they have not is the failure this whole surface guards against.
                    age = if (statusPending) strings.groupStatusNotSent
                    else strings.statusAgeText(member.riderStatusAge),
                    // Freshness outranks status. A confidently-red chip on a nine-minute-old
                    // position would be the §6.3 defect in a new costume.
                    dimmed = member.status == MemberStatus.NO_RECENT_LOCATION,
                )
            } else if (onSetStatus != null) {
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = onSetStatus,
                    label = { Text(strings.groupStatusSet, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (member.status) {
                        MemberStatus.RIDING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // Your own row says "Last shared" only when it has drifted — "you are fine" is not
                // information, and a permanent counter under your own name is noise (§2.2).
                if (ageText != null && !(member.isSelf && member.positionAge == PresenceAge.Bucket.Now)) {
                    Text(
                        " · " + if (member.isSelf) {
                            String.format(Locale.getDefault(), strings.groupLastShared, ageText)
                        } else {
                            ageText
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (canRoute) {
                TextButton(
                    onClick = { onDirections(member) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        if (ageText != null) {
                            String.format(Locale.getDefault(), strings.groupDirectionsWithAge, ageText)
                        } else {
                            strings.groupDirections
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        onRemove?.let { RemoveMemberAction(strings, it) }
    }
}

/**
 * §3.3: photo → initials → neutral glyph, and *"member identity must never be conveyed by colour
 * alone."* The name and initials carry it; the tint only separates people at a glance.
 */
@Composable
private fun RemoveMemberAction(strings: AppStrings, onRemove: () -> Unit) {
    TextButton(onClick = onRemove) {
        Text(strings.groupRemoveMember, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MemberAvatar(member: RosterMember) {
    val initials = member.initials ?: member.displayName?.firstOrNull()?.uppercase()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color = deterministicTint(member.uid), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials ?: "•",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GroupControls(
    state: CommunityUiState,
    strings: AppStrings,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onLeave: () -> Unit,
    onShare: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.session.status == GroupSessionStatus.PREPARING) {
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text(strings.groupShare)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.session.isLeader) {
            if (state.session.status == GroupSessionStatus.PREPARING) {
                Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(strings.groupStart) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) {
                Text(strings.groupEnd)
            }
        } else {
            // §5.1.3: leaving is one tap, always reachable, and silent. No confirmation, no
            // "are you sure you want to abandon the group" — §3.5 rules out confirmation guilt.
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text(strings.groupLeave)
            }
        }

        if (state.session.isLeader) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { if (confirmLeave) onLeave() else confirmLeave = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // §8: "The leader leaves their own group → ends the group for everyone, and the
                // confirm dialog says exactly that." The one place a confirmation is warranted,
                // because the action does something to other people.
                Text(if (confirmLeave) strings.groupLeaveConfirmLeader else strings.groupLeave)
            }
        }
    }
}
