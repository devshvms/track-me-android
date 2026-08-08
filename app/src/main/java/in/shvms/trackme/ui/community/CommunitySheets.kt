package `in`.shvms.trackme.ui.community

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.crypto.GroupCrypto
import `in`.shvms.trackme.ui.localization.AppStrings
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * §2.3 — *"A single sheet, four fields, all with sane defaults so the fast path is
 * name-then-create."*
 *
 * Start time and destination are the two optional fields (D6); they land in GR-13/GR-14. Persona
 * lock is **not** here and never will be — §15.2 cut it outright.
 */
@Composable
fun CreateGroupSheet(
    strings: AppStrings,
    defaultName: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, hours: Int, size: Int) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var hours by remember { mutableIntStateOf(DEFAULT_HOURS) }
    var size by remember { mutableIntStateOf(FREE_MAX_MEMBERS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.groupCreate) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(strings.groupNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                Text(strings.groupDurationLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // §11.2: the free caps are stated positively as a product shape, not hidden
                    // behind a paywall nag. 4 hours is the ceiling (D4), so it is simply the last
                    // option rather than a locked one.
                    for (option in DURATION_OPTIONS) {
                        FilterChip(
                            selected = hours == option,
                            onClick = { hours = option },
                            label = {
                                Text(String.format(Locale.getDefault(), strings.groupHours, option))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text(strings.groupSizeLabel, style = MaterialTheme.typography.labelLarge)
                Text(
                    String.format(Locale.getDefault(), strings.groupSizeValue, size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    strings.groupHowItWorks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.ifBlank { defaultName }, hours, size) },
            ) { Text(strings.groupCreateAction) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/**
 * §2.4 — *"The join sheet shows what the user is agreeing to before they tap."*
 *
 * The consent line is not a disclaimer and not a checkbox: one plain sentence, stating who will
 * see them and for how long, above the button that does it (§5.1.7).
 */
@Composable
fun JoinGroupSheet(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onJoin: (code: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val normalized = GroupCrypto.normalizeJoinCode(code)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.groupJoinWithCode) },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    // Normalisation happens on submit, not per keystroke — rewriting what someone
                    // is typing under their finger is hostile, and O→0 mid-word would look broken.
                    onValueChange = { code = it.take(12) },
                    label = { Text(strings.groupCodeLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    strings.groupJoinConsent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    strings.groupHowItWorks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { normalized?.let(onJoin) },
                enabled = normalized != null,
            ) { Text(strings.groupJoinAction) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

// --- Helpers ------------------------------------------------------------------------------------

const val DEFAULT_HOURS = 4
val DURATION_OPTIONS = listOf(1, 2, 4)

/** D4's free tier. Server-enforced too — this is only what the sheet offers. */
const val FREE_MAX_MEMBERS = 5

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

@Composable
fun defaultGroupName(strings: AppStrings): String {
    val first = FirebaseAuth.getInstance().currentUser?.displayName
        ?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
    return if (first != null) {
        String.format(Locale.getDefault(), strings.groupDefaultName, first)
    } else {
        strings.groupSignedOutTitle
    }
}

/**
 * §2.3 — the share sheet opens with copy written to be dropped into a group chat, *"because that
 * is the highest-intent channel."*
 *
 * The link carries the token in the **fragment** (amendment A6): a token in the path is
 * transmitted and logged, which breaks §10 permanently for that group. The code is included too,
 * because in 1.7.x it is the only path that reaches the app — App Links are deferred to 1.7.1.
 */
fun shareInvite(context: Context, state: CommunityUiState, strings: AppStrings) {
    // §9's funnel: group_created -> invite_sent -> member_joined. invite_sent feeds the
    // north-star k-factor, so without it the growth loop is unmeasurable at exactly the step
    // that defines it. Records that a share sheet opened, never to whom.
    `in`.shvms.trackme.analytics.AnalyticsManager.trackGroupInviteSent(viaCode = true)
    val code = state.session.joinCode ?: return
    val link = AppConfig.GROUP_BASE_URL + AppConfig.GROUP_INVITE_LINK_PREFIX + (state.session.inviteToken ?: "")
    val message = String.format(Locale.getDefault(), strings.groupShareMessage, code, link)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            },
            strings.groupShare,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Stable per-member tint from the uid, so two people are separable at a glance (§3.3). */
/**
 * A member's colour, shared with their map marker.
 *
 * Delegates to [GroupMemberTint] rather than owning a ramp: the roster and the marker each had
 * their own, so the same member rendered in two different colours and the roster taught you
 * nothing about the map.
 */
fun deterministicTint(uid: String): Color = `in`.shvms.trackme.ui.components.GroupMemberTint.colorFor(uid)

fun formatClockTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(java.util.Date(epochMillis))
}
