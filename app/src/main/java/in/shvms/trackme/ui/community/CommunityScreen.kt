package `in`.shvms.trackme.ui.community

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import `in`.shvms.trackme.domain.group.MemberDirections
import `in`.shvms.trackme.ui.home.components.formatRemaining
import `in`.shvms.trackme.domain.group.PresenceAge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
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
    /**
     * SCOPE_1.7.3 §4 — hand Home a member to point at, then switch to it. Distinct from
     * [onOpenHome], which is the group *destination* control and only switches tabs.
     */
    onShowMemberOnMap: (`in`.shvms.trackme.domain.group.MemberFocusPolicy.Focus) -> Unit = {},
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
    val scope = rememberCoroutineScope()
    // §4/Q4.2 explains an un-focusable row here rather than with a Toast, so it matches the
    // group-end notice and the recovery notice already surfaced through this host.
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current

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
    var alertsMuted by remember(state.session.groupId) { mutableStateOf(viewModel.alertsMuted) }

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
                // Rebuilt after device testing: this was a tall card whose text sat left while its
                // only button floated right, with 16dp of its own padding on top of the page's — so it
                // used a third of the screen to say one sentence and lined up with nothing.
                //
                // It is now a single row: icon, sentence, action. Same rhythm as every other card
                // on the page, and it takes the height the sentence actually needs.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            groupEndNoticeText(notice, strings),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = { viewModel.acknowledgeEndNotice() }) {
                            Text(strings.groupNoticeDismiss)
                        }
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
                // §4, Q4.2: every row is tappable. One with no position explains itself rather
                // than going inert — "nothing happens" reads as a broken roster.
                onFocusMember = { member ->
                    when (
                        val outcome = `in`.shvms.trackme.domain.group.MemberFocusPolicy.onRowTapped(
                            uid = member.uid,
                            lastKnownLat = member.lastKnownPosition?.first,
                            lastKnownLng = member.lastKnownPosition?.second,
                        )
                    ) {
                        is `in`.shvms.trackme.domain.group.MemberFocusPolicy.Outcome.ShowOnMap ->
                            onShowMemberOnMap(outcome.focus)
                        `in`.shvms.trackme.domain.group.MemberFocusPolicy.Outcome.ExplainNoPosition ->
                            scope.launch {
                                snackbarHostState.showSnackbar(strings.groupMemberNoPositionYet)
                            }
                    }
                },
                onDirections = { member ->
                    member.lastKnownPosition?.let { (lat, lng) ->
                        AnalyticsManager.trackGroupDirectionsOpened(member.positionAge.telemetryBucket())
                        openDirections(context, lat, lng, strings)
                    }
                },
                onSetStatus = { showStatusPicker = true },
                onToggleMute = {
                    alertsMuted = !alertsMuted
                    viewModel.alertsMuted = alertsMuted
                },
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
    onFocusMember: (RosterMember) -> Unit,
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
            Spacer(Modifier.height(12.dp))
            // Shown to everyone, not just the leader: "where are we going and when" is the group's
            // shared plan, and a member who cannot see it has to ask. Carded like everything else —
            // these were bare text on a background while the rows beneath them were cards, which is
            // what made the page look half-finished.
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    DestinationRow(state.session, strings, onShowOnMap = onShowOnMap)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StartTimeRow(state.session, strings)
                }
            }
            Spacer(Modifier.height(20.dp))
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
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = SeverityAlert,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        strings.groupNeedsTheGroup,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = SeverityAlert,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onToggleMute, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(
                            if (alertsMuted) strings.groupUnmuteAlerts else strings.groupMuteAlerts,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            items(state.needsAttention, key = { "alert-${'$'}{it.uid}" }) { member ->
                RosterCard(
                    member = member,
                    strings = strings,
                    onFocusMember = { onFocusMember(member) },
                    onDirections = onDirections,
                    onRemove = if (state.session.isLeader && !member.isSelf) {
                        { onRemoveMember(member) }
                    } else null,
                    // Your own alert lives here too, and it must stay editable — this omission is
                    // what made "Need help" impossible to clear on device.
                    onEditStatus = if (member.isSelf) onSetStatus else null,
                    statusPending = member.isSelf && !state.session.selfStatusAcknowledged,
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    strings.groupInThisGroup,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        items(state.everyoneElse, key = { it.uid }) { member ->
            RosterCard(
                member = member,
                strings = strings,
                onFocusMember = { onFocusMember(member) },
                onDirections = onDirections,
                // Only the leader, and never against themselves — removing yourself is `leave`,
                // which ends the group for everyone (§8), and routing it through here would end
                // the group by a path whose confirm dialog never said so.
                onRemove = if (state.session.isLeader && !member.isSelf) {
                    { onRemoveMember(member) }
                } else null,
                onEditStatus = if (member.isSelf) onSetStatus else null,
                statusPending = member.isSelf && !state.session.selfStatusAcknowledged,
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Spacer(Modifier.height(24.dp))
            GroupControls(state, strings, onStart, onEnd, onLeave, onShare)
        }
    }
}

@Composable
private fun GroupHeader(state: CommunityUiState, strings: AppStrings) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // The group name is NOT repeated here. It is already the top bar title, and showing it
            // twice — once in the bar, once as a headline directly beneath it — was the first thing
            // that read as unfinished on device.
            //
            // §2.5 leads with the honest summary instead: "visible to N people until HH:MM", which
            // §3.5 requires to state audience and duration together.
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

            // §2.5a: the ride window belongs to the ride. While the group is still assembling there
            // is nothing to count down, and showing the creation-based expiry made it look as
            // though the clock had already been running — which, before A39, it had been.
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.groupTimeLeftLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (state.session.status == GroupSessionStatus.PREPARING) {
                        strings.groupNotStarted
                    } else {
                        String.format(
                            Locale.getDefault(),
                            strings.groupTimeLeft,
                            formatRemaining(state.session.expiresAtMillis - System.currentTimeMillis()),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            state.session.joinCode?.let { code ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            strings.groupCodeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            code,
                            // Monospaced and spaced out: this gets read aloud across a car park,
                            // so the characters have to be individually distinguishable.
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 4.sp,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(strings.groupCodeLabel, code),
                            )
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = strings.groupCodeLabel,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
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
/**
 * One member, as a card.
 *
 * Rewritten after device testing. The previous version was a bare `Row` with a divider, which read
 * as a different app from `HistoryScreen` and `SettingsScreen` — both card-based — and whose height
 * jumped whenever anyone set a status, so the avatar and the trailing action drifted out of line
 * with the name.
 *
 * The fix is a **fixed leading block and a fixed trailing block**, with only the middle column
 * growing. The avatar and the actions are top-aligned to the name rather than centred on a column
 * whose height depends on content, so a row with a status and a row without one still line up.
 */
@Composable
private fun RosterCard(
    member: RosterMember,
    strings: AppStrings,
    onFocusMember: () -> Unit,
    onDirections: (RosterMember) -> Unit,
    onRemove: (() -> Unit)? = null,
    onEditStatus: (() -> Unit)? = null,
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
    // §2.3 (revised): the action survives staleness. It desaturates with the avatar and keeps
    // routing to an explicitly labelled last known point, disappearing only when the relay stops
    // returning a coordinate at all. Hiding it left a rider searching for someone who had stopped
    // with nothing, at precisely the moment they needed it most — and the age on the button says
    // how far to trust the pin.
    val canRoute = member.lastKnownPosition != null && !member.isSelf

    val spoken = listOfNotNull(
        name.takeIf { it.isNotBlank() },
        strings.groupLeaderBadge.takeIf { member.isLeader },
        riderStatusLabel,
        statusText,
        ageText,
    ).joinToString(", ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // §4: the whole row is the affordance, not a trailing button. Q4.2 keeps it tappable
            // even with no position — it explains itself instead of going inert.
            .clickable(onClick = onFocusMember)
            // §3.6 of 1.7.0, A18: one merged node, so TalkBack reads a sentence rather than walking
            // five children. Directions and edit-status are custom ACTIONS on that node.
            //
            // clearAndSetSemantics wipes the clickable's own semantics, so the primary action is
            // re-declared here explicitly — without it the row would be silently un-actionable to
            // TalkBack while working fine by touch, which is the worst of both.
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick(label = strings.groupShowMemberOnMap) { onFocusMember(); true }
                customActions = listOfNotNull(
                    onEditStatus?.let {
                        CustomAccessibilityAction(strings.groupStatusSet) { it(); true }
                    },
                    if (canRoute) {
                        CustomAccessibilityAction(strings.groupDirections) { onDirections(member); true }
                    } else null,
                    onRemove?.let {
                        CustomAccessibilityAction(strings.groupRemoveMember) { it(); true }
                    },
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            // Top, not centre. Centring against a growing column is what made the avatar drift
            // down as soon as a member set a status.
            verticalAlignment = Alignment.Top,
        ) {
            MemberAvatar(member)
            Spacer(Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name.ifBlank { strings.groupStatusNoLocation },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (member.isSelf) FontWeight.SemiBold else FontWeight.Normal,
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

                // One line, always present, so every card has the same second row whether or not
                // anyone has set a status.
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        statusText,
                        ageText?.takeIf { !(member.isSelf && member.positionAge == PresenceAge.Bucket.Now) }
                            ?.let { age ->
                                if (member.isSelf) {
                                    String.format(Locale.getDefault(), strings.groupLastShared, age)
                                } else {
                                    age
                                }
                            },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (member.status) {
                        MemberStatus.RIDING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                if (riderStatus != null && riderStatusLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    StatusChip(
                        label = riderStatusLabel,
                        severity = riderStatus.severity,
                        age = if (statusPending) strings.groupStatusNotSent
                        else strings.statusAgeText(member.riderStatusAge),
                        dimmed = member.status == MemberStatus.NO_RECENT_LOCATION,
                        // THE BUG FOUND ON DEVICE. Your own chip must always reopen the picker.
                        // Previously the affordance was an `else` branch to the chip, so the moment
                        // you set anything it vanished — and a severity-1 status put you in the
                        // attention section, which passed no callback at all. Between them, "Need
                        // help" could not be withdrawn from anywhere in the app.
                        onClick = onEditStatus,
                    )
                } else if (onEditStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = onEditStatus,
                        label = { Text(strings.groupStatusSet, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }

            // Fixed-width trailing block, so cards with and without actions align down the page.
            Row(verticalAlignment = Alignment.Top) {
                if (canRoute) {
                    IconButton(
                        onClick = { onDirections(member) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            // Null: the card owns the description, and a second announced node
                            // would make TalkBack read every member twice.
                            contentDescription = null,
                            // Desaturated with the avatar when the fix is old — still offered,
                            // visibly less trustworthy.
                            tint = if (member.positionIsFresh) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                        )
                    }
                }
                onRemove?.let {
                    IconButton(onClick = it, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.PersonRemove,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
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
