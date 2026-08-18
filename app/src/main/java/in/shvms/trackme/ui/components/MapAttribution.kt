package `in`.shvms.trackme.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The size and position of Google's attribution mark, which everything here has to work around.
 *
 * The Maps SDK does not expose the logo's bounds, and it must never be covered — so these are
 * measured constants with headroom rather than guesses. They are in **dp**, because the logo is
 * drawn at a constant physical size regardless of the surface's pixel dimensions: the same map at
 * 1080px is a different fraction of the frame on a 2x device than on a 3x one.
 */
object MapAttribution {
    /** Width of the Google mark, with headroom. Anything drawn after this clears it. */
    const val GOOGLE_MARK_WIDTH_DP = 58f

    /** Height of the Google mark. The TrackMe mark matches it so the pair reads as one line. */
    const val GOOGLE_MARK_HEIGHT_DP = 20f

    /** The SDK's own inset from the bottom-left corner. */
    const val MARK_MARGIN_DP = 8f

    /** Gap either side of the separator. */
    const val SEPARATOR_GAP_DP = 4f

    /** Cap height of the wordmark, chosen to sit optically level with the Google mark. */
    const val WORDMARK_SIZE_SP = 13f
}

/**
 * "| TrackMe", set beside the map's own attribution.
 *
 * Placed to the **right** of Google's mark rather than anywhere else on the map, because the two
 * are the same kind of thing — who made the map, and who made the picture — and because every
 * other corner is either occupied by controls or, in an export, by the stats panel.
 *
 * Deliberately not a badge or a filled chip. It sits directly on the map with a text shadow, the
 * way Google's own mark does, so it reads on a light basemap and on a dark one without introducing
 * a surface that would compete with the map controls.
 *
 * Caller is responsible for aligning this to the bottom-start of the map; the left padding here
 * only clears Google's mark.
 */
@Composable
fun TrackMeMapAttribution(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    /**
     * The map's own `contentPadding` bottom, if it sets one.
     *
     * Google's mark is drawn **inside** the map's content padding, so a map that insets for a
     * navigation bar or for a control panel moves the mark up by exactly that much. Without the
     * same number here the wordmark stays pinned to the frame and the pair drifts apart -- which
     * is what happened on Home, whose map is full-bleed and pads for the nav bar.
     *
     * Pass the same value the map was given, not an estimate of it.
     */
    bottomOffset: Dp = 0.dp,
) {
    // A fixed-height row, bottom-aligned on the same inset the Google mark uses, with its content
    // centred inside. Padding the text alone lined up its *baseline* with the mark's *bottom edge*,
    // which reads as a wordmark sitting slightly low -- and drifted between screens, because the
    // text height varies with the font scale while the mark does not.
    Row(
        modifier = modifier
            .padding(
                start = (MapAttribution.MARK_MARGIN_DP + MapAttribution.GOOGLE_MARK_WIDTH_DP +
                    MapAttribution.SEPARATOR_GAP_DP).dp,
                bottom = MapAttribution.MARK_MARGIN_DP.dp + bottomOffset,
            )
            .height(MapAttribution.GOOGLE_MARK_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A shadow rather than a plate: the mark has to survive both a pale basemap and satellite
        // imagery, and a plate large enough to guarantee that would read as a UI element.
        val style = MaterialTheme.typography.labelMedium.copy(
            fontSize = MapAttribution.WORDMARK_SIZE_SP.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1f),
                blurRadius = 3f,
            ),
        )
        Text(text = "|", style = style.copy(color = tint.copy(alpha = 0.75f)))
        Text(text = " TrackMe", style = style)
    }
}
