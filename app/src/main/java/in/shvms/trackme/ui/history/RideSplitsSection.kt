package `in`.shvms.trackme.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.RideSplit
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.fastestSplit
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/** One bar's slot. Fixed, so a 2 km walk and a 30 km run draw the same shape of bar. */
private val SPLIT_BAR_WIDTH = 44.dp
private val SPLIT_BAR_GAP = 8.dp
private val SPLIT_PLOT_HEIGHT = 104.dp

/**
 * Per-unit splits as labelled columns.
 *
 * ### Fixed bar width, scrolled when it overflows
 *
 * Dividing the available width by the number of splits made a two-split walk draw two enormous
 * slabs and would have made a thirty-split run draw thirty slivers. Neither reads as the same
 * chart. A fixed slot means a bar is always a bar; when the ride outgrows the screen the row
 * scrolls, which is the honest response to more data than fits.
 *
 * ### What the height means
 *
 * Height is the pace itself — minutes per unit — so a **taller bar is a slower kilometre**. That is
 * the honest reading of a time-per-distance measure: more time is more bar. The colour ramp says
 * the same thing a second way, and the number is printed above every bar, so the chart cannot be
 * read backwards.
 *
 * Heights scale against the split range rather than against zero. A ride whose kilometres run 5:30
 * to 6:00 has a real story in those thirty seconds, and anchoring at zero would flatten it into a
 * row of identical bars.
 */
@Composable
fun RideSplitsSection(
    splits: List<RideSplit>,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    if (splits.isEmpty()) return
    val strings = LocalAppStrings.current

    val fastest = remember(splits) { fastestSplit(splits) }
    val paces = remember(splits, imperial) {
        splits.map { UnitFormatter.paceSecondsPerUnit(it.averageSpeedMps.coerceAtLeast(0.01), imperial) }
    }
    // Full splits set the scale. A partial covers less ground, so its pace is noisier, and letting
    // it define the range would squash every real kilometre against one end.
    val scalePaces = remember(splits, paces) {
        splits.indices.filter { !splits[it].isPartial }.map { paces[it] }.ifEmpty { paces }
    }
    val slowest = scalePaces.max()
    val quickest = scalePaces.min()
    val span = (slowest - quickest).takeIf { it > 0.001 } ?: 1.0

    val description = remember(splits, imperial, strings) {
        splitsAccessibilityText(splits, imperial, fastest?.index, strings)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // One node for the whole chart: "1, 2, 3" read bar by bar tells nobody anything.
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(SPLIT_BAR_GAP),
        verticalAlignment = Alignment.Bottom,
    ) {
        splits.forEachIndexed { index, split ->
            val normalised = ((paces[index] - quickest) / span).coerceIn(0.0, 1.0).toFloat()
            SplitColumn(
                split = split,
                normalised = normalised,
                isFastest = fastest != null && !split.isPartial && split.index == fastest.index,
                paceText = UnitFormatter.pace(split.averageSpeedMps, imperial)
                    .substringBefore(' '),
                label = if (split.isPartial) {
                    strings.splitRemainderShort
                } else {
                    split.index.toString()
                },
            )
        }
    }
}

@Composable
private fun SplitColumn(
    split: RideSplit,
    normalised: Float,
    isFastest: Boolean,
    paceText: String,
    label: String,
) {
    // A floor so the quickest split is still a bar rather than a hairline.
    val fraction = 0.20f + normalised * 0.80f
    val barColor = when {
        split.isPartial -> MaterialTheme.colorScheme.outline
        else -> lerp(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            normalised,
        )
    }

    Column(
        modifier = Modifier.width(SPLIT_BAR_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = paceText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isFastest) FontWeight.Bold else FontWeight.Normal,
            color = if (isFastest) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPLIT_PLOT_HEIGHT)
                .clip(RoundedCornerShape(4.dp))
                // A track behind every bar, so a short one reads as a low value rather than as a
                // slot nobody filled in.
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The whole chart as one sentence.
 *
 * A bar chart is unreadable to a screen reader element by element, so the splits are announced as
 * pace values with their labels — the same information a sighted reader takes from the picture.
 */
internal fun splitsAccessibilityText(
    splits: List<RideSplit>,
    imperial: Boolean,
    fastestIndex: Int?,
    strings: AppStrings,
): String = buildString {
    append(strings.splitsTitle)
    append(": ")
    append(
        splits.joinToString(", ") { split ->
            val label = if (split.isPartial) {
                strings.splitRemainder
            } else {
                "${split.index} ${UnitFormatter.distanceUnitLabel(imperial)}"
            }
            val pace = UnitFormatter.pace(split.averageSpeedMps, imperial)
            if (!split.isPartial && split.index == fastestIndex) {
                "$label $pace ${strings.splitFastest}"
            } else {
                "$label $pace"
            }
        }
    )
}
