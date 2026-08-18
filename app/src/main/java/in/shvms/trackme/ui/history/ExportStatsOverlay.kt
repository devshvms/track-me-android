package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * Where the information panel sits on an exported image, and how much of the frame it takes.
 *
 * ### Geometry lives here, not at the two places that draw it
 *
 * The panel is drawn twice — once by Compose over the live preview, once onto the bitmap by the
 * exporter — and those two implementations had already drifted: the preview used 20% of the frame
 * *height* while the exporter used 20% of its *width*, so the same setting produced 11% of height
 * on a 9:16 story and 36% on a 16:9. Expressing the rectangle once, in fractions of the frame, is
 * what stops that happening again.
 *
 * All values are normalised 0..1 against the rendered frame, so a placement means the same thing at
 * 1080×1080 as at 1080×1920 as in a 360dp preview.
 */
enum class StatsOverlayStyle {
    /** No panel. The map alone. */
    None,

    /** Full-width band across the bottom. What every export looked like before 1.8.0. */
    BottomBar,

    /** Rectangular card in the top-left corner. */
    TopLeft,

    /** Rectangular card in the top-right corner. */
    TopRight;

    // A half-width bottom band was offered briefly and removed: it read as a bottom bar someone
    // had truncated, and the corner cards already serve the "leave most of the frame clear" case
    // while also keeping the attribution corner free.

    fun label(strings: AppStrings): String = when (this) {
        None -> strings.statsOverlayNone
        BottomBar -> strings.statsOverlayBar
        TopLeft -> strings.statsOverlayTopLeft
        TopRight -> strings.statsOverlayTopRight
    }

    val isVisible: Boolean get() = this != None

    /**
     * The panel rectangle as fractions of the frame, or null when nothing is drawn.
     *
     * Heights are for the figures alone. They were sized for two lines when the panel repeated the
     * ride title above them — a name the sharer already knows and the viewer gets from the caption,
     * costing a fifth of the frame to say it.
     */
    fun rect(): OverlayRect? = when (this) {
        None -> null
        BottomBar -> OverlayRect(left = 0f, top = 0.88f, right = 1f, bottom = 1f, inset = 0f)
        // Corner cards stack their figures, so they are taller and narrower than the band.
        TopLeft -> OverlayRect(left = 0.03f, top = 0.03f, right = 0.44f, bottom = 0.22f, inset = 0.02f)
        TopRight -> OverlayRect(left = 0.56f, top = 0.03f, right = 0.97f, bottom = 0.22f, inset = 0.02f)
    }

    /** Corner cards read as cards; the flush bottom band does not. */
    val isCard: Boolean get() = this == TopLeft || this == TopRight

    /** Text alignment that keeps the panel's content away from the frame edge it sits against. */
    val alignsTextEnd: Boolean get() = this == TopRight

    /**
     * Whether the figures run on one line or stack.
     *
     * A full-width band has room for "date · duration · distance" and reads as a caption. A corner
     * card is under half that width, where the same string wraps mid-value or ellipsises — so it
     * stacks instead, which is what a card is for.
     */
    val stacksFigures: Boolean get() = isCard
}

/**
 * A panel rectangle in frame fractions.
 *
 * @param inset corner radius as a fraction of the frame's shorter edge. Zero for flush bands.
 */
data class OverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val inset: Float,
) {
    val widthFraction: Float get() = right - left
    val heightFraction: Float get() = bottom - top

    fun leftPx(frameWidth: Int): Float = left * frameWidth
    fun topPx(frameHeight: Int): Float = top * frameHeight
    fun rightPx(frameWidth: Int): Float = right * frameWidth
    fun bottomPx(frameHeight: Int): Float = bottom * frameHeight

    /** Corner radius in pixels, from the shorter edge so it is the same visual curve at any ratio. */
    fun cornerRadiusPx(frameWidth: Int, frameHeight: Int): Float =
        inset * minOf(frameWidth, frameHeight)
}

/**
 * Duration for a shared image: "2hr 4min", "8min", "45s".
 *
 * Not `HH:MM:SS`. A stopwatch readout is right while a ride is running, where the seconds are
 * moving and you are watching them. On a finished ride it asks the reader to parse `00:13:06` into
 * "thirteen minutes" — three fields, two of them usually zero or irrelevant, in the one place the
 * picture has least room and least of the reader's attention.
 */
fun compactDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}hr ${minutes}min"
        hours > 0 -> "${hours}hr"
        minutes > 0 -> "${minutes}min"
        // Sub-minute rides are usually accidents, but a blank where a duration should be reads as
        // a bug rather than as a very short ride.
        else -> "${seconds}s"
    }
}
