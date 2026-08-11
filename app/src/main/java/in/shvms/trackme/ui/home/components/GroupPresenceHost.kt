package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.group.GroupPresencePolicy
import `in`.shvms.trackme.domain.group.PresenceAge
import `in`.shvms.trackme.domain.group.StatusSeverity
import `in`.shvms.trackme.theme.TrackMeAmber
import `in`.shvms.trackme.ui.community.SeverityAlert
import `in`.shvms.trackme.ui.community.ageText
import `in`.shvms.trackme.ui.community.color
import `in`.shvms.trackme.ui.community.glyph
import `in`.shvms.trackme.ui.community.statusLabel
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale

/**
 * What Home tells a rider about their own presence — SCOPE_1.7.2 §3.6, amendments **A28** and
 * **A29**.
 *
 * A dedicated host, rendering whenever the session is active and **independent of
 * `ActiveRideHudPanel`**, which stays gated on a live ride at `HomeScreen.kt:625`. Group presence
 * exists without a ride: §2.6 of 1.7.0 is explicit that stopping a ride does not leave the group,
 * and *"the person who got a flat tyre is exactly the person the group most needs to see"* — that
 * person had no pill row at all before this.
 *
 * Ride-specific pills are deliberately **not** hoisted out of the HUD. They belong to the ride.
 */
@Composable
fun GroupPresenceHost(
    pill: GroupPresencePolicy.Pill,
    strings: AppStrings,
    onOpenCommunity: () -> Unit,
    onClearStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pill is GroupPresencePolicy.Pill.None) return

    val (text, accent, severity) = describe(pill, strings)
    // §3.7: tiers 2-3 clear in one tap from here; tier 1 routes to Community. The tier that
    // interrupts other people is the tier that takes two taps to withdraw — a mis-tapped clear on a
    // pothole silently withdraws something true, and nobody notices.
    val inlineClear = pill is GroupPresencePolicy.Pill.StatusReminder &&
        pill.status.severity != StatusSeverity.ALERT

    Surface(
        modifier = modifier
            .padding(horizontal = 12.dp)
            // Announced without stealing focus, matching the existing revoked-permission surface.
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(14.dp),
        color = accent,
        shadowElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clickable(onClick = onOpenCommunity)
                .padding(start = 10.dp, end = if (inlineClear) 2.dp else 10.dp, top = 5.dp, bottom = 5.dp),
        ) {
            severity?.let {
                Text(
                    it.glyph(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            if (inlineClear) {
                // 24dp glyph inside a 48dp target (§3.4). It must not overlap the pill body's own
                // tap target — one clears a status, the other navigates, and confusing them on a
                // moving bike is the mis-tap this release can least afford.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClearStatus)
                        .clearAndSetSemantics { contentDescription = strings.groupStatusClearAction },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Composes the sentence — §3.6's clause order: ride reassurance, group state, consequence.
 *
 * The reassurance clause appears **only when a ride is actually recording**, because it is only
 * true then. The consequence clause outranks group state for colour: an unsent severity-1 status is
 * the most consequential true thing on screen.
 */
private fun describe(
    pill: GroupPresencePolicy.Pill,
    strings: AppStrings,
): Triple<String, Color, StatusSeverity?> = when (pill) {
    is GroupPresencePolicy.Pill.None -> Triple("", Color.Transparent, null)

    is GroupPresencePolicy.Pill.StatusReminder -> Triple(
        listOfNotNull(
            strings.statusLabel(pill.status),
            strings.ageText(pill.age)?.takeIf { pill.age != PresenceAge.Bucket.Now },
        ).joinToString(" · "),
        pill.status.severity.color(),
        pill.status.severity,
    )

    is GroupPresencePolicy.Pill.StatusUnsent -> Triple(
        strings.statusLabel(pill.status) + " · " + strings.groupStatusNotSent,
        pill.status.severity.color().copy(alpha = 0.75f),
        pill.status.severity,
    )

    is GroupPresencePolicy.Pill.Paused -> Triple(
        listOfNotNull(
            strings.groupPillRideRecording.takeIf { pill.rideRecording },
            when (pill.cause) {
                GroupPresencePolicy.Cause.LOCAL -> strings.groupPillUpdatesPaused
                GroupPresencePolicy.Cause.RELAY -> strings.groupPillRelayUnavailable
            },
            strings.ageText(pill.lastShared)?.let {
                String.format(Locale.getDefault(), strings.groupLastShared, it)
            },
        ).joinToString(" · "),
        TrackMeAmber,
        null,
    )

    is GroupPresencePolicy.Pill.PausedWithUnsentAlert -> Triple(
        listOfNotNull(
            strings.groupPillRideRecording.takeIf { pill.rideRecording },
            strings.groupPillUpdatesPaused,
            String.format(Locale.getDefault(), strings.groupPillNotSent, strings.statusLabel(pill.status)),
        ).joinToString(" · "),
        SeverityAlert,
        pill.status.severity,
    )

    is GroupPresencePolicy.Pill.NotSharing -> Triple(
        listOfNotNull(
            strings.groupNotSharing,
            pill.status?.let { status ->
                val label = strings.statusLabel(status)
                String.format(
                    Locale.getDefault(),
                    if (pill.statusAcknowledged) strings.groupPillSent else strings.groupPillNotSent,
                    label,
                )
            },
        ).joinToString(" · "),
        // A fixed brand red rather than a theme token: `describe` is not composable so it cannot
        // read MaterialTheme, and this is the one pill whose colour never varies with its content.
        SeverityAlert,
        pill.status?.severity,
    )
}
