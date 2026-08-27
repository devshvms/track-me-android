package `in`.shvms.trackme.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One cell of a [StatGrid]. */
data class Stat(val label: String, val value: String)

/**
 * A row of read-only metrics, separated by hairlines.
 *
 * ### Why a component
 * Ride detail built its stats as two `Row`s of a local `StatItem`, which centred its text and
 * hardcoded `Color.Gray` for the label — a literal colour that ignores the theme entirely and is
 * the exact class of value the token layer exists to delete. Comparison and the ride card each had
 * their own near-copy.
 *
 * ### Tabular figures
 * Values are rendered with tabular numerals. Proportional digits have different widths, so a
 * distance ticking from 9.9 to 10.0 — or two stats sitting in adjacent cells — shift horizontally
 * as the numbers change. In a column of figures that reads as jitter.
 *
 * The separator is drawn as a background showing through 1dp gaps rather than as divider
 * composables, so cells stay evenly weighted regardless of how many there are.
 *
 * ### Two-line labels
 * A label gets exactly two lines, always. One line ellipsised "ELEVATION GAIN" to "ELEVATION G..."
 * on an ordinary phone, and English is not the worst case: `gpsSignalGaps` is 27 characters in
 * French, `elevationGain` 19 in Spanish, `maxGForce` 16 in Hindi. Shrinking the type or trimming
 * the padding until English fits would leave four locales still truncating, so the cell is sized
 * for wrapping instead. Fixed at two lines rather than one-or-two so the values stay on a common
 * line across cells — a row where one value sits lower than its neighbours reads as misalignment.
 */
@Composable
fun StatGrid(
  stats: List<Stat>,
  modifier: Modifier = Modifier,
) {
  if (stats.isEmpty()) return
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      .background(MaterialTheme.colorScheme.outlineVariant),
    horizontalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    stats.forEach { stat ->
      Column(
        modifier = Modifier
          .weight(1f)
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .padding(horizontal = 8.dp, vertical = 10.dp),
      ) {
        Text(
          text = stat.label.uppercase(),
          // SemiBold rather than smaller: at labelSmall the type is already 11sp, and shrinking a
          // secondary label further is the wrong lever on a screen that has to survive 200% text.
          // Weight buys the same separation from the value without costing legibility.
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          minLines = 2,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stat.value,
          style = MaterialTheme.typography.titleMedium.copy(
            fontFeatureSettings = "tnum",
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
