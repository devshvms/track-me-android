package `in`.shvms.trackme.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import `in`.shvms.trackme.R
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

interface ImageExporter {
    suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap? = null, showStats: Boolean = true): File
}

/**
 * Fallback implementation using Google Maps Static API.
 * We are not using this by default because it requires the "Maps Static API" to be enabled
 * and linked to an active billing account. It charges $2.00 per 1000 requests.
 * Kept here in case the native snapshot method has issues on certain devices.
 */
class GoogleStaticApiImageExporterImpl : ImageExporter {
    override suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap?, showStats: Boolean): File = withContext(Dispatchers.IO) {
        val points = rideWithPoints.points
        val step = maxOf(1, points.size / 300)
        val sampledPoints = points.filterIndexed { index, _ -> index % step == 0 }
            .map { LatLng(it.latitude, it.longitude) }
            
        val encodedPath = PolyUtil.encode(sampledPoints)
        val apiKey = context.getString(R.string.google_maps_key)
        
        val reqW = 640
        val reqH = (640f * (ratioHeight.toFloat() / ratioWidth.toFloat())).toInt().coerceAtMost(640)
        val realW = reqW * AppConfig.HQ_IMAGE_SCALE
        val realH = reqH * AppConfig.HQ_IMAGE_SCALE
        
        val urlString = "${AppConfig.STATIC_MAP_BASE_URL}?size=${reqW}x${reqH}&scale=${AppConfig.HQ_IMAGE_SCALE}&path=color:${AppConfig.MAP_LINE_COLOR}|weight:${AppConfig.MAP_LINE_WEIGHT}|enc:$encodedPath&key=$apiKey"
        
        val connection = URL(urlString).openConnection() as java.net.HttpURLConnection
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
        
        if (showStats) {
            val bannerHeight = realH * AppConfig.OVERLAY_BANNER_HEIGHT_RATIO
            val bannerTop = realH - bannerHeight
            val paint = Paint().apply {
                color = AppConfig.OVERLAY_BANNER_COLOR
                alpha = AppConfig.OVERLAY_BANNER_ALPHA
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, bannerTop, realW.toFloat(), realH.toFloat(), paint)
            
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AppConfig.OVERLAY_TEXT_COLOR
                textSize = bannerHeight * 0.25f
                textAlign = Paint.Align.LEFT
            }
            
            val distanceKm = (rideWithPoints.ride.postRideCalculation?.distance ?: 0.0) / 1000.0
            val distanceStr = String.format(Locale.getDefault(), "%.2f km", distanceKm)
            
            val durationMillis = (rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime
            val seconds = durationMillis / 1000
            val durationStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(rideWithPoints.ride.startTime))
            
            val padding = realW * 0.05f
            
            canvas.drawText("TrackMe Ride", padding, bannerTop + bannerHeight * 0.4f, textPaint)
            
            textPaint.textSize = bannerHeight * 0.15f
            canvas.drawText("$dateStr • $durationStr • $distanceStr", padding, bannerTop + bannerHeight * 0.7f, textPaint)
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
    override suspend fun export(rideWithPoints: RideWithPoints, ratioWidth: Int, ratioHeight: Int, context: Context, mapSnapshot: Bitmap?, showStats: Boolean): File = withContext(Dispatchers.IO) {
        if (mapSnapshot == null) {
            throw IllegalArgumentException("mapSnapshot cannot be null for NativeSnapshotImageExporterImpl")
        }

        val finalW = mapSnapshot.width
        val finalH = mapSnapshot.height

        val finalBitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        
        // Draw the map
        canvas.drawBitmap(mapSnapshot, 0f, 0f, null)
        
        if (showStats) {
            val bannerHeight = (finalW * AppConfig.OVERLAY_BANNER_HEIGHT_RATIO).toInt()
            val bannerTop = (finalH - bannerHeight).toFloat()
            
            // Draw the banner
            val paint = Paint().apply {
                color = AppConfig.OVERLAY_BANNER_COLOR
                alpha = AppConfig.OVERLAY_BANNER_ALPHA
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, bannerTop, finalW.toFloat(), finalH.toFloat(), paint)
            
            // Draw Text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AppConfig.OVERLAY_TEXT_COLOR
                textSize = bannerHeight * 0.25f
                textAlign = Paint.Align.LEFT
            }
            
            val distanceKm = (rideWithPoints.ride.postRideCalculation?.distance ?: 0.0) / 1000.0
            val distanceStr = String.format(Locale.getDefault(), "%.2f km", distanceKm)
            
            val durationMillis = (rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime
            val seconds = durationMillis / 1000
            val durationStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            
            val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(rideWithPoints.ride.startTime))
            
            val padding = finalW * 0.05f
            
            canvas.drawText("TrackMe Ride", padding, bannerTop + bannerHeight * 0.4f, textPaint)
            
            textPaint.textSize = bannerHeight * 0.15f
            canvas.drawText("$dateStr • $durationStr • $distanceStr", padding, bannerTop + bannerHeight * 0.7f, textPaint)
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
