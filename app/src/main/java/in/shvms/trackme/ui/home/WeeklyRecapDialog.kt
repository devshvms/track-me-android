package `in`.shvms.trackme.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.stats.WeeklyRecap
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale

/**
 * B2 weekly recap card — a calm, Wrapped-style "here's what you did" ritual for a completed
 * week, plus the B3 streak line. STRICTLY gain-framed: it only ever appears when there is
 * something to celebrate (rides > 0) and never shows a loss/at-risk/comparison message.
 *
 * Brand tokens only (`colorScheme`); Dynamic Type + TalkBack friendly; no forced animation.
 */
@Composable
fun WeeklyRecapDialog(
    recap: WeeklyRecap,
    onDismiss: () -> Unit,
    imperial: Boolean = false
) {
    val strings = LocalAppStrings.current
    val distance = `in`.shvms.trackme.domain.UnitFormatter.distance(recap.distanceMeters, imperial, decimals = 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.weeklyRecapTitle,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatRow(strings.weeklyRecapRidesLabel, recap.rideCount.toString())
                StatRow(strings.weeklyRecapDistanceLabel, distance)

                // B3 streak line — one gain-framed sentence inside the recap. Never a loss.
                val streakText = when {
                    recap.streakWeeks >= 2 ->
                        String.format(Locale.getDefault(), strings.weeklyRecapStreak, recap.streakWeeks)
                    recap.streakWeeks == 1 -> strings.weeklyRecapStreakOne
                    else -> null
                }
                if (streakText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp).padding(4.dp)
                            )
                        }
                        Text(
                            text = streakText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.weeklyRecapDismiss)
            }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
