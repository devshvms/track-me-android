package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.domain.processor.RouteCoordinate

/** What a supporting figure is, so a template can place or drop it by meaning rather than position. */
internal enum class FigureRole { DURATION, ELEVATION, EFFORT }

/** One supporting figure: a quiet label and its value, both already localised and formatted. */
internal data class TemplateFigure(val role: FigureRole, val label: String, val value: String)

/** The Award's words, already chosen and localised; [facts] is what the ring is drawn from. */
internal data class AwardText(
    val facts: AwardFacts,
    /** "PR", "1st", "100". */
    val badge: String,
    /** "DISTANCE", "TIME", "RIDES". */
    val badgeCaption: String,
    /** "Longest yet". */
    val headline: String,
    /** "Previous best 38.2 km", or null when there was no record to beat. */
    val subline: String?,
)

/**
 * Everything a template draws, as finished text and geometry.
 *
 * Decided by the UI layer and handed over whole: `EXPORT_SHARE_CONTRACTS.md` §4 — **renderers
 * receive finished text; they never resolve strings or preferences themselves.** That rule is what
 * stopped the image and the video disagreeing about figures twice, and the templates inherit it
 * rather than relearning it.
 */
internal data class TemplateContent(
    /** Recorded runs of the (privacy-trimmed) route, from the same `RouteRenderPlan` every surface uses. */
    val runs: List<List<RouteCoordinate>>,
    /** Dotted joins across GPS gaps. */
    val joins: List<List<RouteCoordinate>>,
    /** Per point of each run, 0..1 pace intensity; null or mismatched means one flat colour. */
    val runIntensities: List<List<Float>>?,
    val heroValue: String,
    val heroUnit: String,
    /** The unit spelled out for the Sticker, where there is room for a word under the number. */
    val heroUnitLong: String,
    /** At most three, in display order. */
    val figures: List<TemplateFigure>,
    val dateLine: String,
    val placeLine: String?,
    val link: String?,
    val elevation: ElevationProfile?,
    val elevationLabel: String?,
    val splits: List<SplitBar>,
    val splitsLabel: String?,
    val fastestSegment: List<RouteCoordinate>?,
    val fastestLabel: String?,
    val award: AwardText?,
    val light: LightPhase,
    val lightLine: String?,
    /**
     * SCOPE_1.8.9 Part 2. Non-null only for a selection [AggregateSelection.shape] called a TOUR —
     * which is what stops the Itinerary rendering a list of unrelated places as though it were a
     * journey. Defaulted so every single-ride call site is untouched.
     */
    val itinerary: Itinerary? = null,
    /** The regions the selection touched, in the order ridden. Aggregate only. */
    val regions: Map<String, RegionRole> = emptyMap(),
    /**
     * "2 states · 5 districts", already formatted and localised by the caller — the same division
     * of labour as [lightLine]. The renderer has no `AppStrings`, and giving it one would put copy
     * decisions in the drawing layer.
     */
    val coverageLine: String? = null,
)
