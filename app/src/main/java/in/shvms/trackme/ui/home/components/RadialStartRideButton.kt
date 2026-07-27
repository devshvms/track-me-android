package `in`.shvms.trackme.ui.home.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import `in`.shvms.trackme.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun RadialStartRideButton(
    onStartRide: (RidePersona) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val strings = LocalAppStrings.current

    val personas = remember {
        listOf(
            RidePersona.WALK,
            RidePersona.RUN,
            RidePersona.CYCLING,
            RidePersona.BIKE_DRIVE,
            RidePersona.CAR_DRIVE
        )
    }

    // Angles in degrees for semicircle arch around top (from left to right)
    val anglesDeg = remember { listOf(160.0, 125.0, 90.0, 55.0, 20.0) }
    val radiusDp = 115.dp
    val radiusPx = with(density) { radiusDp.toPx() }
    val itemRadiusPx = with(density) { 28.dp.toPx() } // 56dp diameter / 2

    var isPressed by remember { mutableStateOf(false) }
    var hoveredPersona by remember { mutableStateOf<RidePersona?>(null) }
    var lastVibratedPersona by remember { mutableStateOf<RidePersona?>(null) }
    var launchedPersona by remember { mutableStateOf<RidePersona?>(null) }

    // Helper to trigger haptic feedback
    fun triggerHaptic() {
        try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(launchedPersona) {
        val target = launchedPersona ?: return@LaunchedEffect
        triggerHaptic()
        delay(420)
        onStartRide(target)
        launchedPersona = null
    }

    // Calculate offsets for persona items relative to center button
    val itemOffsetsPx = remember(radiusPx) {
        anglesDeg.map { deg ->
            val rad = Math.toRadians(deg)
            val dx = radiusPx * cos(rad)
            val dy = -radiusPx * sin(rad) // negative y is UP
            Offset(dx.toFloat(), dy.toFloat())
        }
    }

    Box(
        modifier = modifier
            .width(260.dp)
            .height(180.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Semicircle Persona options
        personas.forEachIndexed { idx, persona ->
            val isHovered = hoveredPersona == persona
            val offsetPx = itemOffsetsPx[idx]
            val animAlpha by animateFloatAsState(
                targetValue = if (isPressed) (if (isHovered) 1f else 0.65f) else 0f,
                animationSpec = spring(),
                label = "alpha_$idx"
            )
            val animScale by animateFloatAsState(
                targetValue = if (isPressed) (if (isHovered) 1.25f else 1f) else 0f,
                animationSpec = spring(),
                label = "scale_$idx"
            )

            if (animAlpha > 0.01f) {
                val offsetDpX = with(density) { offsetPx.x.toDp() }
                val offsetDpY = with(density) { offsetPx.y.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = offsetDpX, y = offsetDpY)
                        .scale(animScale)
                        .alpha(animAlpha)
                        .size(56.dp)
                        .clip(CircleShape)
                        // C1: persona circles are a brand-action control → cyan, not green.
                        .background(
                            color = if (isHovered) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            }
                        )
                        .border(
                            width = if (isHovered) 2.dp else 0.dp,
                            color = Color.White,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = persona.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Outer expanding radar pulse ring during start launch animation
        val pulseScale by animateFloatAsState(
            targetValue = if (launchedPersona != null) 1.65f else 1f,
            animationSpec = tween(durationMillis = 420),
            label = "pulseScale"
        )
        val pulseAlpha by animateFloatAsState(
            targetValue = if (launchedPersona != null) 0f else 0.45f,
            animationSpec = tween(durationMillis = 420),
            label = "pulseAlpha"
        )

        if (launchedPersona != null && pulseAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .scale(pulseScale)
                    .size(86.dp)
                    .clip(CircleShape)
                    // C1: launch pulse belongs to the brand-action Start control → cyan.
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                        CircleShape
                    )
            )
        }

        // Center Start Button (C1: brand cyan, not green) - only touches on this circle
        // trigger interaction
        val centerScale by animateFloatAsState(
            targetValue = when {
                launchedPersona != null -> 1.12f
                isPressed -> 0.92f
                else -> 1f
            },
            animationSpec = spring(),
            label = "centerScale"
        )

        Box(
            modifier = Modifier
                .scale(centerScale)
                .size(86.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .semantics(mergeDescendants = true) {
                    val currentLaunch = launchedPersona
                    contentDescription = strings.startRideAccessibility
                    stateDescription = if (currentLaunch == null) {
                        strings.activitySelectionAvailable
                    } else {
                        String.format(
                            Locale.getDefault(),
                            strings.startingPersona,
                            strings.personaLabel(currentLaunch)
                        )
                    }
                    role = Role.Button
                    onClick(label = strings.startRideAction) {
                        if (launchedPersona == null) {
                            launchedPersona = RidePersona.AUTO
                            true
                        } else {
                            false
                        }
                    }
                    customActions = personas.map { persona ->
                        CustomAccessibilityAction(
                            String.format(
                                Locale.getDefault(),
                                strings.startPersona,
                                strings.personaLabel(persona)
                            )
                        ) {
                            if (launchedPersona == null) {
                                launchedPersona = persona
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val centerPx = Offset(size.width / 2f, size.height / 2f)
                        val downDist = hypot(down.position.x - centerPx.x, down.position.y - centerPx.y)
                        if (downDist > centerPx.x || launchedPersona != null) {
                            return@awaitEachGesture
                        }

                        isPressed = true
                        hoveredPersona = null
                        lastVibratedPersona = null
                        triggerHaptic()

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val pos = change.position
                            val relPos = pos - centerPx

                            // Check which persona circle is closest/hovered
                            var foundHover: RidePersona? = null
                            for (i in personas.indices) {
                                val targetOffset = itemOffsetsPx[i]
                                val dist = hypot(relPos.x - targetOffset.x, relPos.y - targetOffset.y)
                                if (dist <= itemRadiusPx * 1.5f) {
                                    foundHover = personas[i]
                                    break
                                }
                            }

                            if (foundHover != hoveredPersona) {
                                hoveredPersona = foundHover
                                if (foundHover != null && foundHover != lastVibratedPersona) {
                                    lastVibratedPersona = foundHover
                                    triggerHaptic()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val selected = hoveredPersona ?: RidePersona.AUTO
                        isPressed = false
                        hoveredPersona = null
                        lastVibratedPersona = null
                        launchedPersona = selected
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // C1: content sits on colorScheme.primary, so it must use onPrimary. It was
                // hardcoded Navy900, which only worked because the button was light green —
                // on light theme's cyan/deep that would be dark-on-dark and fail AA.
                // The small captions previously ran at 90% alpha; on cyan/deep that composites
                // to 4.27:1 (below AA for 8-9sp text), so they now render at full opacity and
                // take their hierarchy from size/weight instead. Covered by ThemeContrastTest.
                val onStartButton = MaterialTheme.colorScheme.onPrimary
                val currentLaunch = launchedPersona
                if (currentLaunch != null) {
                    // A persona has been selected (drag released) and the ride is about to
                    // start: show just its icon, no text — the outer radar pulse ring plus the
                    // parent's merged accessibility stateDescription ("Starting X") already
                    // communicate the confirmation, so a text label here is redundant clutter.
                    Icon(
                        imageVector = currentLaunch.icon(),
                        contentDescription = null,
                        tint = onStartButton,
                        modifier = Modifier.size(40.dp)
                    )
                } else if (isPressed) {
                    val currentHover = hoveredPersona
                    if (currentHover != null) {
                        // Actively hovering a specific persona while dragging: icon only, no
                        // text — same reasoning as the launched state above.
                        Icon(
                            imageVector = currentHover.icon(),
                            contentDescription = null,
                            tint = onStartButton,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        // No persona hovered yet (still over the center = AUTO). This is
                        // onboarding guidance, not a persona selection, so it keeps its label.
                        Icon(
                            imageVector = RidePersona.AUTO.icon(),
                            contentDescription = null,
                            tint = onStartButton,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = strings.personaLabel(RidePersona.AUTO),
                            color = onStartButton,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = strings.dragToSelect.uppercase(Locale.getDefault()),
                            color = onStartButton,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        // The parent owns the merged accessibility description.
                        contentDescription = null,
                        tint = onStartButton,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }
    }
}
