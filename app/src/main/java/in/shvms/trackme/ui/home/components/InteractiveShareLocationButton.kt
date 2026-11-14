package `in`.shvms.trackme.ui.home.components

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import `in`.shvms.trackme.theme.*
import `in`.shvms.trackme.ui.components.HapticFeedbackUtils.triggerPhysicalVibrate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.data.remote.LiveShareStatus
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/**
 * Production-ready interactive Live Share Location floating action button.
 *
 * Features:
 * - Dynamic color transitions and animated antenna pulse when live sharing is active.
 * - Smooth morphing close icon when drawer is opened.
 * - Upward circular option drawer (90% translucent container hugging 52.dp circle buttons).
 * - Tactile haptic feedback and device vibration on state changes and clicks.
 *
 * @param liveShareState Current [LiveShareState] representing active/idle/starting status.
 * @param isAuthenticated Whether the current user can create or mutate a live-share session.
 * @param onStartShare Callback triggered when the user initiates location sharing.
 * @param onStopShare Callback triggered when the user stops sharing location.
 * @param onSendShare Callback triggered when sending/sharing link via system intent.
 * @param onCopyShare Callback triggered when copying link to clipboard.
 * @param modifier Optional [Modifier] for layout placement.
 */
@Composable
fun InteractiveShareLocationButton(
    liveShareState: LiveShareState,
    isAuthenticated: Boolean,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onSendShare: () -> Unit,
    onCopyShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDrawerOpen by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val motion = LocalTrackMeMotion.current
    val buttonScale = remember { Animatable(1f) }

    // Auto-open the drawer and copy the link exactly once when a share the user just started
    // becomes ACTIVE (STARTING -> ACTIVE). Not fired on first composition, so returning to a
    // screen with an already-active share does not re-copy or re-open.
    var previousStatus by remember { mutableStateOf(liveShareState.status) }
    LaunchedEffect(liveShareState.status) {
        if (previousStatus == LiveShareStatus.STARTING &&
            liveShareState.status == LiveShareStatus.ACTIVE &&
            liveShareState.shareLink != null
        ) {
            isDrawerOpen = true
            onCopyShare()
        }
        previousStatus = liveShareState.status
    }

    // Trigger haptics and clean scale bounce when status transitions (e.g. IDLE -> STARTING or STARTING -> ACTIVE)
    LaunchedEffect(liveShareState.status) {
        if (liveShareState.status == LiveShareStatus.STARTING) {
            isDrawerOpen = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 45L)
            buttonScale.animateTo(1.15f, motion.spatialFast.spec())
            buttonScale.animateTo(1.0f, motion.spatialFast.spec())
        } else if (liveShareState.status == LiveShareStatus.ACTIVE) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 55L)
            buttonScale.animateTo(1.22f, motion.spatialFast.spec())
            buttonScale.animateTo(1.0f, motion.spatialFast.spec())
        } else if (liveShareState.status == LiveShareStatus.IDLE) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 40L)
        }
    }

    // Colors for different states
    val inactiveBgColor = TrackMeGrey // s1: Inactive gray push button
    val activeBgColor = Color(0xFFB3E5FC) // s4: Active cyan/sky blue
    val optionButtonColor = TrackMeBlue // Drawer circle blue

    // Blinking animation for s3 (STARTING)
    val infiniteTransition = rememberInfiniteTransition(label = "share_blink")
    val blinkingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    val isStarting = liveShareState.status == LiveShareStatus.STARTING
    val isActive = liveShareState.status == LiveShareStatus.ACTIVE
    val isDisabled = !isAuthenticated
    val density = LocalDensity.current

    // Smooth background color transition
    val animatedBgColor by animateColorAsState(
        targetValue = when {
            isDisabled -> TrackMeGrey.copy(alpha = 0.55f)
            // Was a hardcoded light grey, which flashed pale against the night basemap. The
            // active/inactive states below stay on their fixed accent fills — those are their
            // own surfaces with foregrounds chosen for them.
            isDrawerOpen -> MaterialTheme.colorScheme.surfaceContainerHighest
            isActive -> activeBgColor
            isStarting -> activeBgColor.copy(alpha = blinkingAlpha)
            else -> inactiveBgColor
        },
        // While STARTING the target is `blinkingAlpha`, which is itself an infinite animation, so
        // there is nothing to smooth toward — a spec here would chase a moving target and mush the
        // blink into a dim constant. `snap()` says that outright; the old `tween(0)` was the same
        // decision written as a special case of a duration.
        animationSpec = if (isStarting) snap() else motion.effectsDefault.spec(),
        label = "animatedBgColor"
    )

    // Smooth icon transitions (no layout jumps or dual-mounting glitches). Alphas take effects
    // tokens, scale and rotation take spatial ones — see MapControlButtons for why that split is
    // load-bearing rather than cosmetic.
    val antennaAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0f else 1f,
        animationSpec = motion.effectsDefault.spec(),
        label = "antennaAlpha"
    )
    val antennaScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0.65f else 1f,
        animationSpec = motion.spatialFast.spec(),
        label = "antennaScale"
    )
    val crossAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = motion.effectsDefault.spec(),
        label = "crossAlpha"
    )
    val crossScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0.65f,
        animationSpec = motion.spatialFast.spec(),
        label = "crossScale"
    )
    val crossRotation by animateFloatAsState(
        targetValue = if (isDrawerOpen) 90f else -45f,
        animationSpec = motion.spatialFast.spec(),
        label = "crossRotation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Base Push Button (s1, s3, s4) transforms into cross (X) when drawer is open.
        // v1.9.3: when idle/error/expired, 1 tap immediately starts live sharing (skipping the
        // intermediate PlayArrow sub-drawer). When ACTIVE, tapping toggles the Send/Copy/Stop drawer.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .scale(buttonScale.value)
                .clip(CircleShape)
                .background(color = animatedBgColor)
                .clickable(enabled = isAuthenticated && !isStarting) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    triggerPhysicalVibrate(context, 35L)
                    if (isActive) {
                        isDrawerOpen = !isDrawerOpen
                    } else {
                        isDrawerOpen = false
                        onStartShare()
                    }
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isDisabled) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = strings.liveShareAuthRequired,
                        tint = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(27.dp)
                    )
                } else if (antennaAlpha > 0.01f) {
                    // Antenna icon layer
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.graphicsLayer {
                            alpha = antennaAlpha
                            scaleX = antennaScale
                            scaleY = antennaScale
                        }
                    ) {
                        LiveShareAntennaIcon(
                            contentDescription = strings.sharePin,
                            tint = Color.Black.copy(alpha = 0.85f)
                        )
                    }
                }
                // Cross icon layer
                if (crossAlpha > 0.01f) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.close,
                        tint = Color.Black.copy(alpha = 0.85f),
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                alpha = crossAlpha
                                scaleX = crossScale
                                scaleY = crossScale
                                rotationZ = crossRotation
                            }
                    )
                }
            }
        }

        // Viewer count pill or pulsing ready dot when drawer is closed
        if (isActive && liveShareState.shareLink != null && !isDrawerOpen) {
            if (liveShareState.activeViewers > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = liveShareState.activeViewers.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            } else {
                val dotPulse by infiniteTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotPulse"
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(12.dp)
                        .scale(dotPulse)
                        .clip(CircleShape)
                        .background(TrackMeRed)
                )
            }
        }

        // Upward Circular Drawer (s2 / s5)

        if (isDrawerOpen && isAuthenticated) {
            val popupOffsetY = remember(density) {
                with(density) { -60.dp.roundToPx() }
            }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { isDrawerOpen = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.width(185.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        if (isActive) {
                            DrawerOptionPillButton(
                                icon = Icons.AutoMirrored.Filled.Send,
                                label = strings.shareLink,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 40L)
                                    isDrawerOpen = false
                                    onSendShare()
                                }
                            )
                            DrawerOptionPillButton(
                                icon = Icons.Default.ContentCopy,
                                label = strings.copyLink,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 40L)
                                    isDrawerOpen = false
                                    onCopyShare()
                                }
                            )
                            DrawerOptionPillButton(
                                icon = Icons.Default.Stop,
                                label = strings.stopSharing,
                                color = MaterialTheme.colorScheme.errorContainer,
                                textColor = MaterialTheme.colorScheme.onErrorContainer,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 65L)
                                    isDrawerOpen = false
                                    onStopShare()
                                }
                            )
                        } else {
                            DrawerOptionPillButton(
                                icon = Icons.Default.PlayArrow,
                                label = strings.startSharing,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 50L)
                                    isDrawerOpen = false
                                    onStartShare()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerOptionPillButton(
    icon: ImageVector,
    label: String,
    color: Color,
    textColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = color,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LiveShareAntennaIcon(contentDescription: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "((",
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = contentDescription,
            tint = TrackMeRed,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
            Text(
            text = "))",
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
