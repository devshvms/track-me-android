package `in`.shvms.trackme.ui.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import `in`.shvms.trackme.theme.AmberWarn
import `in`.shvms.trackme.theme.CyanBright
import `in`.shvms.trackme.theme.GreenGo
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * How route endpoints are marked on an exported image.
 *
 * Markers used to be a single on/off switch, which is the wrong shape for the decision: whether to
 * mark the route is one question, and what to mark it *with* is another, and only the second one
 * has an interesting answer. A cyan dot reads well on the default basemap and disappears on
 * satellite imagery; a shot framed around where a ride finished does not want its start marked at
 * all; and a picture headed for print or a monochrome layout wants neither brand colour.
 *
 * The same set applies to both previews so the choice means the same thing everywhere, even though
 * what gets marked differs — route endpoints on a single ride, each ride's start on an aggregate.
 */
enum class ExportMarkerStyle {
    /** No markers. The route line alone. */
    None,

    /** Green start, cyan finish, amber pause rings. The app's own palette, and the default. */
    StartFinish,

    /** Finish only — for a shot that is about where you ended up. */
    FinishOnly,

    /** Start and finish in black and white. Reads on satellite imagery and in print. */
    Mono,

    /** Google's standard teardrop pin, the shape every map app has trained people to read. */
    Pin;

    fun label(strings: AppStrings): String = when (this) {
        None -> strings.markerStyleNone
        StartFinish -> strings.markerStyleStartFinish
        FinishOnly -> strings.markerStyleFinishOnly
        Mono -> strings.markerStyleMono
        Pin -> strings.markerStylePin
    }

    /** Whether the start of the route carries a marker at all. */
    val marksStart: Boolean get() = this != None && this != FinishOnly

    /** Whether the end of the route carries a marker at all. */
    val marksFinish: Boolean get() = this != None

    /**
     * Whether auto-pause rings are drawn.
     *
     * Only on [StartFinish]. The other styles are deliberate reductions — a monochrome or
     * finish-only picture that still sprinkled amber rings down the route would not be either.
     */
    val marksPauses: Boolean get() = this == StartFinish
}

/**
 * Marker bitmaps for a style, at a given pixel size.
 *
 * Sizes are passed in rather than fixed because the preview and the export render the same picture
 * at different resolutions — see `ExportRenderScale`. A hardcoded 64px marker is a different visual
 * size on every surface.
 */
object ExportMarkers {

    fun start(style: ExportMarkerStyle, sizePx: Int): BitmapDescriptor? = when (style) {
        ExportMarkerStyle.None, ExportMarkerStyle.FinishOnly -> null
        ExportMarkerStyle.StartFinish -> circle(GreenGo.toArgb(), android.graphics.Color.WHITE, sizePx)
        ExportMarkerStyle.Mono -> circle(android.graphics.Color.WHITE, android.graphics.Color.BLACK, sizePx)
        ExportMarkerStyle.Pin -> pin(BitmapDescriptorFactory.HUE_GREEN)
    }

    fun finish(style: ExportMarkerStyle, sizePx: Int): BitmapDescriptor? = when (style) {
        ExportMarkerStyle.None -> null
        ExportMarkerStyle.StartFinish, ExportMarkerStyle.FinishOnly ->
            circle(CyanBright.toArgb(), android.graphics.Color.WHITE, sizePx)
        ExportMarkerStyle.Mono -> circle(android.graphics.Color.BLACK, android.graphics.Color.WHITE, sizePx)
        ExportMarkerStyle.Pin -> pin(BitmapDescriptorFactory.HUE_AZURE)
    }

    /**
     * The translucent ring marking an auto-pause. Null unless the style draws pauses.
     *
     * The parameter is `markerStyle`, not `style`: inside a `Paint` apply block, `style` would
     * resolve to this parameter rather than to `Paint.style`, which the compiler catches here but
     * would not if the types happened to line up.
     */
    fun pause(markerStyle: ExportMarkerStyle, sizePx: Int): BitmapDescriptor? {
        if (!markerStyle.marksPauses) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val stroke = (sizePx * 0.04f).coerceAtLeast(1.5f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = AmberWarn.copy(alpha = 0.2f).toArgb()
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - stroke, paint)
            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.argb(180, 255, 255, 255)
            paint.strokeWidth = stroke
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - stroke, paint)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }.getOrNull()
    }

    /**
     * The per-ride marker on an aggregate export.
     *
     * Aggregate markers carry a letter because they identify *which* ride starts there, so the
     * styles reduce differently: [ExportMarkerStyle.FinishOnly] has no meaning across several
     * rides and falls back to the lettered marker rather than vanishing, which would leave the
     * legend referring to letters that appear nowhere on the map.
     */
    fun aggregate(
        style: ExportMarkerStyle,
        context: Context,
        label: String,
        routeColor: Int,
        sizePx: Int
    ): BitmapDescriptor? = when (style) {
        ExportMarkerStyle.None -> null
        ExportMarkerStyle.Pin -> pin(BitmapDescriptorFactory.HUE_AZURE)
        ExportMarkerStyle.Mono -> letter(
            context, label, android.graphics.Color.BLACK, android.graphics.Color.WHITE, sizePx
        )
        else -> letter(context, label, routeColor, android.graphics.Color.WHITE, sizePx)
    }

    private fun pin(hue: Float): BitmapDescriptor? =
        runCatching { BitmapDescriptorFactory.defaultMarker(hue) }.getOrNull()

    private fun circle(fillColor: Int, ringColor: Int, sizePx: Int): BitmapDescriptor? = runCatching {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = (sizePx * 0.04f).coerceAtLeast(1.5f)
        val radius = sizePx / 2f - stroke
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, paint)
        paint.apply {
            color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }.getOrNull()

    private fun letter(
        context: Context,
        label: String,
        fillColor: Int,
        textColor: Int,
        sizePx: Int
    ): BitmapDescriptor? = runCatching {
        val size = sizePx.coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint)
        paint.apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            textSize = size * 0.42f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(label, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }.getOrNull()
}
