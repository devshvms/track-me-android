package `in`.shvms.trackme.ui.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.MapType
import `in`.shvms.trackme.R
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.MapBackdrop
import `in`.shvms.trackme.domain.export.template.PixelPoint
import `in`.shvms.trackme.domain.export.template.PlaceParts
import `in`.shvms.trackme.domain.export.template.RouteProjection
import `in`.shvms.trackme.domain.export.template.TemplateCanvas
import `in`.shvms.trackme.domain.export.template.TemplateContent
import `in`.shvms.trackme.domain.export.template.TemplateRenderer
import `in`.shvms.trackme.domain.export.template.traceRouteBoxDesign
import `in`.shvms.trackme.ui.components.captureOffscreenMap
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** The app's one typeface, loaded once. Inter is the brand's single family (BRAND_SYSTEM.md). */
private object TemplateTypeface {
    @Volatile private var cached: Typeface? = null
    fun get(context: Context): Typeface = cached ?: (
        runCatching { ResourcesCompat.getFont(context, R.font.inter_variable) }.getOrNull() ?: Typeface.DEFAULT
        ).also { cached = it }
}

/**
 * Draws one template for one ride — the preview and the export call this with different widths and
 * nothing else different, which is what makes the preview honest.
 */
internal suspend fun renderTemplate(
    context: Context,
    ride: RideWithPoints,
    choice: ExportTemplateChoice,
    canvas: TemplateCanvas,
    privacyTrim: Boolean,
    widthPx: Int,
    strings: AppStrings,
    imperial: Boolean,
): Bitmap? {
    val locale = Locale.getDefault()
    val dateFormat = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val content = ExportTemplateContent.build(
        ride = ride,
        options = TemplateOptions(privacyTrim = privacyTrim, place = choice.place, lightOverride = choice.lightOverride),
        strings = strings,
        imperial = imperial,
        formatDate = { dateFormat.format(Date(it)) },
        formatTime = { timeFormat.format(Date(it)) },
        locale = locale,
    )
    val backdrop = if (choice.mapBackground && choice.id == ExportTemplateId.TRACE) {
        captureTraceBackdrop(context, content, canvas, widthPx)
    } else {
        null
    }
    // A requested basemap that could not be captured degrades to the plain ground — never to a
    // route drawn over a map it was not projected onto (EXPORT_SHARE_CONTRACTS §3).
    return TemplateRenderer.render(choice.id, canvas, content, TemplateTypeface.get(context), widthPx, backdrop)
}

/** Writes a rendered template as PNG — the only format that keeps the Sticker's transparency. */
internal fun writeTemplatePng(context: Context, bitmap: Bitmap, rideId: Long, template: ExportTemplateId): File {
    val dir = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME).apply { mkdirs() }
    val file = File(dir, "${AppConfig.IMAGE_FILE_PREFIX}${rideId}_${template.analyticsValue}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/**
 * The Trace's lite basemap (SCOPE_1.8.9 §8), captured from a dedicated off-screen map that holds **no
 * route at all** — the renderer draws the line — so untrimmed pixels cannot be baked in (§8's
 * regression obligation is met by construction).
 *
 * The camera is placed so the route lands in the Trace's route box, and then the map is *asked* where
 * each point landed: the camera choice is ours, the projection is the SDK's (EXPORT_SHARE_CONTRACTS
 * "Never re-derive the map projection"). Map padding would have been simpler and would also have
 * dragged Google's logo up into the text; this keeps it in the corner, where the renderer re-draws
 * it unveiled — attribution is required, and a veil that hid it would breach the Maps terms.
 */
