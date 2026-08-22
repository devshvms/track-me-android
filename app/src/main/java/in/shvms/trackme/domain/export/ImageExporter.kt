package `in`.shvms.trackme.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class ExportOptions(
    val showStats: Boolean = true,
    /**
     * The panel rectangle in frame fractions, or null for the historical flush bottom band.
     *
     * The geometry is decided in the UI layer and carried here rather than recomputed, because the
     * preview and the exporter drawing the same panel from two independent formulas is exactly how
     * they came to disagree: one used 20% of the frame height and the other 20% of its width.
     */
    val statsPanel: StatsPanelRect? = null,
    val isDarkTheme: Boolean = true,
    val showDistance: Boolean = true,
    val showDuration: Boolean = true,
    val showDate: Boolean = true,
    val routePoints: List<`in`.shvms.trackme.data.local.entity.GPSPointEntity>? = null,
    val includeTrackMeLockup: Boolean = true,
    /**
     * The panel's figure lines, already formatted and ordered by the UI — **decided there, not
     * re-derived here.** Null falls back to the legacy in-exporter derivation.
     *
     * 1.8.0 centralised the panel's *geometry* after the preview and the exporter drifted. It never
     * centralised its *contents*, so they drifted again (SCOPE_1.8.4 §8.1). Primitives rather than
     * the UI's `OverlayContent`, for the same reason `StatsPanelRect` mirrors `OverlayRect` — the
     * domain does not depend on the UI layer.
     *
     * There is deliberately **no title field**: the panel carries figures only (§8.3).
     */
    val overlayFigures: List<String>? = null,
    /** Draw the TrackMe wordmark beside the map's Google attribution, as the preview does. */
    val includeMapAttribution: Boolean = true
)

/**
 * A stats-panel rectangle in frame fractions, plus how its text is aligned and rounded.
 *
 * A plain data carrier so the domain layer does not depend on the UI enum that produced it.
 */
data class StatsPanelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Corner radius as a fraction of the frame's shorter edge. Zero for a flush band. */
    val cornerFraction: Float = 0f,
    val alignEnd: Boolean = false,
    /** Corner cards stack their figures; a full-width band runs them on one line. */
    val stackFigures: Boolean = false,
)

interface ImageExporter {
    suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap? = null, options: ExportOptions = ExportOptions()): File
}

private fun usesImperialUnits(context: Context): Boolean {
    (context.applicationContext as? TrackMeApp)?.let { return it.preferencesManager.unitSystem.value == "imperial" }

    val preferences = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
    val stored = preferences.getString("unit_system", null)
    if (stored != null) return stored == "imperial"

    // Match AppPreferencesManager's first-launch locale default for exported share cards.
    return Locale.getDefault().country.uppercase() in setOf("US", "GB", "MM", "LR")
}

internal fun staticMapRequestDimensions(
    ratioWidth: Int,
    ratioHeight: Int,
    maxDimension: Int = 640
): Pair<Int, Int> {
    val safeWidth = ratioWidth.coerceAtLeast(1)
    val safeHeight = ratioHeight.coerceAtLeast(1)
    val max = maxDimension.coerceAtLeast(1).toFloat()
    val ratioScale = minOf(max / safeWidth, max / safeHeight)
    return Pair(
        (safeWidth * ratioScale).roundToInt().coerceAtLeast(1),
        (safeHeight * ratioScale).roundToInt().coerceAtLeast(1)
    )
}

/**
 * Fallback implementation using Google Maps Static API.
 * We are not using this by default because it requires the "Maps Static API" to be enabled
 * and linked to an active billing account. It charges $2.00 per 1000 requests.
 * Kept here in case the native snapshot method has issues on certain devices.
 */
