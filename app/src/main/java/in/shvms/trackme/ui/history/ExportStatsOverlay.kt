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
 * on a 9:16 story and 36% on a 16:9. Expressing the rectangle once, in fractions of the frame,
 * is what stops that happening again.
 *
 * All values are normalised 0..1 against the rendered frame, so a placement means the same thing
 * at 1080×1080 as at 1080×1920 as in a 360dp preview.
 */
enum class StatsOverlayStyle {
    /** No panel. The map alone. */
    None,

    /** Full-width band across the bottom. What every export has looked like until now. */
    BottomBar,

    /** Half-width band, bottom right. Leaves the Google attribution in the bottom-left clear. */
    BottomHalf,

    /** Rectangular card in the top-left corner. */
    TopLeft,

    /** Rectangular card in the top-right corner. */
    TopRight;

    fun label(strings: AppStrings): String = when (this) {
        None -> strings.statsOverlayNone
        BottomBar -> strings.statsOverlayBar
        BottomHalf -> strings.statsOverlayHalf
        TopLeft -> strings.statsOverlayTopLeft
        TopRight -> strings.statsOverlayTopRight
    }

    val isVisible: Boolean get() = this != None

    /**
     * The panel rectangle as fractions of the frame, or null when nothing is drawn.
     *
     * The corner cards are inset from the edge; the bottom bar is flush, because that is what it
     * has always been and changing it would alter every export anyone has already made.
     */
    fun rect(): OverlayRect? = when (this) {
        None -> null
        BottomBar -> OverlayRect(left = 0f, top = 0.80f, right = 1f, bottom = 1f, inset = 0f)
        BottomHalf -> OverlayRect(left = 0.50f, top = 0.82f, right = 1f, bottom = 1f, inset = 0f)
        TopLeft -> OverlayRect(left = 0.03f, top = 0.03f, right = 0.52f, bottom = 0.19f, inset = 0.02f)
        TopRight -> OverlayRect(left = 0.48f, top = 0.03f, right = 0.97f, bottom = 0.19f, inset = 0.02f)
    }

    /** Corner cards read as cards; the flush bottom band does not. */
    val isCard: Boolean get() = this == TopLeft || this == TopRight

    /** Text alignment that keeps the panel's content away from the frame edge it sits against. */
    val alignsTextEnd: Boolean get() = this == TopRight || this == BottomHalf
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