private suspend fun captureTraceBackdrop(
    context: Context,
    content: TemplateContent,
    canvas: TemplateCanvas,
    widthPx: Int,
): MapBackdrop? {
    val heightPx = (widthPx / canvas.aspect).toInt()
    val all = (content.runs + content.joins).flatten()
    if (all.size < 2) return null
    val scale = widthPx / TemplateRenderer.DESIGN_WIDTH
    val box = traceRouteBoxDesign(canvas).let { it.copy(left = it.left * scale, top = it.top * scale, right = it.right * scale, bottom = it.bottom * scale) }
    val density = context.resources.displayMetrics.density
    val camera = cameraFor(all, box, widthPx, heightPx, density) ?: return null
    val style = MapLabelStyle.exportStyle(context, MapType.NORMAL, MapLabelStyle.NoLabels, darkTheme = true)
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            captureOffscreenMap(
                context = context,
                widthPx = widthPx,
                heightPx = heightPx,
                mapType = MapType.NORMAL,
                configure = { map ->
                    style?.let(map::setMapStyle)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                },
            ) { bitmap, map ->
                val projection = map?.projection
                val backdrop = if (bitmap != null && projection != null) {
                    fun place(line: List<`in`.shvms.trackme.domain.processor.RouteCoordinate>) = line.map {
                        projection.toScreenLocation(LatLng(it.latitude, it.longitude)).let { p -> PixelPoint(p.x.toFloat(), p.y.toFloat()) }
                    }
                    MapBackdrop(
                        bitmap = bitmap,
                        runs = content.runs.map(::place),
                        joins = content.joins.map(::place),
                        // Radii of the lifted ellipse about the corner where the Maps SDK draws its
                        // logo at default padding. Not yet measured on a device, unlike iOS's — whose
                        // first guess was wrong, so check it on the first device pass.
                        attributionHeightPx = 72f * density,
                        attributionWidthPx = 200f * density,
                    )
                } else {
                    null
                }
                if (continuation.isActive) continuation.resume(backdrop)
            }
        }
    }
}

/**
 * A camera that puts the route's bounds inside [box]: zoom from the tighter axis, target shifted so
 * the route's centre sits at the box's centre rather than the map's. Web-Mercator arithmetic, with the
 * world 256 dp wide at zoom 0, as the Maps SDK defines it.
 */
private fun cameraFor(
    coordinates: List<`in`.shvms.trackme.domain.processor.RouteCoordinate>,
    box: `in`.shvms.trackme.domain.export.template.PixelBox,
    widthPx: Int,
    heightPx: Int,
    density: Float,
): CameraPosition? {
    fun x(lng: Double) = RouteProjection.mercatorX(lng)
    fun y(lat: Double) = RouteProjection.mercatorY(lat)
    val minX = coordinates.minOf { x(it.longitude) }
    val maxX = coordinates.maxOf { x(it.longitude) }
    val minY = coordinates.minOf { y(it.latitude) }
    val maxY = coordinates.maxOf { y(it.latitude) }
    val spanX = (maxX - minX).coerceAtLeast(1e-9)
    val spanY = (maxY - minY).coerceAtLeast(1e-9)
    // Pixels per mercator radian at zoom 0.
    val worldPx = 256.0 * density / (2 * PI)
    val scale = min(box.width / spanX, box.height / spanY)
    val zoom = (ln(scale / worldPx) / ln(2.0)).toFloat().coerceIn(2f, 20f)
    val pixelsPerUnit = worldPx * Math.pow(2.0, zoom.toDouble())
    val routeCenterX = (minX + maxX) / 2
    val routeCenterY = (minY + maxY) / 2
    val boxCenterX = (box.left + box.right) / 2.0
    val boxCenterY = (box.top + box.bottom) / 2.0
    val targetX = routeCenterX + (widthPx / 2.0 - boxCenterX) / pixelsPerUnit
    val targetY = routeCenterY - (heightPx / 2.0 - boxCenterY) / pixelsPerUnit
    val latitude = Math.toDegrees(2 * atan(exp(targetY)) - PI / 2)
    val longitude = Math.toDegrees(targetX)
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    return CameraPosition(LatLng(latitude, longitude), zoom, 0f, 0f)
}

/** Android's geocoder, mapped into the fields [PlaceLabelPolicy] may read. Null when absent or offline. */
internal fun androidGeocoder(context: Context): suspend (Double, Double) -> PlaceParts? = geocode@{ latitude, longitude ->
    if (!Geocoder.isPresent()) return@geocode null
    val geocoder = Geocoder(context, Locale.getDefault())
    val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
        }
    }
    address?.let { PlaceParts(it.subLocality, it.locality, it.subAdminArea, it.adminArea, it.thoroughfare, it.countryName) }
}
