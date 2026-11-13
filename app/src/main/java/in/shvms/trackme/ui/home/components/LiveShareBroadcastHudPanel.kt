package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.remote.LiveShareManager
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.theme.CyanBright
import `in`.shvms.trackme.theme.LocalTrackMeElevation
import `in`.shvms.trackme.theme.SuccessGreen
import `in`.shvms.trackme.theme.TrackMeRed
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Intent-first broadcast HUD displayed when a user taps "Share Live Location" from Home.
 * Centers viewer count, notified watchers, link sharing, and an encapsulated ride summary row
 * that expands into the full telemetry HUD on tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveShareBroadcastHudPanel(
    trackingState: TrackingState,
    distanceText: String,
    elapsedDurationText: String,
    timeSinceLastGps: Long,
    liveShareState: LiveShareState,
    isOffline: Boolean = false,
    onExpandRideTelemetry: () -> Unit,
    onCopyShare: () -> Unit,
    onSendShare: () -> Unit,
    onStopShareAndRide: () -> Unit,
    onKeepRecordingRide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val elevation = LocalTrackMeElevation.current

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(liveShareState.expiresAt, liveShareState.status) {
        while (liveShareState.status == LiveShareStatus.ACTIVE && liveShareState.expiresAt != null) {
            nowEpochMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val remainingLabel = remember(liveShareState.expiresAt, nowEpochMs) {
        val expiresAt = liveShareState.expiresAt ?: return@remember null
        val remainingSec = Duration.between(Instant.ofEpochMilli(nowEpochMs), expiresAt).seconds.coerceAtLeast(0L)
        val hours = remainingSec / 3600L
        val mins = (remainingSec % 3600L) / 60L
        val secs = remainingSec % 60L
        when {
            hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", hours, mins)
            mins > 0 -> String.format(Locale.getDefault(), "%dm %02ds", mins, secs)
            else -> String.format(Locale.getDefault(), "%ds", secs)
        }
    }

    val context = LocalContext.current
    val isOsPowerSave = remember(context) {
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
    }
    val batteryManager = remember(context) {
        context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    }
    val batteryLevel = remember(batteryManager) {
        batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }
    val isPowerSaveActive = remember(isOsPowerSave, batteryLevel) {
        LiveShareManager.isEffectivePowerSave(isOsPowerSave, batteryLevel)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            BroadcastStatusPill(
                text = strings.hudLiveSharingPill,
                icon = Icons.Default.Sensors,
                containerColor = CyanBright,
                contentColor = Color.Black,
                shadowElevation = elevation.mapOverlay,
            )

            if (isPowerSaveActive) {
                BroadcastStatusPill(
                    text = strings.hudPowerSavingPill,
                    icon = Icons.Default.BatteryAlert,
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.Black,
                    shadowElevation = elevation.mapOverlay,
                    contentDescription = strings.hudPowerSavingA11y,
                )
            }

            remainingLabel?.let { remaining ->
                BroadcastStatusPill(
                    text = remaining,
                    icon = Icons.Default.Schedule,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = elevation.mapOverlay,
                )
            }

            if (isOffline) {
                BroadcastStatusPill(
                    text = strings.hudOfflineShieldPill,
                    icon = Icons.Default.Shield,
                    containerColor = SuccessGreen,
                    contentColor = Color.Black,
                    shadowElevation = elevation.mapOverlay,
                )
            }

            if (trackingState == TrackingState.GPS_LOST ||
                trackingState == TrackingState.GPS_DISABLED ||
                trackingState == TrackingState.STORAGE_LOW
            ) {
                val lostSeconds = (timeSinceLastGps / 1000L).coerceAtLeast(1L)
                val warningText = when (trackingState) {
                    TrackingState.STORAGE_LOW -> strings.hudStorageLowPill
                    TrackingState.GPS_DISABLED -> String.format(
                        Locale.getDefault(),
                        strings.hudLocationDisabledPill,
                        lostSeconds.toString(),
                    )
                    else -> String.format(
                        Locale.getDefault(),
                        strings.hudGpsLostPill,
                        lostSeconds.toString(),
                    )
                }
                BroadcastStatusPill(
                    text = warningText,
                    icon = Icons.Default.WarningAmber,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shadowElevation = elevation.mapOverlay,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            tonalElevation = elevation.level3,
            shadowElevation = elevation.level3,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = if (liveShareState.activeViewers > 0) CyanBright else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        val viewerText = if (liveShareState.activeViewers > 0) {
                            String.format(
                                Locale.getDefault(),
                                strings.liveShareViewersWatching,
                                liveShareState.activeViewers,
                            )
                        } else {
                            strings.liveShareNoViewersYet
                        }
                        Text(
                            text = viewerText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                val notifiedCount = maxOf(
                    liveShareState.notifiedWatchersCount,
                    liveShareState.sharedWatcherNames.size,
                )
                if (notifiedCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = String.format(
                                Locale.getDefault(),
                                strings.liveShareSharedWithCount,
                                notifiedCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (liveShareState.sharedWatcherNames.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            liveShareState.sharedWatcherNames.forEach { name ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                }

                Surface(
                    onClick = onExpandRideTelemetry,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "$distanceText · $elapsedDurationText",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = strings.liveShareEncapsulatedRideHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCopyShare,
                        enabled = liveShareState.shareLink != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.copyLink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = onSendShare,
                        enabled = liveShareState.shareLink != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.shareLink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onStopShareAndRide,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrackMeRed,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.liveShareStopAndSaveRide, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onKeepRecordingRide) {
                        Text(strings.liveShareKeepRecordingRide, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastStatusPill(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    shadowElevation: androidx.compose.ui.unit.Dp,
    contentDescription: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = shadowElevation,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .defaultMinSize(minHeight = 24.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}
