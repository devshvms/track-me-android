package `in`.shvms.trackme.domain.export.template

/**
 * The five export templates (SCOPE_1.8.9 §6). Declaration order is the strip's order, and it is
 * stable: The Award sits last so that its appearing and disappearing never moves the others.
 */
enum class ExportTemplateId(val analyticsValue: String) {
    TRACE("trace"),
    INSTRUMENT("instrument"),
    STICKER("sticker"),
    HOUR("hour"),
    AWARD("award"),
}

/** §9.3 — forced by the data: an aggregate has no single reveal, no single split table, no single hour. */
enum class TemplateScope { SINGLE, AGGREGATE, BOTH }

/**
 * The canvases a template is designed for, at the destination's real pixel size — Instagram wants
 * 1080 × 1920 for a story and upscales anything smaller into the soft look that reads as "made by
 * an app".
 */
enum class TemplateCanvas(val widthPx: Int, val heightPx: Int, val label: String) {
    STORY(1080, 1920, "9:16"),
    PORTRAIT(1080, 1350, "4:5"),
    SQUARE(1080, 1080, "1:1"),

    /** The Sticker's own card. Sized to its content, not to a destination frame — it goes *on* one. */
    CARD(1080, 675, "");

    val aspect: Float get() = widthPx.toFloat() / heightPx
}

/** A template as data rather than as a renderer (SCOPE_1.8.9 §5). */
data class ExportTemplateSpec(
    val id: ExportTemplateId,
    val scope: TemplateScope,
    /** First is the default. */
    val canvases: List<TemplateCanvas>,
    val transparent: Boolean = false,
    val supportsMapBackground: Boolean = false,
) {
    val defaultCanvas: TemplateCanvas get() = canvases.first()
}

object ExportTemplates {

    val all: List<ExportTemplateSpec> = listOf(
        ExportTemplateSpec(
            ExportTemplateId.TRACE, TemplateScope.BOTH,
            listOf(TemplateCanvas.STORY, TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE),
            supportsMapBackground = true,
        ),
        ExportTemplateSpec(
            ExportTemplateId.INSTRUMENT, TemplateScope.SINGLE,
            // 4:5 first: detail rewards a reader who stops, and a story frame gets a second and a half.
            listOf(TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE, TemplateCanvas.STORY),
        ),
        ExportTemplateSpec(
            ExportTemplateId.STICKER, TemplateScope.BOTH,
            listOf(TemplateCanvas.CARD),
            transparent = true,
        ),
        ExportTemplateSpec(
            ExportTemplateId.HOUR, TemplateScope.SINGLE,
            listOf(TemplateCanvas.STORY, TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE),
        ),
        ExportTemplateSpec(
            ExportTemplateId.AWARD, TemplateScope.SINGLE,
            listOf(TemplateCanvas.STORY, TemplateCanvas.PORTRAIT, TemplateCanvas.SQUARE),
        ),
    )

    fun spec(id: ExportTemplateId): ExportTemplateSpec = all.first { it.id == id }

    /**
     * The canvas to render [id] at. The user's last choice is kept when the template was designed
     * for it; otherwise the template's own default. A template never renders at a ratio it was not
     * designed for — this is the one refinement to §9.2's "ratio is global".
     */
    fun canvasFor(id: ExportTemplateId, preferred: TemplateCanvas?): TemplateCanvas {
        val spec = spec(id)
        return preferred?.takeIf { it in spec.canvases } ?: spec.defaultCanvas
    }

    /**
     * The ratios offered for [id], in one fixed order for every template. The spec lists its native
     * canvas first; chips that reshuffle under the thumb on each template change read as new options.
     */
    fun canvasChoices(id: ExportTemplateId): List<TemplateCanvas> = TemplateCanvas.entries.filter { it in spec(id).canvases }
}
