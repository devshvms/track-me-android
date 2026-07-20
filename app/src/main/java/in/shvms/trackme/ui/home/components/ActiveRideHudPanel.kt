package `in`.shvms.trackme.ui.home.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import `in`.shvms.trackme.theme.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import `in`.shvms.trackme.ui.components.HapticFeedbackUtils.triggerPhysicalVibrate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.service.TrackingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Production-ready heads-up display (HUD) panel rendered at the bottom of the active ride screen.
 *
 * Features:
 * - Real-time metrics display: Distance, Duration, and Speed formatted cleanly.
 * - Interactive slide-to-stop mechanism with acknowledgement animations and physical haptics.
 * - Pause/Resume toggle with smooth scale bounce feedback.
 * - Persona badge and live share status indicator.
 */
@Composable
fun ActiveRideHudPanel(
    trackingState: TrackingState,
    distanceText: String,
    durationText: String,
    speedText: String,
    selectedPersona: RidePersona,
    isAutoPaused: Boolean,
    timeSinceLastGps: Long,
    isEmergencyReady: Boolean,
    isEmergencyPermissionRevoked: Boolean = false,
    sosPermissionRevokedMessage: String = "SOS is off - SMS permission was removed.",
    reEnableSosDescription: String = "Re-enable SOS",
    dismissSosPermissionDescription: String = "Dismiss",
    isEmergencyActive: Boolean,
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    liveShareAuthRequired: String,
    isOffline: Boolean = false,
    onPauseToggle: () -> Unit,

    onStopRide: () -> Unit,
    onTriggerSos: () -> Unit,
    onStopSos: () -> Unit,
    onOpenEmergencySetup: () -> Unit = {},
    onDismissSosPermissionNotice: () -> Unit = {},
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onSendShare: () -> Unit,
    onCopyShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Persistent Info Pills Row (Yellow/Orange/Cyan pills)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Persona Pill (Yellow)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = TrackMeAmber,
                shadowElevation = 2.dp,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = "${selectedPersona.emoji} ${selectedPersona.displayName}",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            if (trackingState == TrackingState.GPS_LOST || trackingState == TrackingState.GPS_DISABLED || trackingState == TrackingState.STORAGE_LOW) {
                val context = LocalContext.current
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TrackMeRed,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable {
                            val settingsAction = if (trackingState == TrackingState.STORAGE_LOW) {
                                android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                            } else {
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                            }
                            context.startActivity(
                                android.content.Intent(settingsAction)
                            )
                        }
                ) {
                    val lostSeconds = (timeSinceLastGps / 1000L).coerceAtLeast(1L)
                    Text(
                        text = if (trackingState == TrackingState.STORAGE_LOW) {
                            "⚠ Storage almost full - free space to resume"
                        } else if (trackingState == TrackingState.GPS_DISABLED) {
                            "⚠ Location services disabled (${lostSeconds}s)"
                        } else {
                            "⚠ GPS signal lost (${lostSeconds}s)"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Auto-Paused / Paused Pill
            if (isAutoPaused) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TrackMeOrange,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "⏸ Auto Paused",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else if (trackingState == TrackingState.PAUSED) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TrackMeOrange,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "⏸ Paused",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Live Share Pill
            if (liveShareState.status == LiveShareStatus.ACTIVE) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CyanBright,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "📡 LiveSharing",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Offline Shield Pill
            if (isOffline) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    // C1: semantic — "shield active" is a positive state, not brand accent.
                    color = SuccessGreen,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "🛡 Offline Shield Active",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }


        Spacer(modifier = Modifier.height(6.dp))

        // 3. Semi-transparent 50% opacity bottom HUD panel
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Stats Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = "DISTANCE", value = distanceText)
                    StatItem(label = "DURATION", value = durationText)
                    StatItem(label = "SPEED", value = speedText)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                if (!isAuthenticated) {
                    Text(
                        text = liveShareAuthRequired,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isEmergencyPermissionRevoked) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = sosPermissionRevokedMessage,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                            )
                            IconButton(onClick = onOpenEmergencySetup) {
                                Icon(Icons.Default.Settings, contentDescription = reEnableSosDescription)
                            }
                            IconButton(onClick = onDismissSosPermissionNotice) {
                                Icon(Icons.Default.Close, contentDescription = dismissSosPermissionDescription)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Action Buttons Row (SOS | Unified Pause/Stop Pill | Live Share)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SOS Button - 52.dp circle
                    SosButton(
                        isReady = isEmergencyReady,
                        isActive = isEmergencyActive,
                        onTrigger = onTriggerSos,
                        onStop = onStopSos,
                        modifier = Modifier.size(52.dp)
                    )

                    // Unified Center Pill (Pause/Resume on left, Slide-to-Stop on right) - 52.dp height
                    val isPaused = trackingState == TrackingState.PAUSED ||
                        trackingState == TrackingState.GPS_LOST ||
                        trackingState == TrackingState.GPS_DISABLED ||
                        trackingState == TrackingState.STORAGE_LOW
                    UnifiedPauseStopPill(
                        isPaused = isPaused,
                        onPauseToggle = onPauseToggle,
                        onStopRide = onStopRide,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp)
                            .height(52.dp)
                    )

                    // Live Location Interactive Share Button (s1 - s5) - 52.dp circle
                    InteractiveShareLocationButton(
                        liveShareState = liveShareState,
                        isAuthenticated = isAuthenticated,
                        onStartShare = onStartShare,
                        onStopShare = onStopShare,
                        onSendShare = onSendShare,
                        onCopyShare = onCopyShare,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SosButton(
    isReady: Boolean,
    isActive: Boolean,
    onTrigger: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val buttonScale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    val inactiveBgColor = TrackMeGrey
    val readyBgColor = TrackMeRed
    val activeBgColor = TrackMeRedLight

    val animatedBgColor by animateColorAsState(
        targetValue = when {
            !isReady -> inactiveBgColor
            isActive -> activeBgColor
            else -> readyBgColor
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "sosBgColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(52.dp)
            .scale(buttonScale.value)
            .clip(CircleShape)
            .background(color = animatedBgColor)
            .clickable(enabled = isReady) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                triggerPhysicalVibrate(context, 45L)
                
                coroutineScope.launch {
                    buttonScale.animateTo(1.15f, tween(120))
                    buttonScale.animateTo(1.0f, tween(150))
                }

                if (isActive) {
                    onStop()
                } else {
                    onTrigger()
                }
            }
    ) {
        Text(
            text = "SOS",
            color = if (isReady) Color.White else Color.Black.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun UnifiedPauseStopPill(
    isPaused: Boolean,
    onPauseToggle: () -> Unit,
    onStopRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val pauseIconScale = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var isStoppingAcknowledged by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    ) {
        val halfWidthDp = maxWidth / 2
        val maxSlidePx = with(density) { halfWidthDp.toPx() }

        // Left side button (Pause / Play)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(halfWidthDp)
                .fillMaxHeight()
                .clickable {
                    if (dragOffset.value >= -10f && !isStoppingAcknowledged) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerPhysicalVibrate(context, 45L)
                        coroutineScope.launch {
                            pauseIconScale.animateTo(0.75f, tween(80))
                            pauseIconScale.animateTo(1f, tween(120))
                        }
                        onPauseToggle()
                    }
                }
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = "Pause / Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .scale(pauseIconScale.value)
            )
        }

        // Right side red sliding Stop pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                .width(halfWidthDp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(TrackMeRed)
                .clickable {
                    if (!isStoppingAcknowledged) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerPhysicalVibrate(context, 35L)
                        coroutineScope.launch {
                            dragOffset.animateTo(-maxSlidePx * 0.45f, animationSpec = tween(180))
                            dragOffset.animateTo(0f, animationSpec = tween(220))
                        }
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        coroutineScope.launch {
                            if (!isStoppingAcknowledged) {
                                val newOffset = (dragOffset.value + delta).coerceIn(-maxSlidePx, 0f)
                                dragOffset.snapTo(newOffset)
                                if (-dragOffset.value >= maxSlidePx * 0.75f) {
                                    isStoppingAcknowledged = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 80L)
                                    dragOffset.animateTo(-maxSlidePx, animationSpec = tween(150))
                                    delay(350)
                                    onStopRide()
                                }
                            }
                        }
                    },
                    onDragStopped = {
                        coroutineScope.launch {
                            if (!isStoppingAcknowledged) {
                                dragOffset.animateTo(0f, animationSpec = tween(250))
                            }
                        }
                    }
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "<<",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Ride",
                        tint = TrackMeRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Full-width Stop Acknowledgement overlay
        if (isStoppingAcknowledged) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(TrackMeRed)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RIDE STOPPED",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
