package `in`.shvms.trackme.ui.home.components

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import `in`.shvms.trackme.ui.components.HapticFeedbackUtils.triggerPhysicalVibrate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
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
 * @param onStartShare Callback triggered when the user initiates location sharing.
 * @param onStopShare Callback triggered when the user stops sharing location.
 * @param onSendShare Callback triggered when sending/sharing link via system intent.
 * @param onCopyShare Callback triggered when copying link to clipboard.
 * @param modifier Optional [Modifier] for layout placement.
 */
@Composable
fun InteractiveShareLocationButton(
    liveShareState: LiveShareState,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onSendShare: () -> Unit,
    onCopyShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDrawerOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val buttonScale = remember { Animatable(1f) }

    // Trigger haptics and clean scale bounce when status transitions (e.g. IDLE -> STARTING or STARTING -> ACTIVE)
    LaunchedEffect(liveShareState.status) {
        if (liveShareState.status == LiveShareStatus.STARTING) {
            isDrawerOpen = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 45L)
            buttonScale.animateTo(1.15f, tween(120))
            buttonScale.animateTo(1.0f, tween(150))
        } else if (liveShareState.status == LiveShareStatus.ACTIVE) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 55L)
            buttonScale.animateTo(1.22f, tween(150))
            buttonScale.animateTo(1.0f, tween(180))
        } else if (liveShareState.status == LiveShareStatus.IDLE) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 40L)
        }
    }

    // Colors for different states
    val inactiveBgColor = Color(0xFFB0BEC5) // s1: Inactive gray push button
    val activeBgColor = Color(0xFFB3E5FC) // s4: Active cyan/sky blue
    val optionButtonColor = Color(0xFF29B6F6) // Drawer circle blue

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
    val density = LocalDensity.current

    // Smooth background color transition
    val animatedBgColor by animateColorAsState(
        targetValue = when {
            isDrawerOpen -> Color(0xFFE0E0E0)
            isActive -> activeBgColor
            isStarting -> activeBgColor.copy(alpha = blinkingAlpha)
            else -> inactiveBgColor
        },
        animationSpec = tween(durationMillis = if (isStarting) 0 else 220, easing = FastOutSlowInEasing),
        label = "animatedBgColor"
    )

    // Smooth icon transitions (no layout jumps or dual-mounting glitches)
    val antennaAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "antennaAlpha"
    )
    val antennaScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0.65f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "antennaScale"
    )
    val crossAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "crossAlpha"
    )
    val crossScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0.65f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "crossScale"
    )
    val crossRotation by animateFloatAsState(
        targetValue = if (isDrawerOpen) 90f else -45f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "crossRotation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Base Push Button (s1, s3, s4) transforms into cross (X) when drawer is open
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .scale(buttonScale.value)
                .clip(CircleShape)
                .background(color = animatedBgColor)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    triggerPhysicalVibrate(context, 35L)
                    isDrawerOpen = !isDrawerOpen
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Antenna icon layer
                if (antennaAlpha > 0.01f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.graphicsLayer {
                            alpha = antennaAlpha
                            scaleX = antennaScale
                            scaleY = antennaScale
                        }
                    ) {
                        LiveShareAntennaIcon(tint = Color.Black.copy(alpha = 0.85f))
                    }
                }
                // Cross icon layer
                if (crossAlpha > 0.01f) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
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

        // Red notification dot when share link is ready and drawer is closed
        if (isActive && liveShareState.shareLink != null && !isDrawerOpen) {
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
                    .background(Color(0xFFEF4444))
            )
        }

        // Upward Circular Drawer (s2 / s5)

        if (isDrawerOpen) {
            val popupOffsetY = remember(density) {
                with(density) { -60.dp.roundToPx() }
            }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { isDrawerOpen = false },
                properties = PopupProperties(focusable = true)
            ) {
                // 90% transparent background container hugging the circular 52dp buttons
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = Color(0xFFCFD8DC).copy(alpha = 0.10f),
                    shadowElevation = 0.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        if (isActive) {
                            // s5: Active sharing symbol options drawer
                            DrawerOptionCircleButton(
                                icon = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                color = optionButtonColor,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 40L)
                                    isDrawerOpen = false
                                    onSendShare()
                                }
                            )
                            DrawerOptionCircleButton(
                                icon = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                color = optionButtonColor,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 40L)
                                    isDrawerOpen = false
                                    onCopyShare()
                                }
                            )
                            DrawerOptionCircleButton(
                                icon = Icons.Default.Stop,
                                contentDescription = "Stop",
                                color = Color(0xFFFFCDD2),
                                iconTint = Color(0xFFC62828),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    triggerPhysicalVibrate(context, 65L)
                                    isDrawerOpen = false
                                    onStopShare()
                                }
                            )
                        } else {
                            // s2: Inactive sharing drawer
                            DrawerOptionCircleButton(
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                color = optionButtonColor,
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
private fun DrawerOptionCircleButton(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    iconTint: Color = Color.Black.copy(alpha = 0.85f),
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = color,
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(52.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun LiveShareAntennaIcon(tint: Color) {
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
            contentDescription = "Share Pin",
            tint = Color(0xFFE53935),
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

