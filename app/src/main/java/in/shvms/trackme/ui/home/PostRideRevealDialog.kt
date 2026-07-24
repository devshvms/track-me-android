package `in`.shvms.trackme.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.stats.Reveal
import `in`.shvms.trackme.domain.stats.RevealKind
import `in`.shvms.trackme.ui.components.AchievementBadge
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale

/**
 * B1 — the post-ride reveal surface. A single, calm, gain-framed celebration shown once when
 * Home is foreground after a good ride is saved. Replaces the flat "Ride saved" toast.
 *
 * Design guardrails:
 *  - Bounded set only ([RevealKind]); never random/slot-machine (trust for a safety app).
 *  - Brand tokens only (`colorScheme`), so it inherits C1 cyan + dark-first automatically.
 *  - Genuine achievements ([RevealKind.FIRST_RIDE]/PR/[RevealKind.MILESTONE]) get the animated
 *    [AchievementBadge]; an ordinary good ride ([RevealKind.DEFAULT]) stays a small, calm,
 *    static icon — the badge is reserved for things that are actually earned, never routine.
 *  - Copy is localized (all 6 languages); numbers are formatted here, not stored.
 *  - Dismiss is the only action; it never blocks or repeats.
 */
@Composable
fun PostRideRevealDialog(
    reveal: Reveal,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val title = revealTitle(reveal, strings)
    val body = revealBody(reveal, strings)
    val icon = revealIcon(reveal.kind)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 0.dp,
        icon = {
            if (reveal.kind == RevealKind.DEFAULT) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                AchievementBadge(icon = icon, size = 112.dp)
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Merge title+body for a single, clean screen-reader announcement.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clearAndSetSemantics { contentDescription = "$title. $body" }
            )
        },
        confirmButton = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(strings.revealDismiss)
                }
            }
        }
    )
}

private fun revealIcon(kind: RevealKind): ImageVector = when (kind) {
    RevealKind.FIRST_RIDE -> Icons.Filled.AutoAwesome
    RevealKind.DISTANCE_PR -> Icons.Filled.EmojiEvents
    RevealKind.DURATION_PR -> Icons.Filled.MilitaryTech
    RevealKind.MILESTONE -> Icons.Filled.WorkspacePremium
    RevealKind.DEFAULT -> Icons.Filled.CheckCircle
}

private fun revealTitle(reveal: Reveal, s: AppStrings): String = when (reveal.kind) {
    RevealKind.FIRST_RIDE -> s.revealFirstRideTitle
    RevealKind.DISTANCE_PR -> s.revealDistancePrTitle
    RevealKind.DURATION_PR -> s.revealDurationPrTitle
    RevealKind.MILESTONE -> String.format(
        Locale.getDefault(), s.revealMilestoneTitle, reveal.milestoneRideCount ?: reveal.totalRides
    )
    RevealKind.DEFAULT -> s.revealDefaultTitle
}

private fun revealBody(reveal: Reveal, s: AppStrings): String = when (reveal.kind) {
    RevealKind.FIRST_RIDE -> s.revealFirstRideBody
    RevealKind.DISTANCE_PR -> String.format(
        Locale.getDefault(), s.revealDistancePrBody, formatKm(reveal.distanceMeters)
    )
    RevealKind.DURATION_PR -> String.format(
        Locale.getDefault(), s.revealDurationPrBody, formatDuration(reveal.durationMillis)
    )
    RevealKind.MILESTONE -> s.revealMilestoneBody
    RevealKind.DEFAULT -> String.format(
        Locale.getDefault(), s.revealDefaultBody,
        formatKm(reveal.distanceMeters), formatDuration(reveal.durationMillis)
    )
}

private fun formatKm(meters: Double): String =
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)

/** Compact "1h 20m" / "45m" duration; parity with iOS's reveal formatting. */
private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
    else String.format(Locale.getDefault(), "%dm", minutes)
}
