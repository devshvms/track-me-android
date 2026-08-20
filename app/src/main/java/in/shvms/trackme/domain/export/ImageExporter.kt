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
    val includeTrackMeLockup: Boolean = true
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
            val bannerHeight = realH * AppConfig.OVERLAY_BANNER_HEIGHT_RATIO
            val bannerTop = realH - bannerHeight
            val paint = Paint().apply {
                color = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_COLOR else android.graphics.Color.WHITE
                alpha = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_ALPHA else 220
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, bannerTop, realW.toFloat(), realH.toFloat(), paint)
            
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (options.isDarkTheme) AppConfig.OVERLAY_TEXT_COLOR else android.graphics.Color.BLACK
                textSize = bannerHeight * 0.25f
                textAlign = Paint.Align.LEFT
            }
            
            val distanceStr = `in`.shvms.trackme.domain.UnitFormatter.rideDistance(
                rideWithPoints.ride.postRideCalculation?.distance ?: 0.0,
                usesImperialUnits(context)
            )
            
            val durationMillis = (rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime
            val seconds = durationMillis / 1000
            val durationStr = `in`.shvms.trackme.ui.history.compactDuration(durationMillis)
            
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(rideWithPoints.ride.startTime))
            
            val padding = realW * 0.05f
            
            val rideTitle = rideWithPoints.ride.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride"
            canvas.drawText(rideTitle, padding, bannerTop + bannerHeight * 0.4f, textPaint)
            
            textPaint.textSize = bannerHeight * 0.15f
            val statsList = mutableListOf<String>()
            if (options.showDate) statsList.add(dateStr)
            if (options.showDuration) statsList.add(durationStr)
            if (options.showDistance) statsList.add(distanceStr)
            
            canvas.drawText(statsList.joinToString(" • "), padding, bannerTop + bannerHeight * 0.7f, textPaint)
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
            // Geometry comes from the caller as frame fractions. Recomputing it here is what let
            // the preview and the export disagree: this used to take 20% of the frame *width*
            // while the preview took 20% of its height, which is 11% of a 9:16 story and 36% of a
            // 16:9. The fallback is the historical flush bottom band, for any caller that has not
            // been given a placement.
            val panel = options.statsPanel
                ?: StatsPanelRect(0f, 1f - AppConfig.OVERLAY_BANNER_HEIGHT_RATIO, 1f, 1f)
            val bannerLeft = panel.left * finalW
            val bannerRight = panel.right * finalW
            val bannerTop = panel.top * finalH
            val bannerBottom = panel.bottom * finalH
            val bannerHeight = (bannerBottom - bannerTop).toInt().coerceAtLeast(1)
            val bannerWidth = bannerRight - bannerLeft
            val corner = panel.cornerFraction * minOf(finalW, finalH)

            // Draw the banner
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_COLOR else android.graphics.Color.WHITE
                alpha = if (options.isDarkTheme) AppConfig.OVERLAY_BANNER_ALPHA else 220
                style = Paint.Style.FILL
            }
            if (corner > 0f) {
                canvas.drawRoundRect(bannerLeft, bannerTop, bannerRight, bannerBottom, corner, corner, paint)
            } else {
                canvas.drawRect(bannerLeft, bannerTop, bannerRight, bannerBottom, paint)
            }

            // Draw Text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (options.isDarkTheme) AppConfig.OVERLAY_TEXT_COLOR else android.graphics.Color.BLACK
                textSize = bannerHeight * 0.25f
                textAlign = if (panel.alignEnd) Paint.Align.RIGHT else Paint.Align.LEFT
            }

            val distanceStr = `in`.shvms.trackme.domain.UnitFormatter.rideDistance(
                rideWithPoints.ride.postRideCalculation?.distance ?: 0.0,
                usesImperialUnits(context)
            )
            
            val durationMillis = (rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime
            val seconds = durationMillis / 1000
            val durationStr = `in`.shvms.trackme.ui.history.compactDuration(durationMillis)
            
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(rideWithPoints.ride.startTime))
            
            // Padding is a fraction of the panel, not of the frame: a corner card is half the
            // width of a full band, and 5% of the frame would eat most of it.
            val padding = bannerWidth * 0.06f
            val textX = if (panel.alignEnd) bannerRight - padding else bannerLeft + padding

            val rideTitle = rideWithPoints.ride.title?.ifEmpty { "TrackMe Ride" } ?: "TrackMe Ride"
            canvas.drawText(rideTitle, textX, bannerTop + bannerHeight * 0.4f, textPaint)

            textPaint.textSize = bannerHeight * 0.15f
            val statsList = mutableListOf<String>()
            if (options.showDate) statsList.add(dateStr)
            if (options.showDuration) statsList.add(durationStr)
            if (options.showDistance) statsList.add(distanceStr)

            // A card stacks its figures; the band runs them inline. Same rule the preview uses.
            if (panel.stackFigures && statsList.size > 1) {
                val lineHeight = bannerHeight * 0.30f
                val firstBaseline = bannerTop + bannerHeight * 0.30f
                statsList.forEachIndexed { index, figure ->
                    canvas.drawText(figure, textX, firstBaseline + index * lineHeight, textPaint)
                }
            } else {
                canvas.drawText(statsList.joinToString(" • "), textX, bannerTop + bannerHeight * 0.66f, textPaint)
            }
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
