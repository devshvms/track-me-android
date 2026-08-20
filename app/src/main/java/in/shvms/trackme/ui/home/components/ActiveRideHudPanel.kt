package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.theme.LocalTrackMeElevation
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import `in`.shvms.trackme.theme.*
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.service.TrackingState
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object RideControlAccessibility {
    fun pauseToggleContentDescription(
        isPaused: Boolean,
        strings: `in`.shvms.trackme.ui.localization.AppStrings
    ): String = if (isPaused) strings.resumeTracking else strings.pauseTracking

    fun pauseToggleStateDescription(
        isPaused: Boolean,
        strings: `in`.shvms.trackme.ui.localization.AppStrings
    ): String = if (isPaused) strings.statusPaused else strings.statusRecording

    fun stopContentDescription(strings: `in`.shvms.trackme.ui.localization.AppStrings): String =
        strings.stopTracking

    fun stopStateDescription(
        isStopping: Boolean,
        strings: `in`.shvms.trackme.ui.localization.AppStrings
    ): String = if (isStopping) strings.rideStopped else strings.rideInProgress
}

@Composable
fun ActiveRideHudPanel(
    trackingState: TrackingState,
    distanceText: String,
    durationText: String,
    /** Total wall-clock time since the ride started, including any paused segments. */
    elapsedDurationText: String,
    speedText: String,
    /** Shown instead of [speedText] for personas where [usesPace] holds — walk and run. */
    paceText: String,
    selectedPersona: RidePersona,
    isAutoPaused: Boolean,
    timeSinceLastGps: Long,
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    liveShareAuthRequired: String,
    isOffline: Boolean = false,
    /**
     * True while the group-presence host (A29) is already showing a paused state.
     *
     * §2.5: the green shield is correct about the ride and badly wrong about the group — it
     * reassures at the exact moment the group stopped receiving updates. In Group Mode it is
     * **replaced**, not accompanied; two contradictory pills is worse than either. Outside Group
     * Mode it is unchanged and still correct.
     */
    groupPresencePillShown: Boolean = false,
    onPauseToggle: () -> Unit,

    onStopRide: () -> Unit,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onSendShare: () -> Unit,
    onCopyShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val elevation = LocalTrackMeElevation.current
    val motion = LocalTrackMeMotion.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status pill row. Themed chrome over the map since 1.8.0 — see the notes on each pill.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Persona pill. Was a fixed amber fill — amber is the warning role, and "you are
            // riding as Motorbike" is not a warning; the colour made a neutral fact look like a
            // caution. It is floating chrome over the map, so it now joins the map control
            // buttons on the surface ramp and follows the themed basemap.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = elevation.mapOverlay,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = selectedPersona.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = strings.personaLabel(selectedPersona),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (trackingState == TrackingState.GPS_LOST || trackingState == TrackingState.GPS_DISABLED || trackingState == TrackingState.STORAGE_LOW) {
                val context = LocalContext.current
                // Kept at full `error` emphasis rather than `errorContainer`: this pill is what
                // tells you the ride is not being recorded, which is the one thing on this screen
                // that must not be missable.
                // onClick on the Surface so the press indication is clipped to the pill's corners
                // rather than flashing as a rectangle behind it.
                Surface(
                    onClick = {
                        val settingsAction = if (trackingState == TrackingState.STORAGE_LOW) {
                            android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                        } else {
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        }
                        context.startActivity(android.content.Intent(settingsAction))
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shadowElevation = elevation.mapOverlay,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    val lostSeconds = (timeSinceLastGps / 1000L).coerceAtLeast(1L)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        // The warning mark was a literal "⚠" inside the string, which screen
                        // readers announce inconsistently and translators have to carry.
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (trackingState) {
                                TrackingState.STORAGE_LOW -> strings.hudStorageLowPill
                                TrackingState.GPS_DISABLED -> String.format(
                                    java.util.Locale.getDefault(),
                                    strings.hudLocationDisabledPill,
                                    lostSeconds.toString()
                                )
                                else -> String.format(
                                    java.util.Locale.getDefault(),
                                    strings.hudGpsLostPill,
                                    lostSeconds.toString()
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                        text = "⏸ ${strings.statusPaused}",
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
            if (isOffline && !groupPresencePillShown) {
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
            // On-scale: 20dp was between `large` (16) and `extraLarge` (28) and on neither.
            // `large` is the closer of the two and reads as a composed panel rather than a pill.
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            // Level 3 — a panel floating over the map, so it casts. 8dp was level 4, which the
            // elevation ladder reserves for hover and drag states; nothing rests there.
            // Note tonalElevation is inert while an explicit `color` is set; kept for the day
            // this panel stops being translucent.
            tonalElevation = elevation.level3,
            shadowElevation = elevation.level3
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
                    StatItem(label = strings.distance, value = distanceText)
                    StatItem(label = strings.duration, value = durationText, subValue = elapsedDurationText)
                    // Shared rule, not an inline WALK check: running is the persona that cares
                    // most about pace and was showing speed. "PACE" was also a hardcoded English
                    // literal on a screen that ships in seven languages.
                    if (selectedPersona.usesPace) {
                        StatItem(label = strings.pace, value = paceText)
                    } else {
                        StatItem(label = strings.speed, value = speedText)
                    }
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

                // Action Buttons Row (Unified Pause/Stop Pill | Live Share)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Unified Center Pill (Pause/Resume on left, Slide-to-Stop on right) - 52.dp height
                    val isPaused = trackingState == TrackingState.PAUSED ||
                        trackingState == TrackingState.GPS_LOST ||
                        trackingState == TrackingState.GPS_DISABLED ||
                        trackingState == TrackingState.STORAGE_LOW
                    UnifiedPauseStopPill(
                        isPaused = isPaused,
                        strings = strings,
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

/**
 * @param subValue Optional smaller caption rendered just below [value] — currently used to show
 * total elapsed (wall-clock) time under the active-duration headline stat. Null for stats that
 * don't need a secondary line.
 */
@Composable
private fun StatItem(label: String, value: String, subValue: String? = null) {
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
        if (subValue != null) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun UnifiedPauseStopPill(
    isPaused: Boolean,
    strings: `in`.shvms.trackme.ui.localization.AppStrings,
    onPauseToggle: () -> Unit,
    onStopRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    // Visibility threshold in pixels, not the 0.01 default. That default assumes a normalised
    // 0..1 value; on a pixel offset it means the spring must land within a hundredth of a pixel,
    // and the invisible tail of that decay is several hundred milliseconds of an animation that
    // finished looking finished long ago. One pixel is below what anyone can see.
    val dragOffset = remember { Animatable(0f, visibilityThreshold = 1f) }
    val pauseIconScale = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val motion = LocalTrackMeMotion.current
    val context = LocalContext.current
    var isStoppingAcknowledged by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    ) {
        val halfWidthDp = maxWidth / 2
        val maxSlidePx = with(density) { halfWidthDp.toPx() }

        fun requestStop() {
            if (isStoppingAcknowledged) return
            isStoppingAcknowledged = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 80L)
            coroutineScope.launch {
                // Deliberately still a tween. This is a timed choreography, not free motion: the
                // slide, the 350ms the acknowledgement is readable for, and the commit are one
                // sequence, and the total is what the caller experiences as "how long stopping
                // takes". A spring's settle time depends on how far the thumb has to travel, which
                // is the screen width — so the same gesture would commit at a different moment on
                // a tablet than on a phone.
                dragOffset.animateTo(-maxSlidePx, animationSpec = tween(150))
                delay(350)
                onStopRide()
            }
        }

        // Left side button (Pause / Play)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(halfWidthDp)
                .fillMaxHeight()
                .semantics(mergeDescendants = true) {
                    contentDescription = RideControlAccessibility.pauseToggleContentDescription(isPaused, strings)
                    stateDescription = RideControlAccessibility.pauseToggleStateDescription(isPaused, strings)
                    role = Role.Button
                }
                .clickable {
                    if (dragOffset.value >= -10f && !isStoppingAcknowledged) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerPhysicalVibrate(context, 45L)
                        coroutineScope.launch {
                            pauseIconScale.animateTo(0.75f, motion.spatialFast.spec())
                            pauseIconScale.animateTo(1f, motion.spatialFast.spec())
                        }
                        onPauseToggle()
                    }
                }
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
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
                .semantics(mergeDescendants = true) {
                    contentDescription = RideControlAccessibility.stopContentDescription(strings)
                    stateDescription = RideControlAccessibility.stopStateDescription(isStoppingAcknowledged, strings)
                    role = Role.Button
                    onClick(label = strings.stopRideAction) {
                        requestStop()
                        true
                    }
                }
                .clickable {
                    if (!isStoppingAcknowledged) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerPhysicalVibrate(context, 35L)
                        coroutineScope.launch {
                            dragOffset.animateTo(-maxSlidePx * 0.45f, animationSpec = motion.spatialBounded.spec())
                            dragOffset.animateTo(0f, animationSpec = motion.spatialBounded.spec())
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
                                    requestStop()
                                }
                            }
                        }
                    },
                    onDragStopped = {
                        coroutineScope.launch {
                            if (!isStoppingAcknowledged) {
                                dragOffset.animateTo(0f, animationSpec = motion.spatialBounded.spec())
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
                        contentDescription = null,
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
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = strings.rideStopped
                    }
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
                        text = strings.rideStopped,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
