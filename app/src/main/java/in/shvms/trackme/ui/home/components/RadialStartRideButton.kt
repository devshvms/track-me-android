package `in`.shvms.trackme.ui.home.components

import `in`.shvms.trackme.analytics.RideStartAbortMethod
import `in`.shvms.trackme.ui.components.HapticFeedbackUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import `in`.shvms.trackme.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalViewConfiguration
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
import androidx.compose.ui.text.style.TextAlign
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

internal fun selectedPersonaForRelease(
    hoveredPersona: RidePersona?,
    didExceedTouchSlop: Boolean,
    releasedInsideCenter: Boolean,
    centerPersona: RidePersona = RidePersona.AUTO,
): RidePersona? {
    if (hoveredPersona != null) return hoveredPersona
    return if (!didExceedTouchSlop && releasedInsideCenter) centerPersona else null
}

internal fun hasExceededTouchSlop(
    previouslyExceeded: Boolean,
    distanceFromDownPx: Float,
    touchSlopPx: Float
): Boolean = previouslyExceeded || distanceFromDownPx > touchSlopPx

internal data class PendingRideLaunch(
    val token: Long,
    val persona: RidePersona,
    val awaitsPersonaChoice: Boolean = false,
)

internal data class RadialInteractionState(
    val isPressed: Boolean = false,
    val hoveredPersona: RidePersona? = null,
    val lastVibratedPersona: RidePersona? = null,
    val didExceedTouchSlop: Boolean = false,
    val pendingLaunch: PendingRideLaunch? = null,
)

internal fun canCommitPendingLaunch(
    pendingLaunch: PendingRideLaunch?,
    expectedLaunchToken: Long
): Boolean = pendingLaunch?.token == expectedLaunchToken

internal fun resetRadialInteractionState(): RadialInteractionState = RadialInteractionState()

