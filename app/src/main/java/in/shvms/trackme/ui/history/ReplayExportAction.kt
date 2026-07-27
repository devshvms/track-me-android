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
            val trimmedPoints = trimComparisonEndpoints(rideWithPoints.points).sortedBy { it.timestamp }
            if (trimmedPoints.size < 2) {
                exporting = false
                android.widget.Toast.makeText(context, strings.replayExportFailed, android.widget.Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            capturePrivacyTrimmedSnapshot(context, trimmedPoints) { captured ->
                startExport(
                    rideWithPoints = rideWithPoints,
                    context = context,
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

/** Captures only the privacy-trimmed route on a temporary 9:16 map surface. */
private fun capturePrivacyTrimmedSnapshot(
    context: Context,
    points: List<`in`.shvms.trackme.data.local.entity.GPSPointEntity>,
    onResult: (CapturedMapSnapshot?) -> Unit
) {
    val width = 540
    val height = 960
    val mapView = MapView(context.applicationContext, GoogleMapOptions().mapType(com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL))
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

private fun shareReplay(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "TrackMe"))
}
