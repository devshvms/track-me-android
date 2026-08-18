package `in`.shvms.trackme.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.RideSplit
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.fastestSplit
import `in`.shvms.trackme.theme.LocalTrackMeMotion
import `in`.shvms.trackme.theme.LocalTrackMeSpacing
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/**
 * Per-kilometre (or per-mile) splits, as a bar per unit.
 *
 * ### Why bars rather than the line chart
 *
 * The line chart answers "what was I doing at this moment", which is what the scrubber is for. A
 * splits table answers a different question — "was I consistent, and did I fade" — and on foot
 * that is usually the one being asked. A per-sample pace line is dominated by GPS noise and by
 * every traffic light; a kilometre is long enough to average that out into something comparable.
 *
 * ### Bar length
 *
 * Proportional to **speed**, not to pace, so the fastest split is the longest bar. Pace runs the
 * other way — a slower split is a bigger number — and drawing that directly would give the worst
 * kilometre the most ink, which reads as an achievement. Every running app draws it this way and
 * the convention is worth matching.
 */
@Composable
fun RideSplitsSection(
    splits: List<RideSplit>,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    if (splits.isEmpty()) return
    val strings = LocalAppStrings.current
    val spacing = LocalTrackMeSpacing.current
    val motion = LocalTrackMeMotion.current

    val fastest = remember(splits) { fastestSplit(splits) }
    // Bars are scaled against the fastest split rather than against the theoretical maximum, so a
    // steady ride still fills the row and does not read as a set of stubs.
    val fastestSpeed = fastest?.averageSpeedMps ?: splits.maxOf { it.averageSpeedMps }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.betweenCards),
    ) {
        splits.forEach { split ->
            SplitRow(
                split = split,
                imperial = imperial,
                isFastest = fastest != null && split.index == fastest.index,
                fillFraction = if (fastestSpeed > 0.0) {
                    (split.averageSpeedMps / fastestSpeed).toFloat().coerceIn(0.04f, 1f)
                } else {
                    0.04f
                },
                fastestLabel = strings.splitFastest,
                partialLabel = strings.splitPartial,
                motionSpec = motion.spatialDefault.spec(),
            )
        }
    }
}

@Composable
private fun SplitRow(
    split: RideSplit,
    imperial: Boolean,
    isFastest: Boolean,
    fillFraction: Float,
    fastestLabel: String,
    partialLabel: String,
    motionSpec: androidx.compose.animation.core.AnimationSpec<Float>,
) {
    val paceText = UnitFormatter.pace(split.averageSpeedMps, imperial)
    val unitLabel = UnitFormatter.distanceUnitLabel(imperial)
    // A partial split is labelled by the distance it actually covers, because calling a 400 m tail
    // "4 km" invites comparing its pace with the full kilometres above it.
    val indexLabel = if (split.isPartial) {
        UnitFormatter.distance(split.distanceMeters, imperial, decimals = 2)
    } else {
        "${split.index} $unitLabel"
    }

    val barColor = when {
        isFastest -> MaterialTheme.colorScheme.primary
        split.isPartial -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.secondary
    }
    val animatedFill by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = motionSpec,
        label = "splitBar",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Read as one sentence rather than as three disconnected fragments.
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(indexLabel)
                    append(", ")
                    append(paceText)
                    if (isFastest) append(", $fastestLabel")
                    if (split.isPartial) append(", $partialLabel")
                }
            },
    ) {
        Text(
            text = indexLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isFastest) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFill)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor),
            )
        }

        Text(
            text = paceText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isFastest) FontWeight.Bold else FontWeight.Normal,
            color = if (isFastest) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.width(76.dp),
        )
    }
}