class GoogleStaticApiImageExporterImpl : ImageExporter {
    override suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap?, options: ExportOptions): File = withContext(Dispatchers.IO) {
        val points = options.routePoints ?: rideWithPoints.points
        val step = maxOf(1, points.size / 300)
        val sampledPoints = points.filterIndexed { index, _ -> index % step == 0 }
            .map { LatLng(it.latitude, it.longitude) }
            
        val encodedPath = PolyUtil.encode(sampledPoints)
        val apiKey = context.getString(R.string.google_maps_key)
        
        val (reqW, reqH) = staticMapRequestDimensions(ratioWidth, ratioHeight)
        val realW = reqW * AppConfig.HQ_IMAGE_SCALE
        val realH = reqH * AppConfig.HQ_IMAGE_SCALE
        
        val urlString = "${AppConfig.STATIC_MAP_BASE_URL}?size=${reqW}x${reqH}&scale=${AppConfig.HQ_IMAGE_SCALE}&path=color:${AppConfig.MAP_LINE_COLOR}|weight:${AppConfig.MAP_LINE_WEIGHT}|enc:$encodedPath&key=$apiKey"
        
        val connection = URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("X-Android-Package", context.packageName)
        connection.setRequestProperty("X-Android-Cert", "CBD91D52A6677AC615872A0CA72E172B9E82062E") 
        
        if (connection.responseCode != 200) {
            val errorString = connection.errorStream?.bufferedReader()?.use { it.readText() }
            if (connection.responseCode == 403 && errorString?.contains("not activated") == true) {
                throw Exception("Maps Static API is not enabled on your Google Cloud Project! Please enable it.")
            }
            throw Exception("HTTP ${connection.responseCode}: $errorString")
        }
        
        val inputStream = connection.inputStream
        val mapBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        
        val finalBitmap = Bitmap.createBitmap(realW, realH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        canvas.drawBitmap(mapBitmap, 0f, 0f, null)

        if (options.includeTrackMeLockup) {
            drawTrackMeLockup(canvas, context, realW, realH)
        }
        
        if (options.showStats) {
            drawStatsPanel(canvas, context, rideWithPoints, options, realW, realH)
        }

        if (options.includeMapAttribution) {
            drawMapAttribution(canvas, realW, realH)
        }

        val exportsDir = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME)
        if (!exportsDir.exists()) exportsDir.mkdirs()
        val file = File(exportsDir, "${AppConfig.IMAGE_FILE_PREFIX}${rideWithPoints.ride.id}.png")
        FileOutputStream(file).use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        mapBitmap.recycle()
        finalBitmap.recycle()
        
        file
    }
}

/**
 * Default implementation using a native snapshot of the currently rendered GoogleMap.
 * This is 100% free and does not require network calls or billing accounts.
 */
class NativeSnapshotImageExporterImpl : ImageExporter {
    override suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap?, options: ExportOptions): File = withContext(Dispatchers.IO) {
        if (mapSnapshot == null) {
            throw IllegalArgumentException("mapSnapshot cannot be null for NativeSnapshotImageExporterImpl")
        }

        val finalW = mapSnapshot.width
        val finalH = mapSnapshot.height

        val finalBitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        
        // Draw the map
        canvas.drawBitmap(mapSnapshot, 0f, 0f, null)

        if (options.includeTrackMeLockup) {
            drawTrackMeLockup(canvas, context, finalW, finalH)
        }
        
        if (options.showStats) {
            drawStatsPanel(canvas, context, rideWithPoints, options, finalW, finalH)
        }

        if (options.includeMapAttribution) {
            drawMapAttribution(canvas, finalW, finalH)
        }

        
        val exportsDir = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME)
        if (!exportsDir.exists()) exportsDir.mkdirs()
        val file = File(exportsDir, "${AppConfig.IMAGE_FILE_PREFIX}${rideWithPoints.ride.id}_native.png")
        FileOutputStream(file).use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        finalBitmap.recycle()
        // We do not recycle mapSnapshot here because it might be used by the framework or caller
        
        file
    }
}

/**
 * Type scale for the overlay, as fractions of the frame's **shorter edge**.
 *
 * Shorter edge rather than height: a 16:9 and a 9:16 export of the same ride should carry the same
 * apparent text size, and anything keyed to height alone does not. Mirrors `OverlayMetrics` in the
 * UI layer, which sizes the panel these numbers have to fit inside.
 */
private const val OVERLAY_FIGURE_TEXT_RATIO = 0.029f
private const val OVERLAY_TOP_PADDING_FRACTION = 0.18f
private const val OVERLAY_LINE_ADVANCE = 1.35f

/**
 * Draws the stats panel — **the only place either exporter draws one.**
 *
 * Both implementations previously carried their own copy of this, which is how the file came to
 * show a ride title the preview did not (SCOPE_1.8.4 §8.1). One function, called twice.
 */
