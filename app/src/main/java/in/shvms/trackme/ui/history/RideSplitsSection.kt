package `in`.shvms.trackme.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.RideSplit
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.fastestSplit
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/**
 * Per-unit splits as a column chart, drawn in the same footprint as the line chart.
 *
 * ### Why bars in a fixed area rather than a list of rows
 *
 * A row per kilometre is fine for a 3 km walk and unusable for a 30 km one: the list runs off the
 * bottom of the screen and the ride stops being one picture. Dividing a fixed width by the number
 * of splits keeps the whole ride visible at any distance — the bars get narrower, not more
 * numerous-and-offscreen.
 *
 * ### What the height means
 *
 * Height is the pace itself: minutes per unit, so a **taller bar is a slower kilometre**. That is
 * the honest reading of a time-per-distance measure — more time is more bar — and the colour ramp
 * says the same thing a second way, so the chart cannot be read backwards. The horizontal-bar
 * convention (longer = faster) does not survive being turned on its side, because a tall bar reads
 * as "more", and more pace is slower.
 *
 * Heights are scaled against the split range rather than against zero. A ride whose kilometres run
 * 5:30 to 6:00 has a real story in those thirty seconds, and anchoring at zero would flatten it
 * into thirty identical bars.
 */
@Composable
fun RideSplitsSection(
    splits: List<RideSplit>,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    if (splits.isEmpty()) return
    val strings = LocalAppStrings.current
    val textMeasurer = rememberTextMeasurer()

    val fastest = remember(splits) { fastestSplit(splits) }
    val paces = remember(splits) {
        splits.map { UnitFormatter.paceSecondsPerUnit(it.averageSpeedMps.coerceAtLeast(0.01), imperial) }
    }
    // Full splits set the scale. A partial is measured over a shorter distance, so its pace is
    // noisier, and letting it define the range would squash every real kilometre.
    val scalePaces = remember(splits, paces) {
        splits.indices.filter { !splits[it].isPartial }.map { paces[it] }.ifEmpty { paces }
    }
    val slowest = scalePaces.max()
    val quickest = scalePaces.min()

    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fastColor = MaterialTheme.colorScheme.primary
    val slowColor = MaterialTheme.colorScheme.tertiary
    val partialColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = labelColor,
        fontWeight = FontWeight.Bold,
    )

    val description = remember(splits, imperial, strings) {
        splitsAccessibilityText(splits, imperial, fastest?.index, strings)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val count = splits.size
            if (count == 0) return@Canvas

            val labelHeight = 18.dp.toPx()
            val plotHeight = (size.height - labelHeight).coerceAtLeast(1f)
            val slotWidth = size.width / count
            // Gaps shrink with the slot so a thirty-kilometre ride does not become all gap.
            val gap = (slotWidth * 0.18f).coerceIn(1f, 6.dp.toPx())
            val barWidth = (slotWidth - gap).coerceAtLeast(1f)

            // A visible floor so the quickest kilometre is still a bar rather than a line.
            val minFraction = 0.18f
            val span = (slowest - quickest).takeIf { it > 0.001 } ?: 1.0

            splits.forEachIndexed { i, split ->
                val pace = paces[i]
                val normalised = ((pace - quickest) / span).coerceIn(0.0, 1.0).toFloat()
                val fraction = minFraction + normalised * (1f - minFraction)
                val barHeight = plotHeight * fraction
                val left = i * slotWidth + gap / 2f
                val top = plotHeight - barHeight

                // Track behind each bar, so a short bar still reads as a slot rather than as
                // empty space someone forgot to fill.
                drawRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, plotHeight),
                )
                drawRect(
                    color = when {
                        split.isPartial -> partialColor
                        else -> lerp(fastColor, slowColor, normalised.coerceIn(0f, 1f))
                    },
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                )

                // Labels only where they fit. Below about 22dp a split index is unreadable, and
                // drawing it anyway produces a smear along the axis.
                if (slotWidth >= 22.dp.toPx()) {
                    val text = if (split.isPartial) {
                        strings.splitRemainderShort
                    } else {
                        split.index.toString()
                    }
                    val layout = textMeasurer.measure(text, style = labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            left + (barWidth - layout.size.width) / 2f,
                            plotHeight + (labelHeight - layout.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The whole chart as one sentence.
 *
 * A bar chart is unreadable to a screen reader element by element — "1, 2, 3" tells nobody
 * anything — so the splits are announced as pace values with their labels, which is the same
 * information a sighted reader takes from the picture.
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

