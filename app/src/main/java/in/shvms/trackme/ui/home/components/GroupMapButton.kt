package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale

/**
 * The Group Ride control on Home — a map control circle alongside recentre and compass, shown only
 * while a group is live. Tapping it opens the Community tab.
 *
 * **This replaces the persistent ribbon §3.2.1 specified** (product decision, 2026-08-08, recorded
 * as A20). §3.2 called the ribbon "the primary signal… always visible… answers 'who can see me
 * right now' without the user going looking", and a map control is quieter than that. The trade is
 * deliberate: the ribbon consumed permanent vertical space on the one screen that is mostly map,
 * and Home is where a rider actually looks while riding.
 *
 * What is preserved, because §5.1.7 and the research requirement ("a live session must be visible,
 * expiring, and revocable at all times") are not decoration:
 *
 * - **Visible.** The control only exists while a group is live, is tinted with the brand accent
 *   rather than the neutral chrome of its neighbours, and carries the live member count as a badge.
 *   Its presence *is* the signal.
 * - **Expiring.** The remaining time is in the accessibility description, and one tap away in full.
 * - **Revocable.** Leave is two taps from anywhere — this control, or the Community tab that is
 *   always in the bottom bar, then Leave. §5.1.3 asks for one tap from the ribbon; with no ribbon,
 *   two taps from every screen is the honest cost, and it is recorded rather than glossed.
 *
 * The TalkBack description is a single sentence built from the same tested pure functions the
 * ribbon used, so a screen-reader user gets the whole state — group, audience, time left — without
 * opening anything.
 */
@Composable
fun GroupMapButton(
    session: GroupSessionState,
    /**
     * Everyone in the group, **including you** — this is a headcount, and a group of two that reads
     * "1" looks like a bug rather than a definition.
     */
    memberCount: Int,
    /**
     * How many people can see you — everyone **except** you.
     *
     * Deliberately a second parameter rather than a derivation of [memberCount]: they are genuinely
     * different numbers, and §5.1.7 wants the spoken consent sentence to state the audience, not the
     * headcount. Sharing one value made the badge and TalkBack disagree about which was meant.
     */
    audienceCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!session.isActive) return

    val strings = LocalAppStrings.current
    val remaining = session.expiresAtMillis - System.currentTimeMillis()
    val summary = groupPresenceSummary(
        session = session,
        memberCount = audienceCount,
        remainingMillis = remaining,
        timeLeftFormat = strings.groupTimeLeft,
        visibleUntilFormat = strings.groupVisibleUntil,
    )

    Box(
        // One merged node with the whole sentence — the badge and icon are decorative, and a
        // screen reader announcing "3" on its own would be noise (§3.6).
        modifier = modifier.clearAndSetSemantics { contentDescription = summary },
    ) {
        MapControlCircleButton(
            icon = Icons.Default.Group,
            contentDescription = summary,
            onClick = onClick,
            // §3.1/§3.2: depth and the one locked accent, never a second hue. The tint is what
            // separates "you are sharing right now" from the neutral map chrome beside it.
            iconTint = if (session.status == GroupSessionStatus.DEGRADED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        if (memberCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = memberCount.coerceAtMost(9).toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// --- Pure text, shared with the Community header and testable without a composition -------------

/**
 * The single sentence TalkBack reads for the group control.
 *
 * §5.1.7: consent is educational and continuous — it states who can see you and for how long,
 * every time it is read, not once at join.
 */
internal fun groupPresenceSummary(
    session: GroupSessionState,
    memberCount: Int,
    remainingMillis: Long,
    timeLeftFormat: String,
    visibleUntilFormat: String,
): String {
    val name = session.groupName.orEmpty()
    val visible = String.format(
        Locale.getDefault(),
        visibleUntilFormat,
        memberCount.coerceAtLeast(0),
        formatClock(session.expiresAtMillis),
    )
    val left = String.format(Locale.getDefault(), timeLeftFormat, formatRemaining(remainingMillis))
    return listOf(name, visible, left).filter { it.isNotBlank() }.joinToString(". ")
}

/** Compact "N • 57m left", or the honest degraded line (§8 — never a silent failure). */
internal fun groupPresenceSubtitle(
    session: GroupSessionState,
    memberCount: Int,
    remainingMillis: Long,
    timeLeftFormat: String,
    degradedText: String,
): String = if (session.status == GroupSessionStatus.DEGRADED) {
    degradedText
} else {
    "$memberCount • " + String.format(
        Locale.getDefault(),
        timeLeftFormat,
        formatRemaining(remainingMillis),
    )
}

internal fun formatRemaining(millis: Long): String {
    if (millis <= 0) return "0m"
    val hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

internal fun formatClock(epochMillis: Long): String =
    if (epochMillis <= 0) "" else java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(java.util.Date(epochMillis))