@Composable
fun RadialStartRideButton(
    onStartRide: (RidePersona) -> Unit,
    preselectedPersona: RidePersona = RidePersona.AUTO,
    onOpenAllPersonas: () -> Unit = {},
    onAbortRideStart: (RideStartAbortMethod) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
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

    var interactionState by remember { mutableStateOf(resetRadialInteractionState()) }
    var nextLaunchToken by remember { mutableStateOf(0L) }

    // Haptics go through the shared helper. This used to hand-roll the same thing against
    // Context.VIBRATOR_SERVICE, which is deprecated since API 31 — and the helper had already been
    // written with the VibratorManager path, so the duplicate was both deprecated and worse.
    fun triggerHaptic() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        HapticFeedbackUtils.triggerPhysicalVibrate(context, durationMs = 30L)
    }

    fun beginLaunch(persona: RidePersona, awaitsPersonaChoice: Boolean = false) {
        nextLaunchToken += 1L
        interactionState = resetRadialInteractionState().copy(
            pendingLaunch = PendingRideLaunch(
                token = nextLaunchToken,
                persona = persona,
                awaitsPersonaChoice = awaitsPersonaChoice,
            )
        )
    }

    fun commitPendingLaunch(observedLaunchToken: Long?): Boolean {
        val launch = interactionState.pendingLaunch
        if (launch == null || observedLaunchToken == null || launch.token != observedLaunchToken) {
            return false
        }
        interactionState = resetRadialInteractionState()
        triggerHaptic()
        onStartRide(launch.persona)
        return true
    }

    fun startPersonaImmediately(persona: RidePersona) {
        interactionState = resetRadialInteractionState()
        triggerHaptic()
        onStartRide(persona)
    }

    LaunchedEffect(interactionState.pendingLaunch?.token) {
        val target = interactionState.pendingLaunch ?: return@LaunchedEffect
        triggerHaptic()
        delay(if (target.awaitsPersonaChoice) 2_500L else 420L)
        if (!canCommitPendingLaunch(interactionState.pendingLaunch, target.token)) {
            return@LaunchedEffect
        }
        if (target.awaitsPersonaChoice) {
            // A bloom is an invitation, not consent to start. Letting this window lapse only
            // retracts the petals; it must not emit pre-commit-abort telemetry.
            interactionState = resetRadialInteractionState()
            return@LaunchedEffect
        }
        interactionState = resetRadialInteractionState()
        onStartRide(target.persona)
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
            .height(260.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Semicircle Persona options
        personas.forEachIndexed { idx, persona ->
            val isHovered = interactionState.hoveredPersona == persona
            val offsetPx = itemOffsetsPx[idx]
            val showPersonas = interactionState.isPressed || interactionState.pendingLaunch != null
            val animAlpha by animateFloatAsState(
                targetValue = if (showPersonas) (if (isHovered) 1f else 0.65f) else 0f,
                animationSpec = spring(),
                label = "alpha_$idx"
            )
            val animScale by animateFloatAsState(
                targetValue = if (showPersonas) (if (isHovered) 1.25f else 1f) else 0f,
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
                        )
                        .clickable(
                            enabled = showPersonas,
                            onClick = { startPersonaImmediately(persona) },
                        )
                        .semantics {
                            contentDescription = String.format(
                                Locale.getDefault(),
                                strings.startPersona,
                                strings.personaLabel(persona),
                            )
                        },
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

        if (interactionState.pendingLaunch != null) {
            TextButton(
                onClick = {
                    interactionState = resetRadialInteractionState()
                    onOpenAllPersonas()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .heightIn(min = 48.dp),
            ) {
                Text(strings.dashboardChangeActivity, maxLines = 1)
            }
        }

        // Outer expanding radar pulse ring during start launch animation.
        //
        // Deliberately still tweens, and the only pair in the app that is. These two describe one
        // object: the ring has to reach full size at the moment it reaches zero opacity, or it
        // either vanishes mid-expansion or lands as a hard-edged circle. A spring's settle time
        // depends on the distance travelled, and these travel different distances — 0.65 of scale
        // against 0.45 of alpha — so no pair of springs keeps them in lockstep. A shared duration
        // is the thing that actually expresses the constraint.
        //
        // This is not the same as the icon crossfades elsewhere, which are two separate objects
        // trading places; there a few milliseconds of offset is invisible, or an improvement.
        val pulseScale by animateFloatAsState(
            targetValue = if (interactionState.pendingLaunch != null) 1.65f else 1f,
            animationSpec = tween(durationMillis = 420),
            label = "pulseScale"
        )
        val pulseAlpha by animateFloatAsState(
            targetValue = if (interactionState.pendingLaunch != null) 0f else 0.45f,
            animationSpec = tween(durationMillis = 420),
            label = "pulseAlpha"
        )

        if (interactionState.pendingLaunch != null && pulseAlpha > 0.01f) {
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
                interactionState.pendingLaunch != null -> 1.12f
                interactionState.isPressed -> 0.92f
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
                    val currentLaunch = interactionState.pendingLaunch
                    val isCancelState = interactionState.isPressed &&
                        interactionState.didExceedTouchSlop &&
                        interactionState.hoveredPersona == null
                    contentDescription = strings.startRideAccessibility
                    stateDescription = when {
                        currentLaunch != null -> strings.activitySelectionAvailable
                        isCancelState -> strings.cancel
                        else -> strings.activitySelectionAvailable
                    }
                    role = Role.Button
                    onClick(
                        label = strings.startRideAction
                    ) {
                        if (currentLaunch == null) {
                            beginLaunch(preselectedPersona, awaitsPersonaChoice = true)
                            true
                        } else {
                            commitPendingLaunch(currentLaunch.token)
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
                            startPersonaImmediately(persona)
                            true
                        }
                    } + CustomAccessibilityAction(strings.dashboardChangeActivity) {
                        interactionState = resetRadialInteractionState()
                        onOpenAllPersonas()
                        true
                    }
                }
                .pointerInput(touchSlopPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val centerPx = Offset(size.width / 2f, size.height / 2f)
                        val downDist = hypot(down.position.x - centerPx.x, down.position.y - centerPx.y)
                        if (downDist > centerPx.x) {
                            return@awaitEachGesture
                        }

                        val launchAtDown = interactionState.pendingLaunch
                        if (launchAtDown != null) {
                            if (launchAtDown.awaitsPersonaChoice) {
                                commitPendingLaunch(launchAtDown.token)
                                down.consume()
                                do {
                                    val commitEvent = awaitPointerEvent()
                                    commitEvent.changes.forEach { it.consume() }
                                } while (commitEvent.changes.any { it.pressed })
                            } else {
                                triggerHaptic()
                                onAbortRideStart(RideStartAbortMethod.PRE_COMMIT)
                                down.consume()
                                do {
                                    val abortEvent = awaitPointerEvent()
                                    abortEvent.changes.forEach { it.consume() }
                                } while (abortEvent.changes.any { it.pressed })
                                interactionState = resetRadialInteractionState()
                            }
                            return@awaitEachGesture
                        }

                        interactionState = resetRadialInteractionState().copy(isPressed = true)
                        var releasedInsideCenter = true
                        triggerHaptic()

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val pos = change.position
                            val distanceFromDown = hypot(
                                pos.x - down.position.x,
                                pos.y - down.position.y
                            )
                            interactionState = interactionState.copy(
                                didExceedTouchSlop = hasExceededTouchSlop(
                                    previouslyExceeded = interactionState.didExceedTouchSlop,
                                    distanceFromDownPx = distanceFromDown,
                                    touchSlopPx = touchSlopPx
                                )
                            )
                            releasedInsideCenter = hypot(
                                pos.x - centerPx.x,
                                pos.y - centerPx.y
                            ) <= centerPx.x
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

                            if (foundHover != interactionState.hoveredPersona) {
                                val shouldVibrate = foundHover != null &&
                                    foundHover != interactionState.lastVibratedPersona
                                interactionState = interactionState.copy(
                                    hoveredPersona = foundHover,
                                    lastVibratedPersona = if (shouldVibrate) {
                                        foundHover
                                    } else {
                                        interactionState.lastVibratedPersona
                                    }
                                )
                                if (shouldVibrate) {
                                    triggerHaptic()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val selected = selectedPersonaForRelease(
                            hoveredPersona = interactionState.hoveredPersona,
                            didExceedTouchSlop = interactionState.didExceedTouchSlop,
                            releasedInsideCenter = releasedInsideCenter,
                            centerPersona = preselectedPersona,
                        )
                        interactionState = resetRadialInteractionState()
                        if (selected != null) {
                            beginLaunch(selected)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                // C1: content sits on colorScheme.primary, so it must use onPrimary. It was
                // hardcoded Navy900, which only worked because the button was light green —
                // on light theme's cyan/deep that would be dark-on-dark and fail AA.
                // The small captions previously ran at 90% alpha; on cyan/deep that composites
                // to 4.27:1 (below AA for 8-9sp text), so they now render at full opacity and
                // take their hierarchy from size/weight instead. Covered by ThemeContrastTest.
                val onStartButton = MaterialTheme.colorScheme.onPrimary
                val currentLaunch = interactionState.pendingLaunch?.persona
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
                } else if (interactionState.isPressed) {
                    val currentHover = interactionState.hoveredPersona
                    if (currentHover != null) {
                        // Actively hovering a specific persona while dragging: icon only, no
                        // text — same reasoning as the launched state above.
                        Icon(
                            imageVector = currentHover.icon(),
                            contentDescription = null,
                            tint = onStartButton,
                            modifier = Modifier.size(36.dp)
                        )
                    } else if (
                        interactionState.didExceedTouchSlop
                    ) {
                        // Once the gesture becomes a drag, releasing without a hovered persona
                        // cancels. Keep this state icon-only so the fixed center circle remains
                        // robust at larger font scales; semantics announces the localized label.
                        Icon(
                            imageVector = Icons.Default.Close,
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
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 9.sp,
                                maxFontSize = 14.sp,
                                stepSize = 0.5.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = strings.dragToSelect,
                            color = onStartButton,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            softWrap = true,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (preselectedPersona != RidePersona.AUTO) {
                    Icon(
                        imageVector = preselectedPersona.icon(),
                        contentDescription = null,
                        tint = onStartButton,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = strings.personaLabel(preselectedPersona),
                        color = onStartButton,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