private fun drawStatsPanel(
    canvas: Canvas,
    context: Context,
    rideWithPoints: RideWithPoints,
    options: ExportOptions,
    frameWidth: Int,
    frameHeight: Int,
) {
    // Geometry comes from the caller as frame fractions. Recomputing it here is what let the preview
    // and the export disagree once before: this used to take 20% of the frame *width* while the
    // preview took 20% of its height. The fallback is the historical flush bottom band.
    val panel = options.statsPanel
        ?: StatsPanelRect(0f, 1f - AppConfig.OVERLAY_BANNER_HEIGHT_RATIO, 1f, 1f)
    val left = panel.left * frameWidth
    val right = panel.right * frameWidth
    val top = panel.top * frameHeight
    val bottom = panel.bottom * frameHeight
    val width = right - left
    val height = (bottom - top).coerceAtLeast(1f)
    val corner = panel.cornerFraction * minOf(frameWidth, frameHeight)

    val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_COLOR else android.graphics.Color.WHITE
        alpha = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_ALPHA else 220
        style = Paint.Style.FILL
    }
    if (corner > 0f) {
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, panelPaint)
    } else {
        canvas.drawRect(left, top, right, bottom, panelPaint)
    }

    // Contents come from the UI's decision, never from a second derivation here — §8.3 contract 1.
    // The fallback keeps any caller not yet passing them rendering what it always did.
    val figures = options.overlayFigures ?: buildList {
        if (options.showDate) {
            add(SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(rideWithPoints.ride.startTime)))
        }
        if (options.showDuration) {
            add(
                `in`.shvms.trackme.ui.history.compactDuration(
                    (rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime
                )
            )
        }
        if (options.showDistance) {
            add(
                `in`.shvms.trackme.domain.UnitFormatter.rideDistance(
                    rideWithPoints.ride.postRideCalculation?.distance ?: 0.0,
                    usesImperialUnits(context)
                )
            )
        }
    }
    if (figures.isEmpty()) return

    val shorterEdge = minOf(frameWidth, frameHeight).toFloat()
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (options.isDarkTheme) AppConfig.OVERLAY_TEXT_COLOR else android.graphics.Color.BLACK
        textAlign = if (panel.alignEnd) Paint.Align.RIGHT else Paint.Align.LEFT
    }

    // Padding is a fraction of the panel, not of the frame: a corner card is half the width of a
    // full band, and 5% of the frame would eat most of it.
    val padding = width * 0.06f
    val textX = if (panel.alignEnd) right - padding else left + padding
    // Every line is ellipsised to the panel's inner width. The reported defect is a long title drawn
    // right-aligned from the panel edge and running clean off it onto bare map — a share image has
    // no layout pass to catch overflow, so whatever is drawn is in the file.
    val innerWidth = (width - 2 * padding).coerceAtLeast(1f)

    var baseline = top + height * OVERLAY_TOP_PADDING_FRACTION
    textPaint.textSize = shorterEdge * OVERLAY_FIGURE_TEXT_RATIO
    if (panel.stackFigures) {
        figures.forEach { figure ->
            baseline += textPaint.textSize * OVERLAY_LINE_ADVANCE
            canvas.drawText(ellipsise(figure, textPaint, innerWidth), textX, baseline, textPaint)
        }
    } else if (figures.isNotEmpty()) {
        baseline += textPaint.textSize * OVERLAY_LINE_ADVANCE
        canvas.drawText(ellipsise(figures.joinToString(" • "), textPaint, innerWidth), textX, baseline, textPaint)
    }
}

/** Truncates [text] with an ellipsis so it cannot render wider than [maxWidth]. */
internal fun ellipsise(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val ellipsis = "…"
    var end = text.length
    while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) end--
    return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
}

/**
 * The TrackMe wordmark beside the map's own Google attribution.
 *
 * The preview has always drawn this and the file never did, so the preview was wrong in this
 * direction too (§8.1). Beside the Google mark, never over it — covering another party's required
 * attribution is not ours to do.
 */
private fun drawMapAttribution(canvas: Canvas, width: Int, height: Int) {
    val shorterEdge = minOf(width, height).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = shorterEdge * 0.026f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(shorterEdge * 0.006f, 0f, 0f, android.graphics.Color.argb(180, 0, 0, 0))
    }
    canvas.drawText("TrackMe", width * 0.30f, height - shorterEdge * 0.022f, paint)
}

private fun drawTrackMeLockup(canvas: Canvas, context: Context, width: Int, height: Int) {
    val margin = (width * AppConfig.LOCKUP_MARGIN_RATIO).roundToInt().coerceAtLeast(8)
    val iconSize = (width * AppConfig.LOCKUP_ICON_RATIO).roundToInt().coerceAtLeast(32)
    val icon = BitmapFactory.decodeResource(context.resources, R.drawable.ic_trackme_logo) ?: return
    val scaledIcon = if (icon.width == iconSize && icon.height == iconSize) {
        icon
    } else {
        Bitmap.createScaledBitmap(icon, iconSize, iconSize, true)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = iconSize * 0.42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val text = "TrackMe"
    val textWidth = textPaint.measureText(text)
    val gap = (iconSize * 0.18f).roundToInt()
    val lockupWidth = iconSize + gap + textWidth
    val left = (width - margin - lockupWidth).coerceAtLeast(margin.toFloat())
    val top = margin.toFloat()

    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 18, 22, 28)
    }
    val lockupPadding = (iconSize * 0.16f).roundToInt()
    canvas.drawRoundRect(
        android.graphics.RectF(
            left - lockupPadding,
            top - lockupPadding,
            left + lockupWidth + lockupPadding,
            top + iconSize + lockupPadding
        ),
        lockupPadding.toFloat(),
        lockupPadding.toFloat(),
        backgroundPaint
    )

    canvas.drawBitmap(scaledIcon, left, top, null)
    val baseline = top + iconSize * 0.68f
    canvas.drawText(text, left + iconSize + gap, baseline, textPaint)

    if (scaledIcon !== icon) scaledIcon.recycle()
    icon.recycle()
}
