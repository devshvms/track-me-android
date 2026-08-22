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

    /** Backwards-compatible default: a one-line, figures-only panel. Prefer [rect] with content. */
    fun rect(): OverlayRect? = rect(OverlayContent.FIGURES_ONLY_ONE_LINE)

    /**
     * The panel rectangle sized to the content it will actually hold — §8.3 contract 3.
     *
     * The old fixed rectangle is what the customer saw as "the grey overlay is too big": a corner
     * card was **19% of frame height whatever was in it**, so a single figure sat in a mostly empty
     * box, and a title drawn at a quarter of that height overflowed the card onto bare map.
     *
     * Height is therefore derived from the rendered line count rather than assumed. Width stays a
     * bound rather than a measurement: measuring text needs a `Paint` on one side and a
     * `TextMeasurer` on the other, and two independent measurements is precisely the drift §8.1
     * documents. Renderers ellipsise inside the bound instead.
     */
    fun rect(content: OverlayContent): OverlayRect? {
        if (this == None || content.isEmpty) return null
        val height = OverlayMetrics.panelHeightFraction(content.lineCount(stacksFigures))
        return when (this) {
            None -> null
            BottomBar -> OverlayRect(left = 0f, top = 1f - height, right = 1f, bottom = 1f, inset = 0f)
            TopLeft -> OverlayRect(left = 0.03f, top = 0.03f, right = 0.44f, bottom = 0.03f + height, inset = 0.02f)
            TopRight -> OverlayRect(left = 0.56f, top = 0.03f, right = 0.97f, bottom = 0.03f + height, inset = 0.02f)
        }
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


/**
 * Exactly what a stats panel renders, decided **once** for both the Compose preview and the bitmap
 * exporter — `SCOPE_1.8.4.md` §8.3 contract 1.
 *
 * ### Why this type exists
 *
 * 1.8.0 centralised the panel's *rectangle* in [OverlayRect] because the two renderers had drifted.
 * It never centralised the panel's *contents*, so they drifted again — and in both directions at
 * once: the exporter drew a ride title and a TrackMe lockup the preview did not, while the preview
 * drew a TrackMe attribution beside the Google mark that the exporter did not. A preview that is
 * wrong in both directions is worse than no preview, because the sharer trusts it.
 *
 * Pure by construction: no Android types, no formatting, no measurement. Both renderers consume the
 * same instance and neither composes its own list.
 */
data class OverlayContent(
    /** Date / duration / distance, already formatted, in display order. */
    val figures: List<String>,
) {
    val isEmpty: Boolean get() = figures.isEmpty()

    /**
     * How many rendered lines this content occupies.
     *
     * A card stacks its figures, so each is a line. A band runs them inline as one line — the
     * `" • "`-joined string — which is why the count is not simply `figures.size`.
     */
    fun lineCount(stacked: Boolean): Int = when {
        figures.isEmpty() -> 0
        stacked -> figures.size
        else -> 1
    }

    companion object {
        /** The shape [StatsOverlayStyle.rect] assumes when no content is supplied. */
        val FIGURES_ONLY_ONE_LINE = OverlayContent(figures = listOf(""))
    }
}

/**
 * The type scale and padding a panel is built from, as fractions of the frame's **shorter edge**.
 *
 * Fractions of the shorter edge rather than of height: a 16:9 and a 9:16 export of the same ride
 * should carry the same apparent text size, and anything keyed to height alone does not.
 */
object OverlayMetrics {
    /** Figure line height. */
    const val FIGURE_LINE_RATIO = 0.038f
    /** Breathing room above and below the text block, per edge. */
    const val VERTICAL_PADDING_RATIO = 0.016f
    /** Inner horizontal padding, per edge, as a fraction of the panel's own width. */
    const val HORIZONTAL_PADDING_FRACTION = 0.06f

    /**
     * Panel height as a fraction of frame height, for [lines] rendered lines.
     *
     * Assumes a square-ish frame for the shorter-edge conversion; the callers pass real pixel
     * dimensions to [OverlayRect] afterwards, so this only needs to be monotonic in [lines] and
     * stable across ratios — which it is, because every term is the same fraction.
     */
    fun panelHeightFraction(lines: Int): Float {
        if (lines <= 0) return 0f
        return lines * FIGURE_LINE_RATIO + 2 * VERTICAL_PADDING_RATIO
    }
}

/**
 * Selects the panel's lines from already-formatted parts — the one place that decides what a share
 * image says.
 *
 * **No ride title.** 1.8.0 removed it deliberately: it is a name the sharer already knows and the
 * viewer gets from the caption, and it cost a fifth of the frame to repeat. The preview honoured
 * that; the exporter never did, which is the defect §8 was raised for. Confirmed by shvm 2026-08-22
 * — the file drops the title rather than the preview gaining one.
 *
 * Takes formatted strings rather than a ride so it stays pure and testable: the two renderers each
 * format with the same shared helpers ([compactDuration], `UnitFormatter`), but the *choice* of what
 * appears, and in what order, is made exactly once, here.
 */
fun buildOverlayContent(
    date: String,
    duration: String,
    distance: String,
    showDate: Boolean,
    showDuration: Boolean,
    showDistance: Boolean,
): OverlayContent = OverlayContent(
    figures = buildList {
        if (showDate) add(date)
        if (showDuration) add(duration)
        if (showDistance) add(distance)
    },
)
