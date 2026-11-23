package `in`.shvms.trackme.ui.settings

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.live.LiveShareFrequencySliderPolicy
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formats a Live Share update interval in seconds using localized short units (`5 min`, `1 min`, `15 sec`).
 */
internal fun formatLiveShareIntervalShort(seconds: Int, strings: AppStrings): String {
    return if (seconds >= 60 && seconds % 60 == 0) {
        String.format(Locale.getDefault(), strings.liveShareIntervalMinutesShort, seconds / 60)
    } else {
        String.format(Locale.getDefault(), strings.liveShareIntervalSecondsShort, seconds)
    }
}

/**
 * TASK-377: Native 6-step battery-aware slider for Live Share update frequency.
 *
 * Positions run left-to-right from less frequent (5 min / Less battery use) to more frequent
 * (5 sec / More frequent updates). Updates the displayed value on every step crossed with a brief
 * reduced-motion-aware transition, and commits the chosen interval in seconds on release or
 * screen-reader increment/decrement.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveShareFrequencySliderSection(
    committedSeconds: Int,
    onCommitSeconds: (Int) -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    var previewIndex by remember {
        mutableIntStateOf(LiveShareFrequencySliderPolicy.indexForSeconds(committedSeconds))
    }
    LaunchedEffect(committedSeconds) {
        previewIndex = LiveShareFrequencySliderPolicy.indexForSeconds(committedSeconds)
    }

    val previewSeconds = LiveShareFrequencySliderPolicy.secondsForIndex(previewIndex)
    val previewLabel = formatLiveShareIntervalShort(previewSeconds, strings)

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.liveLocationUpdatesTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            AnimatedContent(
                targetState = previewLabel,
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 4 }) togetherWith
                            (fadeOut(tween(110)) + slideOutVertically(tween(110)) { -it / 4 })
                    }
                },
                label = "live_share_interval_label",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("live_share_frequency_value"),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Slider(
            value = previewIndex.toFloat(),
            onValueChange = { rawValue ->
                val nextIndex = rawValue.roundToInt().coerceIn(0, LiveShareFrequencySliderPolicy.MAX_INDEX)
                if (nextIndex != previewIndex) {
                    previewIndex = nextIndex
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onValueChangeFinished = {
                val committed = LiveShareFrequencySliderPolicy.secondsForIndex(previewIndex)
                onCommitSeconds(committed)
            },
            valueRange = 0f..LiveShareFrequencySliderPolicy.MAX_INDEX.toFloat(),
            steps = LiveShareFrequencySliderPolicy.SLIDER_INTERNAL_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_share_frequency_slider")
                .semantics {
                    contentDescription = strings.liveLocationUpdatesTitle
                    stateDescription = previewLabel
                },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.liveShareSliderLessBattery,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = strings.liveShareSliderMoreFrequent,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = strings.liveShareSliderBatteryNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
