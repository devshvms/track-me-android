package `in`.shvms.trackme.ui.onboarding

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.delay
import java.util.Locale

internal const val ONBOARDING_DEMO_AUTO_ADVANCE_MILLIS = 4_000L

internal fun nextOnboardingDemoStep(currentStep: Int, stepCount: Int): Int? {
    require(stepCount > 0)
    require(currentStep in 0 until stepCount)
    return (currentStep + 1).takeIf { it < stepCount }
}

internal fun isMeaningfulOnboardingScrub(startIndex: Int, currentIndex: Int, pointCount: Int): Boolean {
    if (pointCount < 2) return false
    val threshold = ((pointCount - 1) * 0.25f).toInt().coerceAtLeast(2)
    return kotlin.math.abs(currentIndex - startIndex) >= threshold
}

/**
 * Shared, service-free shell for the two interactive onboarding pages.
 *
 * The host owns only presentation progress. Feature state stays with the demo content, and the
 * four-second fallback means an unfamiliar gesture can never trap someone in onboarding.
 */
@Composable
internal fun OnboardingDemoHost(
    strings: AppStrings,
    instructions: List<String>,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (step: Int, advance: () -> Unit) -> Unit,
) {
    require(instructions.isNotEmpty())
    var step by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var activityGeneration by rememberSaveable { mutableIntStateOf(0) }

    fun advance() {
        if (completed) return
        val next = nextOnboardingDemoStep(step, instructions.size)
        if (next == null) {
            completed = true
            onFinished()
        } else {
            step = next
        }
    }

    LaunchedEffect(step, completed, activityGeneration) {
        if (!completed) {
            delay(ONBOARDING_DEMO_AUTO_ADVANCE_MILLIS)
            advance()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            strings.obDemoStepCounter,
                            step + 1,
                            instructions.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = ::advance) {
                        Text(strings.obDemoSkipStep)
                    }
                }
                Text(
                    text = instructions[step],
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(step) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        activityGeneration += 1
                    }
                },
        ) {
            content(step, ::advance)
        }
    }
}
