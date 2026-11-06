package `in`.shvms.trackme.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.dashboardRoutePolylineFromPoints
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.stats.Reveal
import `in`.shvms.trackme.domain.stats.RevealKind
import `in`.shvms.trackme.ui.components.AchievementBadge
import `in`.shvms.trackme.ui.history.RoutePreviewThumbnail
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * B1 — the post-ride reveal surface. A celebratory summary shown once when Home is
 * foreground after a good ride is saved. Overhauled for v1.9.0 (TASK-338) with:
 *  - Auto-rendered Route Card thumbnail preview
 *  - Key metrics strip (Distance, Duration, Avg Speed)
 *  - Primary "Share Trip Card / Video" action
 *  - Secondary "View Trip Details" navigation action
 *  - Bounded set only ([RevealKind]); never random/slot-machine.
 */
@Composable
fun PostRideRevealDialog(
    reveal: Reveal,
    onDismiss: () -> Unit,
    onShareCard: () -> Unit = onDismiss,
    onViewDetails: () -> Unit = onDismiss,
    imperial: Boolean = false
) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackMeApp
    val strings = LocalAppStrings.current
    val title = revealTitle(reveal, strings)
    val body = revealBody(reveal, strings, imperial)
    val icon = revealIcon(reveal.kind)

    var routePolyline by remember { mutableStateOf<String?>(null) }
    var pointCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(reveal.rideId) {
        withContext(Dispatchers.IO) {
            val r = app.database.rideDao().getRideWithPointsById(reveal.rideId)
            if (r != null) {
                routePolyline = r.ride.dashboardRoutePolyline ?: dashboardRoutePolylineFromPoints(r.points)
                pointCount = r.points.size
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
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
                AchievementBadge(icon = icon, size = 96.dp)
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = "$title. $body" },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Route thumbnail preview card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(125.dp)
                ) {
                    if (pointCount >= 2 && !routePolyline.isNullOrEmpty()) {
                        RoutePreviewThumbnail(
                            routePolyline = routePolyline,
                            pointCount = pointCount,
                            distanceMeters = reveal.distanceMeters,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Route,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Key metrics strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RevealStatChip(
                        label = strings.distance,
                        value = formatKm(reveal.distanceMeters, imperial)
                    )
                    RevealStatChip(
                        label = strings.duration,
                        value = formatDuration(reveal.durationMillis)
                    )
                    val avgSpeedMps = if (reveal.durationMillis > 0) {
                        reveal.distanceMeters / (reveal.durationMillis / 1000.0)
                    } else 0.0
                    RevealStatChip(
                        label = strings.avgSpeed,
                        value = UnitFormatter.speed(avgSpeedMps, imperial)
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onShareCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.revealShareCard,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onViewDetails,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Text(
                            text = strings.revealViewDetails,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Text(
                            text = strings.revealDone,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun RevealStatChip(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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

private fun revealBody(reveal: Reveal, s: AppStrings, imperial: Boolean): String = when (reveal.kind) {
    RevealKind.FIRST_RIDE -> s.revealFirstRideBody
    RevealKind.DISTANCE_PR -> String.format(
        Locale.getDefault(), s.revealDistancePrBody, formatKm(reveal.distanceMeters, imperial)
    )
    RevealKind.DURATION_PR -> String.format(
        Locale.getDefault(), s.revealDurationPrBody, formatDuration(reveal.durationMillis)
    )
    RevealKind.MILESTONE -> s.revealMilestoneBody
    RevealKind.DEFAULT -> String.format(
        Locale.getDefault(), s.revealDefaultBody,
        formatKm(reveal.distanceMeters, imperial), formatDuration(reveal.durationMillis)
    )
}

private fun formatKm(meters: Double, imperial: Boolean): String =
    UnitFormatter.distance(meters, imperial, decimals = 1)

/** Compact "1h 20m" / "45m" duration; parity with iOS's reveal formatting. */
private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
    else String.format(Locale.getDefault(), "%dm", minutes)
}
