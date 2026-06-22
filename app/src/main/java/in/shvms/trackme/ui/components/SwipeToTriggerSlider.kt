package `in`.shvms.trackme.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SliderState {
    IDLE, COUNTDOWN
}

@Composable
fun SwipeToTriggerSlider(
    onTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Swipe for SOS"
) {
    val haptic = LocalHapticFeedback.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var lastHapticOffset by remember { mutableFloatStateOf(0f) }
    
    var sliderState by remember { mutableStateOf(SliderState.IDLE) }
    var timeLeft by remember { mutableIntStateOf(`in`.shvms.trackme.config.AppConfig.SOS_COUNTDOWN_SECONDS) }
    var animationProgress by remember { mutableFloatStateOf(0f) }
    
    val animatedProgress by animateFloatAsState(
        targetValue = animationProgress,
        animationSpec = tween(durationMillis = `in`.shvms.trackme.config.AppConfig.SOS_COUNTDOWN_SECONDS * 1000, easing = LinearEasing),
        label = "countdown"
    )

    LaunchedEffect(sliderState) {
        if (sliderState == SliderState.COUNTDOWN) {
            animationProgress = 1f
            for (i in `in`.shvms.trackme.config.AppConfig.SOS_COUNTDOWN_SECONDS downTo 1) {

                timeLeft = i
                delay(1000L)
            }
            onTriggered()
            sliderState = SliderState.IDLE
            offsetX = 0f
            animationProgress = 0f
        } else {
            animationProgress = 0f
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterStart
    ) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val thumbWidthPx = with(LocalDensity.current) { 64.dp.toPx() }
        val maxDragDistance = maxWidthPx - thumbWidthPx

        if (sliderState == SliderState.IDLE) {
            Text(
                text = text,
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelLarge
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            val newOffset = (offsetX + delta).coerceIn(0f, maxDragDistance)
                            offsetX = newOffset
                            
                            // Haptic feedback while dragging
                            if (abs(offsetX - lastHapticOffset) > 50f) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticOffset = offsetX
                            }

                            if (offsetX >= maxDragDistance * 0.95f) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                sliderState = SliderState.COUNTDOWN
                                offsetX = 0f
                            }
                        },
                        onDragStopped = {
                            if (sliderState != SliderState.COUNTDOWN) {
                                offsetX = 0f
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Emergency", tint = Color.White)
            }
        } else {
            // Countdown State
            // Background filling from left to right
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .background(Color(0xFFB71C1C)) // Darker red
            )
            
            // Cancel button overlay
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { 
                        sliderState = SliderState.IDLE
                        offsetX = 0f
                        animationProgress = 0f
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel SOS", tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Cancel ($timeLeft)",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
