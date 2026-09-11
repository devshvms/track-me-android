package `in`.shvms.trackme.domain.export.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import `in`.shvms.trackme.domain.export.drawArtifactLink
import `in`.shvms.trackme.domain.export.isTrackMeArtifactDeepLink
import kotlin.math.max

/**
 * A lite basemap behind The Trace (SCOPE_1.8.9 §8): the captured map, and where the **map SDK**
 * says the route landed on it, in the bitmap's own pixels. Never re-derived here —
 * `EXPORT_SHARE_CONTRACTS.md` "Never re-derive the map projection".
 */
internal class MapBackdrop(
    val bitmap: Bitmap,
    val runs: List<List<PixelPoint>>,
    val joins: List<List<PixelPoint>>,
)

/**
 * Draws the five export templates (SCOPE_1.8.9 §6).
 *
 * Every dimension is designed on a 1080-px-wide canvas and scaled by the width actually rendered,
 * so the preview and the exported file are one picture at two resolutions — the `ExportRenderScale`
 * rule. Text arrives finished ([TemplateContent]); nothing here formats a number or reads a setting.
 */
internal object TemplateRenderer {

    const val DESIGN_WIDTH = 1080f

    fun render(
        template: ExportTemplateId,
        canvasSpec: TemplateCanvas,
        content: TemplateContent,
        typeface: Typeface,
        widthPx: Int = canvasSpec.widthPx,
        backdrop: MapBackdrop? = null,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = (width / canvasSpec.aspect).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scope = DrawScope(Canvas(bitmap), width, height, width / DESIGN_WIDTH, canvasSpec, typeface)
        when (template) {
            ExportTemplateId.TRACE -> scope.drawTrace(content, backdrop)
            ExportTemplateId.INSTRUMENT -> scope.drawInstrument(content)
            ExportTemplateId.STICKER -> scope.drawSticker(content)
            ExportTemplateId.HOUR -> scope.drawHour(content)
            ExportTemplateId.AWARD -> scope.drawAward(content)
        }
        return bitmap
    }
}

/** Brand tokens the templates draw with. Gold is `gold/earned` — semantic, never decoration (§6.4). */
internal object TemplateColors {
    const val CYAN = 0xFF29B6F6.toInt()
    const val GOLD = 0xFFE9B44C.toInt()
    val CYAN_PACE = intArrayOf(0xFF0E6E9E.toInt(), 0xFF29B6F6.toInt(), 0xFF8FE6FF.toInt())
    val GOLD_PACE = intArrayOf(0xFF8A6416.toInt(), 0xFFE9B44C.toInt(), 0xFFF7D98E.toInt())
}

/** The Hour's six skies. Top to bottom stops, then the ink that reads on each. */
internal class HourPalette(
    val sky: IntArray,
    val route: Int,
    val eyebrow: Int,
    val hero: Int,
    val unit: Int,
    val body: Int,
    val sun: Int,
    val scrim: Int,
) {
    companion object {
        private fun c(hex: Long) = hex.toInt()

        fun of(phase: LightPhase): HourPalette = when (phase) {
            LightPhase.DAWN -> HourPalette(
                intArrayOf(c(0xFF2A1E3C), c(0xFF5B3350), c(0xFFB0574B), c(0xFFE08A4C), c(0xFFF5B96E)),
                c(0xFFFFF3E2), c(0xFFFFD9B0), c(0xFFFFF6EC), c(0xFFF0C79E), c(0xFFEBC4A2), c(0xFFFFD9A0), c(0xFF12080A),
            )
            LightPhase.GOLDEN_MORNING, LightPhase.GOLDEN_EVENING -> HourPalette(
                intArrayOf(c(0xFF35220F), c(0xFF7A4A1F), c(0xFFC9782E), c(0xFFEDA64A), c(0xFFF8CF7E)),
                c(0xFFFFF6E6), c(0xFFFFE2B8), c(0xFFFFF8EE), c(0xFFF6D2A2), c(0xFFF0CFA6), c(0xFFFFE6B0), c(0xFF140B04),
            )
            LightPhase.DAY -> HourPalette(
                intArrayOf(c(0xFF0B3552), c(0xFF1D628A), c(0xFF3F93BD), c(0xFF86C3DE), c(0xFFCFE8F2)),
                c(0xFFFFFFFF), c(0xFFE3F4FF), c(0xFFFFFFFF), c(0xFFD6EBF7), c(0xFFD9ECF6), c(0xFFFFF7D6), c(0xFF06121C),
            )
            LightPhase.DUSK -> HourPalette(
                intArrayOf(c(0xFF1A1431), c(0xFF43285A), c(0xFF8E3A66), c(0xFFCF5F4E), c(0xFFEE9A58)),
                c(0xFFFFF0E8), c(0xFFFFCDB8), c(0xFFFFF4EE), c(0xFFF2C2AE), c(0xFFEDBFA9), c(0xFFFFC59A), c(0xFF10070E),
            )
            LightPhase.NIGHT -> HourPalette(
                intArrayOf(c(0xFF04070B), c(0xFF0A121C), c(0xFF10203A), c(0xFF15304F), c(0xFF1B3E62)),
                c(0xFFBFE9FF), c(0xFF8FCFF0), c(0xFFEAF6FF), c(0xFF9CC3DA), c(0xFFA9C8DA), c(0xFFDDE8F2), c(0xFF020409),
            )
        }
    }
}

