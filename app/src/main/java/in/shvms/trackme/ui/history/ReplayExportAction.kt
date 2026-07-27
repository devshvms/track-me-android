package `in`.shvms.trackme.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.replay.MediaCodecReplayExporter
import `in`.shvms.trackme.domain.replay.ReplayExportConfig
import `in`.shvms.trackme.domain.replay.ReplayOverlay
import `in`.shvms.trackme.theme.BrandThemeConfig
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.utils.RideUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private data class CapturedMapSnapshot(
    val bitmap: android.graphics.Bitmap?,
    val routeProjection: List<Pair<Float, Float>>
)

@Composable
fun ReplayExportAction(
    rideWithPoints: RideWithPoints,
    context: Context,
    settings: ExportPreviewSettings,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val hasEnoughPoints = rideWithPoints.points.size >= 2
    // Overlay text is resolved here, in the UI layer, and passed down — the renderer must not
    // reach for strings or preferences itself.
    val app = context.applicationContext as `in`.shvms.trackme.TrackMeApp
    val unitSystem by app.preferencesManager.unitSystem.collectAsState()
    val persona = RideUtils.personaFromStoredName(rideWithPoints.ride.persona)
    val overlay = ReplayOverlay(
        personaLabel = strings.personaLabel(persona),
        imperialUnits = unitSystem == "imperial"
    )

    TextButton(
        onClick = {
            if (exporting) {
                exportJob?.cancel()
                return@TextButton
            }
            if (!hasEnoughPoints) {
                android.widget.Toast.makeText(context, strings.replayExportNotEnoughGps, android.widget.Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            exporting = true
            progress = 0f
            val routePoints = replayRoutePoints(rideWithPoints.points, settings.privacyTrim)
            if (routePoints.size < 2) {
                exporting = false
                android.widget.Toast.makeText(context, strings.replayExportFailed, android.widget.Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            val frameSize = replayFrameSize(settings.ratio)
            captureRouteSnapshot(
                context = context,
                points = routePoints,
                size = replaySnapshotSize(frameSize),
                mapType = settings.mapType
            ) { captured ->
                startExport(
                    rideWithPoints = rideWithPoints,
                    context = context,
                    frameSize = frameSize,
                    applyPrivacyTrim = settings.privacyTrim,
                    snapshot = captured?.bitmap,
                    routeProjection = captured?.routeProjection,
                    persona = persona,
                    overlay = overlay,
                    scope = scope,
                    onProgress = { progress = it },
                    onFinished = {
                        exporting = false
                        exportJob = null
                    },
                    onJobCreated = { exportJob = it },
                    onFailure = { android.widget.Toast.makeText(context, strings.replayExportFailed, android.widget.Toast.LENGTH_SHORT).show() }
                )
            }
        },
        enabled = !exporting || hasEnoughPoints,
        modifier = modifier
    ) {
        Icon(Icons.Default.Movie, contentDescription = strings.replayExportTitle)
        Text(
            text = when {
                exporting -> strings.replayExportProgress.format((progress * 100).roundToInt())
                else -> strings.replayExportButton
            },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun startExport(
    rideWithPoints: RideWithPoints,
    context: Context,
    frameSize: Pair<Int, Int>,
    applyPrivacyTrim: Boolean,
    snapshot: android.graphics.Bitmap?,
    routeProjection: List<Pair<Float, Float>>?,
    persona: RidePersona,
    overlay: ReplayOverlay,
    scope: kotlinx.coroutines.CoroutineScope,
    onProgress: (Float) -> Unit,
    onFinished: () -> Unit,
    onJobCreated: (Job) -> Unit,
    onFailure: () -> Unit
) {
    val lastPublishedProgress = AtomicReference(-1f)
    val deepLinkId = rideWithPoints.ride.firestoreId?.takeLast(12) ?: rideWithPoints.ride.id.toString()
    onJobCreated(scope.launch {
        try {
            val result = withContext(Dispatchers.Default) {
                MediaCodecReplayExporter(
                    renderer = `in`.shvms.trackme.domain.replay.CanvasReplayFrameRenderer(context.applicationContext)
                ).exportReplay(
                    rideWithPoints = rideWithPoints,
                    config = ReplayExportConfig(
                        width = frameSize.first,
                        height = frameSize.second,
                        applyPrivacyTrim = applyPrivacyTrim,
                        privacyTrimDistanceMeters = COMPARISON_PRIVACY_TRIM_METERS,
                        persona = persona,
                        deepLink = "${AppConfig.REPLAY_DEEP_LINK_BASE_URL}$deepLinkId",
                        overlay = overlay
                    ),
                    outputDirectory = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME),
                    mapSnapshot = snapshot,
                    routeProjection = routeProjection,
                    onProgress = { value ->
                        val previous = lastPublishedProgress.get()
                        if ((value == 1f || value - previous >= 0.02f) &&
                            lastPublishedProgress.compareAndSet(previous, value)
                        ) {
                            scope.launch(Dispatchers.Main.immediate) { onProgress(value) }
                        }
                    }
                )
            }
            result.onSuccess { file -> shareReplay(context, file) }.onFailure { onFailure() }
        } catch (_: CancellationException) {
            throw CancellationException()
        } finally {
            if (snapshot != null && !snapshot.isRecycled) snapshot.recycle()
            onFinished()
        }
    })
}

/** Captures only the selected route on a ratio-matched temporary map surface. */
private fun captureRouteSnapshot(
    context: Context,
    points: List<`in`.shvms.trackme.data.local.entity.GPSPointEntity>,
    size: Pair<Int, Int>,
    mapType: com.google.maps.android.compose.MapType,
    onResult: (CapturedMapSnapshot?) -> Unit
) {
    val width = size.first
    val height = size.second
    val mapView = MapView(context.applicationContext, GoogleMapOptions().mapType(googleMapTypeFor(mapType)))
    mapView.layoutParams = ViewGroup.LayoutParams(width, height)
    mapView.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
    )
    mapView.layout(0, 0, width, height)
    mapView.onCreate(null)
    mapView.onStart()
    mapView.onResume()

    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    val mainHandler = Handler(Looper.getMainLooper())
    val timeout = Runnable {
        if (completed.compareAndSet(false, true)) {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            onResult(null)
        }
    }
    mainHandler.postDelayed(timeout, 5_000L)
    fun finish(captured: CapturedMapSnapshot?) {
        if (!completed.compareAndSet(false, true)) {
            captured?.bitmap?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            return
        }
        mainHandler.removeCallbacks(timeout)
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        onResult(captured)
    }
    mapView.getMapAsync { map ->
        val latLngs = points.map { LatLng(it.latitude, it.longitude) }
        val bounds = LatLngBounds.builder().apply { latLngs.forEach(::include) }.build()
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.addPolyline(PolylineOptions().addAll(latLngs).color(BrandThemeConfig.cyanBright.toArgb()).width(8f))
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
            map.setOnMapLoadedCallback {
                runCatching {
                    val normalized = latLngs.map { latLng ->
                        val point = map.projection.toScreenLocation(latLng)
                        point.x / width.toFloat() to point.y / height.toFloat()
                    }
                    map.snapshot { bitmap -> finish(CapturedMapSnapshot(bitmap, normalized)) }
                }.onFailure { finish(null) }
            }
        }.onFailure { finish(null) }
    }
}

/** Maps the preview's ratio to an even H.264 frame size in the 1080p class. */
internal fun replayFrameSize(ratio: Pair<Int, Int>): Pair<Int, Int> {
    val ratioWidth = ratio.first.toFloat()
    val ratioHeight = ratio.second.toFloat()
    if (ratioWidth <= 0f || ratioHeight <= 0f) return 1080 to 1080

    val shortEdge = 1080f
    val ratioScale = shortEdge / minOf(ratioWidth, ratioHeight)
    var width = ratioWidth * ratioScale
    var height = ratioHeight * ratioScale
    val longEdge = maxOf(width, height)
    if (longEdge > 1920f) {
        val capScale = 1920f / longEdge
        width *= capScale
        height *= capScale
    }
    return evenPixels(width) to evenPixels(height)
}

/** Captures at half the frame dimensions, preserving the default 540x960 memory profile. */
internal fun replaySnapshotSize(frameSize: Pair<Int, Int>): Pair<Int, Int> =
    (frameSize.first / 2).coerceAtLeast(2) to (frameSize.second / 2).coerceAtLeast(2)

/** Converts maps-compose styles to the SDK values accepted by [GoogleMapOptions]. */
internal fun googleMapTypeFor(mapType: com.google.maps.android.compose.MapType): Int = when (mapType) {
    com.google.maps.android.compose.MapType.SATELLITE -> GoogleMap.MAP_TYPE_SATELLITE
    com.google.maps.android.compose.MapType.TERRAIN -> GoogleMap.MAP_TYPE_TERRAIN
    com.google.maps.android.compose.MapType.HYBRID -> GoogleMap.MAP_TYPE_HYBRID
    else -> GoogleMap.MAP_TYPE_NORMAL
}

/** Uses the exact endpoint-trim rule shared by the preview and replay exporter. */
internal fun replayRoutePoints(
    points: List<`in`.shvms.trackme.data.local.entity.GPSPointEntity>,
    applyPrivacyTrim: Boolean
): List<`in`.shvms.trackme.data.local.entity.GPSPointEntity> =
    (if (applyPrivacyTrim) {
        trimComparisonEndpoints(points, COMPARISON_PRIVACY_TRIM_METERS)
    } else {
        points
    }).sortedBy { it.timestamp }

private fun evenPixels(value: Float): Int {
    val rounded = kotlin.math.ceil(value - 0.5f).toInt().coerceAtLeast(2)
    return if (rounded % 2 == 0) rounded else rounded + 1
}

private fun shareReplay(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "TrackMe"))
}
