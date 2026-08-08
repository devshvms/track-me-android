package `in`.shvms.trackme.ui.community

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material.icons.Icons
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

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

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
                    // Re-sharing the invite is the single action worth promoting out of the body:
                    // §2.5 gives the leader "re-share the link", and a latecomer asking for the
                    // code is the most common thing that happens in a live group.
                    if (state.inGroup) {
                        IconButton(onClick = { shareInvite(context, state, strings) }) {
                            Icon(Icons.Default.Share, contentDescription = strings.groupShare)
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
        when {
            !state.signedIn -> SignedOutState(strings, onNavigateToSignIn)
            state.inGroup -> GroupRoster(
                state = state,
                strings = strings,
                onStart = viewModel::startGroup,
                onEnd = viewModel::endGroup,
                onLeave = viewModel::leaveGroup,
                onShare = { shareInvite(context, state, strings) },
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
            onDismiss = { showJoin = false },
            onJoin = { code ->
                showJoin = false
                viewModel.joinByCode(code)
            },
        )
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
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item {
            GroupHeader(state, strings)
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

        items(state.roster, key = { it.uid }) { member ->
            RosterRow(member, strings)
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
private fun RosterRow(member: RosterMember, strings: AppStrings) {
    val statusText = when (member.status) {
        MemberStatus.RIDING -> strings.groupStatusRiding
        MemberStatus.JOINED_NOT_STARTED -> strings.groupStatusJoined
        MemberStatus.NO_RECENT_LOCATION -> strings.groupStatusNoLocation
    }
    val name = member.displayName ?: member.initials ?: ""
    val spoken = listOfNotNull(
        name.takeIf { it.isNotBlank() },
        strings.groupLeaderBadge.takeIf { member.isLeader },
        statusText,
    ).joinToString(", ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
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
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (member.status) {
                    MemberStatus.RIDING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * §3.3: photo → initials → neutral glyph, and *"member identity must never be conveyed by colour
 * alone."* The name and initials carry it; the tint only separates people at a glance.
 */
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