/** Interpolates across evenly spaced colour stops. */
internal fun lerpColor(stops: IntArray, t: Float): Int {
    if (stops.size == 1) return stops[0]
    val scaled = t.coerceIn(0f, 1f) * (stops.size - 1)
    val index = scaled.toInt().coerceAtMost(stops.size - 2)
    val f = scaled - index
    val a = stops[index]
    val b = stops[index + 1]
    fun channel(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f)).toInt()
    return Color.argb(channel(24), channel(16), channel(8), channel(0))
}

/** A moving average over [radius] neighbours each side — enough to turn pace flicker into a gradient. */
private fun easeAlong(values: List<Float>, radius: Int = 8): List<Float> = values.indices.map { index ->
    var sum = 0f
    var count = 0
    for (j in maxOf(0, index - radius)..minOf(values.lastIndex, index + radius)) {
        sum += values[j]
        count++
    }
    sum / count
}

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private class DrawScope(
    val canvas: Canvas,
    val width: Int,
    val height: Int,
    /** Pixels per design unit. */
    val u: Float,
    val spec: TemplateCanvas,
    val typeface: Typeface,
) {
    /** The canvas height in design units. */
    val designHeight: Float get() = height / u

    fun px(value: Float): Float = value * u

    fun box(left: Float, top: Float, right: Float, bottom: Float) = PixelBox(px(left), px(top), px(right), px(bottom))

    fun paint(
        size: Float,
        color: Int,
        weight: Int = 400,
        tracking: Float = 0f,
        align: Paint.Align = Paint.Align.LEFT,
        tabular: Boolean = false,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = px(size)
        this.color = color
        textAlign = align
        letterSpacing = tracking
        if (tabular) fontFeatureSettings = "tnum"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            typeface = this@DrawScope.typeface
            fontVariationSettings = "'wght' $weight"
        } else {
            typeface = Typeface.create(this@DrawScope.typeface, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Shortens [value] with an ellipsis until it fits; a label never runs off the canvas. */
    fun fit(value: String, paint: Paint, maxWidthPx: Float): String {
        if (paint.measureText(value) <= maxWidthPx) return value
        val ellipsis = "…"
        var end = value.length
        while (end > 1 && paint.measureText(value, 0, end) + paint.measureText(ellipsis) > maxWidthPx) end--
        return value.substring(0, end).trimEnd() + ellipsis
    }

    fun text(value: String?, x: Float, baseline: Float, paint: Paint, maxWidth: Float? = null) {
        if (value.isNullOrEmpty()) return
        val drawn = maxWidth?.let { fit(value, paint, px(it)) } ?: value
        canvas.drawText(drawn, px(x), px(baseline), paint)
    }

    fun hairline(x0: Float, x1: Float, y: Float, color: Int) {
        canvas.drawRect(px(x0), px(y), px(x1), px(y) + max(1f, px(2f)), Paint().apply { this.color = color })
    }

    fun skyGradient(stops: IntArray) {
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, width * 0.25f, height.toFloat(), stops, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /** The sky colour under [point], along the same diagonal [skyGradient] draws. */
    fun skyAt(stops: IntArray, point: PixelPoint): Int {
        val dx = width * 0.25f
        val dy = height.toFloat()
        return lerpColor(stops, (point.x * dx + point.y * dy) / (dx * dx + dy * dy))
    }

    fun fadeToward(bottomColor: Int, fromY: Float, topAlpha: Int = 0, bottomAlpha: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, px(fromY), 0f, height.toFloat(),
                withAlpha(bottomColor, topAlpha), withAlpha(bottomColor, bottomAlpha), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, px(fromY), width.toFloat(), height.toFloat(), paint)
    }

    /** The hero number with its unit on the same baseline, shrunk until the pair fits [maxWidth]. */
    fun hero(value: String, unit: String, x: Float, baseline: Float, size: Float, color: Int, unitColor: Int, maxWidth: Float) {
        var heroSize = size
        var heroPaint = paint(heroSize, color, weight = 760, tracking = -0.035f, tabular = true)
        var unitPaint = paint(heroSize * 0.29f, unitColor, weight = 500)
        fun span() = heroPaint.measureText(value) + px(heroSize * 0.08f) + unitPaint.measureText(unit)
        while (heroSize > size * 0.5f && span() > px(maxWidth)) {
            heroSize *= 0.93f
            heroPaint = paint(heroSize, color, weight = 760, tracking = -0.035f, tabular = true)
            unitPaint = paint(heroSize * 0.29f, unitColor, weight = 500)
        }
        canvas.drawText(value, px(x), px(baseline), heroPaint)
        canvas.drawText(unit, px(x) + heroPaint.measureText(value) + px(heroSize * 0.08f), px(baseline), unitPaint)
    }

    /** The largest paint no wider than [maxWidth] for [value], from [size] down to half of it. */
    fun shrinkToFit(value: String, size: Float, maxWidth: Float, make: (Float) -> Paint): Paint {
        var current = size
        var candidate = make(current)
        while (current > size * 0.5f && candidate.measureText(value) > px(maxWidth)) {
            current *= 0.92f
            candidate = make(current)
        }
        return candidate
    }

    /** Up to three quiet columns: a small label over its value. */
    fun figureColumns(figures: List<TemplateFigure>, left: Float, right: Float, labelBaseline: Float, valueBaseline: Float, valueSize: Float, labelColor: Int, valueColor: Int) {
        if (figures.isEmpty()) return
        val shown = figures.take(3)
        val column = (right - left) / 3f
        val labelPaint = paint(28f, labelColor, weight = 600, tracking = 0.12f)
        val valuePaint = paint(valueSize, valueColor, weight = 500, tabular = true)
        shown.forEachIndexed { index, figure ->
            val x = left + column * index
            text(figure.label, x, labelBaseline, labelPaint, column - 16f)
            text(figure.value, x, valueBaseline, valuePaint, column - 16f)
        }
    }

    // --- Route ---

    fun project(content: TemplateContent, routeBox: PixelBox): Pair<List<List<PixelPoint>>, List<List<PixelPoint>>>? {
        val all = content.runs.flatten() + content.joins.flatten()
        val projection = RouteProjection.fit(all, routeBox) ?: return null
        return content.runs.map(projection::project) to content.joins.map(projection::project)
    }

    fun projectSegment(content: TemplateContent, routeBox: PixelBox, segment: List<`in`.shvms.trackme.domain.processor.RouteCoordinate>): List<PixelPoint>? {
        val projection = RouteProjection.fit(content.runs.flatten() + content.joins.flatten(), routeBox) ?: return null
        return segment.map(projection::project)
    }

    /**
     * The route: runs as solid strokes coloured along their length by [colorAt], joins as dots, and
     * the ends marked differently by construction — hollow start, solid finish — so direction of
     * travel reads without an arrow.
     */
    fun route(
        runs: List<List<PixelPoint>>,
        joins: List<List<PixelPoint>>,
        intensities: List<List<Float>>?,
        stroke: Float,
        colorAt: (Float) -> Int,
        /** The colour under a point, so the hollow start can be cut out of whatever it sits on. */
        groundAt: (PixelPoint) -> Int,
        markers: Boolean = true,
        hollowStart: Boolean = true,
    ) {
        val strokePx = px(stroke)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dots = Paint(line).apply {
            strokeWidth = strokePx * 0.7f
            color = withAlpha(colorAt(FLAT_PACE_INTENSITY), 120)
            pathEffect = DashPathEffect(floatArrayOf(0.1f, strokePx * 1.8f), 0f)
        }
        joins.filter { it.size >= 2 }.forEach { join ->
            canvas.drawPath(Path().apply { moveTo(join[0].x, join[0].y); join.drop(1).forEach { lineTo(it.x, it.y) } }, dots)
        }
        runs.forEachIndexed { index, run ->
            if (run.size < 2) return@forEachIndexed
            val values = intensities?.getOrNull(index)?.takeIf { it.size == run.size }
            val kept = decimate(run, max(1f, strokePx * 0.35f))
            if (values == null || values.all { it == values[0] }) {
                line.color = colorAt(values?.firstOrNull() ?: FLAT_PACE_INTENSITY)
                val path = Path().apply {
                    moveTo(run[kept[0]].x, run[kept[0]].y)
                    for (k in 1 until kept.size) lineTo(run[kept[k]].x, run[kept[k]].y)
                }
                canvas.drawPath(path, line)
            } else {
                // Colour follows a softened copy of the pace, taken along the thinned line. Raw
                // per-sample pace flickers, and round caps of alternating colours stack into beads.
                val eased = easeAlong(kept.map { values[it] })
                for (k in 1 until kept.size) {
                    val a = kept[k - 1]
                    val b = kept[k]
                    line.color = colorAt((eased[k - 1] + eased[k]) / 2f)
                    canvas.drawLine(run[a].x, run[a].y, run[b].x, run[b].y, line)
                }
            }
        }
        if (!markers) return
        val start = runs.firstOrNull { it.isNotEmpty() }?.first() ?: return
        val finish = runs.lastOrNull { it.isNotEmpty() }?.last() ?: return
        val markerColor = colorAt(1f)
        val ringRadius = strokePx * 1.3f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx * 0.55f
            color = markerColor
        }
        if (hollowStart) {
            canvas.drawCircle(start.x, start.y, ringRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = groundAt(start) })
            canvas.drawCircle(start.x, start.y, ringRadius, ring)
        } else {
            // On a ground nobody can know — the Sticker goes on someone's photo — a hollow ring cannot
            // be cut out of the line, so the start is a ring around a dot instead.
            canvas.drawCircle(start.x, start.y, ringRadius, ring)
            canvas.drawCircle(start.x, start.y, strokePx * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor })
        }
        canvas.drawCircle(finish.x, finish.y, strokePx * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor })
    }

    fun link(link: String?) {
        drawArtifactLink(canvas, width, height, link)
    }

    // --- The Trace ---

    private class TraceLayout(
        val route: FloatArray,
        val place: Float,
        val hero: Float,
        val heroSize: Float,
        val hairline: Float,
        val labels: Float,
        val values: Float,
        val valueSize: Float,
        val date: Float,
        val stroke: Float,
    )

    private fun traceLayout(): TraceLayout = when (spec) {
        TemplateCanvas.PORTRAIT -> TraceLayout(floatArrayOf(120f, 90f, 960f, 640f), 732f, 925f, 220f, 985f, 1050f, 1112f, 52f, 1195f, 15f)
        TemplateCanvas.SQUARE -> TraceLayout(floatArrayOf(120f, 70f, 960f, 470f), 552f, 720f, 190f, 772f, 832f, 890f, 48f, 962f, 14f)
        // 9:16 keeps every figure inside the centre 1080 x 1480 that Instagram's chrome leaves clear.
        else -> TraceLayout(floatArrayOf(120f, 250f, 960f, 1080f), 1195f, 1415f, 250f, 1480f, 1550f, 1615f, 56f, 1690f, 16f)
    }

    fun drawTrace(content: TemplateContent, backdrop: MapBackdrop?) {
        val layout = traceLayout()
        val ground = 0xFF0C151B.toInt()
        val routeBox = box(layout.route[0], layout.route[1], layout.route[2], layout.route[3])
        val geometry: Pair<List<List<PixelPoint>>, List<List<PixelPoint>>>?
        if (backdrop != null) {
            canvas.drawBitmap(backdrop.bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            // "Lite shade": the map is texture, not subject. One even veil keeps its shapes; a heavier
            // fall toward the figures keeps them legible whatever the map is doing underneath.
            canvas.drawColor(Color.argb(168, 12, 18, 24))
            fadeToward(0xFF080D11.toInt(), layout.place - 180f, bottomAlpha = 225)
            val sx = width / backdrop.bitmap.width.toFloat()
            val sy = height / backdrop.bitmap.height.toFloat()
            fun scaled(lines: List<List<PixelPoint>>) = lines.map { l -> l.map { PixelPoint(it.x * sx, it.y * sy) } }
            geometry = scaled(backdrop.runs) to scaled(backdrop.joins)
        } else {
            skyGradient(intArrayOf(0xFF101B23.toInt(), 0xFF080D11.toInt()))
            contours(layout.hairline)
            geometry = project(content, routeBox)
        }
        geometry?.let { (runs, joins) ->
            route(
                runs, joins, content.runIntensities, layout.stroke, { lerpColor(TemplateColors.CYAN_PACE, it) },
                groundAt = { ground },
                // Over a veiled map the ground under the start is not one colour.
                hollowStart = backdrop == null,
            )
        }
        text(content.placeLine, 120f, layout.place, paint(34f, TemplateColors.CYAN, weight = 600, tracking = 0.14f), 840f)
        hero(content.heroValue, content.heroUnit, 120f, layout.hero, layout.heroSize, 0xFFF2F7FA.toInt(), 0xFF5E7280.toInt(), 840f)
        hairline(120f, 960f, layout.hairline, 0xFF1E2C36.toInt())
        figureColumns(content.figures, 120f, 960f, layout.labels, layout.values, layout.valueSize, 0xFF5E7280.toInt(), 0xFFC8D6DF.toInt())
        text(content.dateLine, 120f, layout.date, paint(28f, 0xFF52646F.toInt(), weight = 500, tracking = 0.08f), 840f)
        link(content.link)
    }

    /** Three faint contour lines behind the figures — the only ornament, and it nods at the subject. */
    private fun contours(hairlineY: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(2f)
            color = withAlpha(0xFF182630.toInt(), 150)
        }
        listOf(-230f, -180f, -130f).forEach { offset ->
            val y = hairlineY + offset
            val path = Path().apply {
                moveTo(0f, px(y))
                quadTo(px(270f), px(y - 26f), px(540f), px(y))
                quadTo(px(810f), px(y + 26f), px(1080f), px(y - 8f))
            }
            canvas.drawPath(path, paint)
        }
    }

    // --- The Instrument ---

    fun drawInstrument(content: TemplateContent) {
        val ground = 0xFF0A1014.toInt()
        canvas.drawColor(ground)
        val left = 70f
        val right = 1010f
        val dateBaseline = designHeight - 64f
        val summaryBaseline = dateBaseline - 60f
        var cursor = summaryBaseline - 120f
        val section = if (spec == TemplateCanvas.SQUARE) 124f else 150f
        val splitsTop = if (content.splits.isNotEmpty()) (cursor - section).also { cursor = it - 22f } else null
        val elevationTop = content.elevation?.let { (cursor - section).also { top -> cursor = top - 22f } }
        val gridTop = 40f
        grid(left, gridTop, right, cursor)
        val routeBox = box(left + 40f, gridTop + 70f, right - 40f, cursor - 30f)
        project(content, routeBox)?.let { (runs, joins) ->
            route(runs, joins, null, 12f, { TemplateColors.CYAN }, groundAt = { ground })
        }
        content.fastestSegment?.let { segment ->
            projectSegment(content, routeBox, segment)?.takeIf { it.size >= 2 }?.let { points ->
                val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = px(15f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = TemplateColors.GOLD
                }
                canvas.drawPath(Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }, gold)
                // A legend rather than a label on the line: a label placed beside a route collides
                // with it on half of all shapes, and gold is the only gold thing on the canvas.
                canvas.drawRect(px(left + 24f), px(gridTop + 30f), px(left + 64f), px(gridTop + 37f), Paint().apply { color = TemplateColors.GOLD })
                text(content.fastestLabel, left + 78f, gridTop + 44f, paint(26f, TemplateColors.GOLD, weight = 600, tracking = 0.14f), 600f)
            }
        }
        val labelPaint = paint(26f, 0xFF53666F.toInt(), weight = 600, tracking = 0.14f)
        elevationTop?.let { top ->
            text(content.elevationLabel, left, top + 26f, labelPaint, right - left)
            content.elevation?.let { profile -> elevationBand(profile, box(left, top + 44f, right, top + section)) }
        }
        splitsTop?.let { top ->
            text(content.splitsLabel, left, top + 26f, labelPaint, right - left)
            splitBars(content.splits, box(left, top + 44f, right, top + section))
        }
        hero(content.heroValue, content.heroUnit, left, summaryBaseline, 104f, 0xFFE6EDF3.toInt(), 0xFF6E808C.toInt(), 470f)
        // Elevation already has its own band above; repeating it here is what overflowed the line.
        val figuresLine = content.figures
            .filterNot { it.role == FigureRole.ELEVATION && content.elevation != null }
            .joinToString("  ·  ") { it.value }
        text(figuresLine, 560f, summaryBaseline - 6f, paint(38f, 0xFFA9BAC5.toInt(), weight = 500, tabular = true), right - 560f)
        text(content.dateLine, left, dateBaseline, paint(26f, 0xFF4E606C.toInt(), weight = 500, tracking = 0.08f), right - left)
        link(content.link)
    }

    private fun grid(left: Float, top: Float, right: Float, bottom: Float) {
        val paint = Paint().apply { color = 0xFF16222B.toInt() }
        val thickness = max(1f, px(1.6f))
        var x = left
        while (x <= right + 0.5f) { canvas.drawRect(px(x), px(top), px(x) + thickness, px(bottom), paint); x += 117.5f }
        var y = top
        while (y <= bottom + 0.5f) { canvas.drawRect(px(left), px(y), px(right), px(y) + thickness, paint); y += 117.5f }
    }

    private fun elevationBand(profile: ElevationProfile, area: PixelBox) {
        val n = profile.heights.size
        if (n < 2) return
        val step = area.width / (n - 1)
        val outline = Path()
        profile.heights.forEachIndexed { index, h ->
            val x = area.left + step * index
            val y = area.bottom - h * area.height
            if (index == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        val fill = Path(outline).apply { lineTo(area.right, area.bottom); lineTo(area.left, area.bottom); close() }
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF132631.toInt() })
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(4f)
            strokeJoin = Paint.Join.ROUND
            color = 0xFF2E7EA3.toInt()
        })
    }

    private fun splitBars(bars: List<SplitBar>, area: PixelBox) {
        if (bars.isEmpty()) return
        val gap = if (bars.size > 30) px(2f) else px(6f)
        val barWidth = ((area.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(px(2f))
        val radius = minOf(barWidth / 2f, px(4f))
        bars.forEachIndexed { index, bar ->
            val x = area.left + index * (barWidth + gap)
            val top = area.bottom - bar.heightFraction * area.height
            val color = when {
                bar.isFastest -> TemplateColors.GOLD
                else -> lerpColor(intArrayOf(0xFF17394F.toInt(), 0xFF2E97C8.toInt()), bar.speedFraction)
            }
            canvas.drawRoundRect(
                RectF(x, top, x + barWidth, area.bottom), radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (bar.isPartial) withAlpha(color, 110) else color },
            )
        }
    }

    // --- The Sticker ---

    fun drawSticker(content: TemplateContent) {
        // Transparent everywhere outside the plate. The plate is translucent rather than absent: white
        // figures have to read on whatever photo the rider drops this onto, bright snow included.
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(168, 10, 15, 19) }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), px(64f), px(64f), plate)
        project(content, box(60f, 72f, 440f, 603f))?.let { (runs, joins) ->
            route(runs, joins, content.runIntensities, 11f, { lerpColor(TemplateColors.CYAN_PACE, it) }, groundAt = { 0 }, hollowStart = false)
        }
        text(content.heroValue, 500f, 262f, paint(170f, Color.WHITE, weight = 760, tracking = -0.035f, tabular = true), 530f)
        text(content.heroUnitLong, 506f, 322f, paint(28f, 0xFFA9BCC7.toInt(), weight = 600, tracking = 0.16f), 520f)
        hairline(500f, 1010f, 362f, withAlpha(0xFF3E525E.toInt(), 200))
        val valuePaint = paint(44f, 0xFFDCE7EE.toInt(), weight = 500, tabular = true)
        val labelPaint = paint(24f, 0xFF8FA3B0.toInt(), weight = 600, tracking = 0.12f)
        content.figures.take(2).forEachIndexed { index, figure ->
            val baseline = 440f + index * 66f
            text(figure.value, 500f, baseline, valuePaint, 300f)
            val valueWidth = valuePaint.measureText(figure.value) / u
            text(figure.label, 500f + valueWidth + 18f, baseline, labelPaint, 1010f - (500f + valueWidth + 18f))
        }
        text(content.placeLine ?: content.dateLine, 500f, 585f, paint(26f, 0xFF8FA3B0.toInt(), weight = 500, tracking = 0.06f), 510f)
        content.link?.takeIf(::isTrackMeArtifactDeepLink)?.let { url ->
            val linkPaint = paint(15f, withAlpha(0xFFA9BCC7.toInt(), 210), weight = 400, align = Paint.Align.RIGHT)
            canvas.drawText(url, px(1036f), px(640f), linkPaint)
        }
    }

    // --- The Award ---

    private class AwardLayout(
        val ringY: Float, val ringR: Float, val badge: Float, val badgeSize: Float, val caption: Float,
        val headline: Float, val headlineSize: Float, val subline: Float, val sublineSize: Float,
        val route: FloatArray, val stroke: Float, val hairline: Float, val hero: Float, val heroSize: Float,
        val figure1: Float, val figure2: Float, val date: Float,
    )

    private fun awardLayout(): AwardLayout = when (spec) {
        TemplateCanvas.PORTRAIT -> AwardLayout(250f, 125f, 276f, 92f, 322f, 490f, 86f, 548f, 38f, floatArrayOf(170f, 590f, 910f, 880f), 13f, 935f, 1100f, 160f, 1045f, 1100f, 1200f)
        TemplateCanvas.SQUARE -> AwardLayout(200f, 100f, 222f, 74f, 262f, 400f, 76f, 452f, 34f, floatArrayOf(200f, 490f, 880f, 720f), 12f, 770f, 905f, 140f, 860f, 905f, 985f)
        else -> AwardLayout(380f, 165f, 404f, 118f, 462f, 690f, 100f, 760f, 42f, floatArrayOf(150f, 830f, 930f, 1230f), 14f, 1300f, 1490f, 190f, 1420f, 1486f, 1590f)
    }

    fun drawAward(content: TemplateContent) {
        val award = content.award
        val layout = awardLayout()
        skyGradient(intArrayOf(0xFF1A1409.toInt(), 0xFF0D1116.toInt(), 0xFF080D11.toInt()))
        if (award != null) {
            ring(award, layout)
            // Shrunk to sit inside the ring: "PR" is two letters, but its translations are not.
            text(award.badge, 540f, layout.badge, shrinkToFit(award.badge, layout.badgeSize, layout.ringR * 1.45f) {
                paint(it, TemplateColors.GOLD, weight = 760, align = Paint.Align.CENTER)
            })
            text(award.badgeCaption, 540f, layout.caption, shrinkToFit(award.badgeCaption, 26f, layout.ringR * 1.5f) {
                paint(it, 0xFF8C7440.toInt(), weight = 600, tracking = 0.16f, align = Paint.Align.CENTER)
            })
            text(award.headline, 540f, layout.headline, paint(layout.headlineSize, 0xFFF5EFE0.toInt(), weight = 680, tracking = -0.02f, align = Paint.Align.CENTER), 960f)
            text(award.subline, 540f, layout.subline, paint(layout.sublineSize, 0xFF9A8A66.toInt(), weight = 450, align = Paint.Align.CENTER), 960f)
        }
        project(content, box(layout.route[0], layout.route[1], layout.route[2], layout.route[3]))?.let { (runs, joins) ->
            route(runs, joins, content.runIntensities, layout.stroke, { lerpColor(TemplateColors.GOLD_PACE, it) }, groundAt = { 0xFF0C1116.toInt() })
        }
        hairline(120f, 960f, layout.hairline, 0xFF2A2312.toInt())
        hero(content.heroValue, content.heroUnit, 120f, layout.hero, layout.heroSize, 0xFFF5EFE0.toInt(), 0xFF8A7B5C.toInt(), 490f)
        val figurePaint = paint(40f, 0xFFB9AC8E.toInt(), weight = 500, tabular = true)
        content.figures.getOrNull(0)?.let { text(it.value, 640f, layout.figure1, figurePaint, 320f) }
        content.figures.getOrNull(1)?.let { text(it.value, 640f, layout.figure2, figurePaint, 320f) }
        text(content.dateLine, 120f, layout.date, paint(26f, 0xFF6B5C3C.toInt(), weight = 500, tracking = 0.08f), 840f)
        link(content.link)
    }

    /**
     * The ring is information, not a badge shape: the muted arc is the old record as a share of this
     * ride, and the bright remainder is the new ground. With no record to beat it closes in gold.
     */
    private fun ring(award: AwardText, layout: AwardLayout) {
        val cx = px(540f)
        val cy = px(layout.ringY)
        val r = px(layout.ringR)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(4f)
            color = 0xFF2A2312.toInt()
        })
        fun arcPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(10f)
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        if (award.facts.previousBest == null) {
            canvas.drawArc(oval, -90f, 360f, false, arcPaint(TemplateColors.GOLD))
        } else {
            val previous = 360f * award.facts.previousFraction.coerceIn(0f, 0.97f)
            canvas.drawArc(oval, -90f, previous, false, arcPaint(0xFF6E5220.toInt()))
            canvas.drawArc(oval, -90f + previous, 360f - previous, false, arcPaint(TemplateColors.GOLD))
        }
    }

    // --- The Hour ---

    /** [lowSun] and [highSun] are x, y, radius: a low sun sits in the gap between route and text. */
    private class HourLayout(
        val route: FloatArray, val eyebrow: Float, val hero: Float, val heroSize: Float,
        val body: Float, val place: Float, val stroke: Float, val lowSun: FloatArray, val highSun: FloatArray,
    )

    private fun hourLayout(): HourLayout = when (spec) {
        TemplateCanvas.PORTRAIT -> HourLayout(floatArrayOf(120f, 90f, 960f, 640f), 760f, 950f, 190f, 1030f, 1092f, 15f, floatArrayOf(880f, 700f, 62f), floatArrayOf(1000f, 62f, 56f))
        TemplateCanvas.SQUARE -> HourLayout(floatArrayOf(140f, 70f, 940f, 470f), 575f, 742f, 170f, 814f, 870f, 14f, floatArrayOf(900f, 522f, 48f), floatArrayOf(1004f, 56f, 48f))
        else -> HourLayout(floatArrayOf(120f, 220f, 960f, 1060f), 1330f, 1530f, 214f, 1616f, 1680f, 16f, floatArrayOf(880f, 1195f, 96f), floatArrayOf(880f, 118f, 58f))
    }

    fun drawHour(content: TemplateContent) {
        val palette = HourPalette.of(content.light)
        val layout = hourLayout()
        skyGradient(palette.sky)
        sun(content.light, palette, layout)
        fadeToward(palette.scrim, designHeight * 0.5f, bottomAlpha = 220)
        project(content, box(layout.route[0], layout.route[1], layout.route[2], layout.route[3]))?.let { (runs, joins) ->
            route(runs, joins, null, layout.stroke, { withAlpha(palette.route, 245) }, groundAt = { skyAt(palette.sky, it) })
        }
        text(content.lightLine, 120f, layout.eyebrow, paint(34f, palette.eyebrow, weight = 600, tracking = 0.16f), 840f)
        hero(content.heroValue, content.heroUnit, 120f, layout.hero, layout.heroSize, palette.hero, palette.unit, 840f)
        text(content.figures.joinToString("  ·  ") { it.value }, 120f, layout.body, paint(44f, palette.body, weight = 500, tabular = true), 840f)
        text(content.placeLine, 120f, layout.place, paint(36f, withAlpha(palette.body, 215), weight = 500), 840f)
        link(content.link)
    }

    /**
     * The light source, placed where it can never sit under a figure: a low sun in the gap between the
     * route and the text, a high sun or the moon above the route.
     */
    private fun sun(phase: LightPhase, palette: HourPalette, layout: HourLayout) {
        val high = phase == LightPhase.DAY || phase == LightPhase.NIGHT
        val spot = if (high) layout.highSun else layout.lowSun
        val alpha = when (phase) {
            LightPhase.NIGHT -> 90
            LightPhase.DAY -> 120
            else -> 150
        }
        val cx = px(spot[0])
        val cy = px(spot[1])
        val r = px(if (phase == LightPhase.NIGHT) spot[2] * 0.7f else spot[2])
        canvas.drawCircle(cx, cy, r * 2.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 2.6f, withAlpha(palette.sun, alpha / 2), withAlpha(palette.sun, 0), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.sun, alpha) })
    }
}
