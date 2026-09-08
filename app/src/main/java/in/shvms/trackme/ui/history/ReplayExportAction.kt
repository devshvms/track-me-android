package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.ui.components.rememberMessenger
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import `in`.shvms.trackme.domain.export.artifactDeepLink
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.replay.MediaCodecReplayExporter
import `in`.shvms.trackme.domain.replay.ReplayExportConfig
import `in`.shvms.trackme.domain.replay.ReplayOverlay
import `in`.shvms.trackme.theme.BrandThemeConfig
import `in`.shvms.trackme.theme.LocalTrackMeMotion
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

/** The three mutually-exclusive things the wide replay button can be showing. */
internal enum class ReplayExportLabel { IDLE, IN_PROGRESS, UNAVAILABLE }

/**
 * Pure presentation state for the wide replay-export button.
 *
 * [fillFraction] drives a progress fill drawn *inside* the button's own fixed bounds, which is why
 * the button's measured width never depends on the label. That is the structural fix behind E9:
 * the old design grew a text label ("Creating replay · NN%") inside a shared four-button row and
 * squeezed its siblings. A constant-width container with an internal fill cannot do that.
 */
internal data class ReplayExportButtonState(
    val enabled: Boolean,
    val fillFraction: Float,
    val percent: Int,
    val label: ReplayExportLabel
) {
    val isExporting: Boolean get() = label == ReplayExportLabel.IN_PROGRESS
}

/**
 * Derives the button's state. Kept free of Compose and Android types so it is unit-testable.
 *
 * - Not enough GPS points → disabled, with the reason surfaced as supporting text rather than a
 *   toast the user only sees after tapping a button that looked available (E9 edge case).
 * - Exporting → stays enabled, because tapping cancels the in-flight export (behavior preserved
 *   from the pre-E9 button).
 */
internal fun replayExportButtonState(
    exporting: Boolean,
    progress: Float,
    hasEnoughPoints: Boolean
): ReplayExportButtonState {
    if (exporting) {
        val clamped = progress.coerceIn(0f, 1f)
        return ReplayExportButtonState(
            enabled = true,
            fillFraction = clamped,
            percent = (clamped * 100).roundToInt(),
            label = ReplayExportLabel.IN_PROGRESS
        )
    }
    return ReplayExportButtonState(
        enabled = hasEnoughPoints,
        fillFraction = 0f,
        percent = 0,
        label = if (hasEnoughPoints) ReplayExportLabel.IDLE else ReplayExportLabel.UNAVAILABLE
    )
}

private val ReplayButtonHeight = 56.dp
private val ReplayButtonShape = RoundedCornerShape(28.dp)

/**
 * Wide, full-width replay-video export action.
 *
 * Lives inside the shared export preview (see [ExportPreviewDialog]'s `videoAction` slot), not in
 * Ride Detail's action row. Cancellation on preview dismissal is automatic: the export job is
 * launched from [rememberCoroutineScope], so leaving composition cancels it and the exporter's
 * `finally` block recycles the map snapshot.
 */
@Composable
fun ReplayExportAction(
    rideWithPoints: RideWithPoints,
    context: Context,
    settings: ExportPreviewSettings,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val messenger = rememberMessenger()
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
    val imperial = unitSystem == "imperial"
    // TASK-305: built by the same function the still export uses, so the two artifacts made from
    // this one preview cannot disagree about what they say. The video used to derive its own
    // distance and duration with its own formatters and ignore the panel settings entirely.
    val exportDuration = displayExportDuration(rideWithPoints.ride)
    val overlayContent = buildOverlayContent(
        date = remember(rideWithPoints.ride.startTime) {
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(rideWithPoints.ride.startTime))
        },
        duration = exportDuration ?: strings.unknown,
        distance = `in`.shvms.trackme.domain.UnitFormatter.rideDistance(
            rideWithPoints.ride.postRideCalculation?.distance ?: 0.0,
            imperial
        ),
        showDate = settings.showDate,
        showDuration = settings.showDuration && exportDuration != null,
        showDistance = settings.showDistance,
    )
    val overlay = ReplayOverlay(
        personaLabel = strings.personaLabel(persona),
        imperialUnits = imperial,
        statsStyle = settings.statsOverlay,
        figures = overlayContent.figures,
        darkTheme = settings.darkTheme
    )

    val state = replayExportButtonState(
        exporting = exporting,
        progress = progress,
        hasEnoughPoints = hasEnoughPoints
    )

    val onClick: () -> Unit = onClick@{
        if (state.isExporting) {
            exportJob?.cancel()
            return@onClick
        }
        exporting = true
        progress = 0f
        val routePoints = replayRoutePoints(rideWithPoints.points, settings.privacyTrim)
        if (routePoints.size < 2) {
            exporting = false
            messenger.show(strings.replayExportFailed)
            return@onClick
        }
        val frameSize = replayFrameSize(settings.ratio)
        captureRouteSnapshot(
            context = context,
            points = routePoints,
            size = replaySnapshotSize(frameSize),
            mapType = settings.mapType,
            // Basemap theme and label rules, composed into one MapStyleOptions. The burned-in
            // chrome is carried separately, on `overlay` — see ReplayOverlay (TASK-305).
            mapStyle = settings.mapStyle(context)
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
                onFailure = { messenger.show(strings.replayExportFailed) }
            )
        }
    }

    val label = when (state.label) {
        ReplayExportLabel.IN_PROGRESS -> strings.replayExportProgress.format(state.percent)
        ReplayExportLabel.IDLE, ReplayExportLabel.UNAVAILABLE -> strings.replayExportButton
    }
    // Smooths the 2%-quantised progress callbacks into a continuous fill instead of visible steps.
    val animatedFill by animateFloatAsState(
        targetValue = state.fillFraction,
        // Bounded 0..1: an overshoot would push the fill past the end of its track and clip.
        animationSpec = LocalTrackMeMotion.current.spatialBounded.spec(),
        label = "replayExportFill"
    )
    val trackColor = if (state.enabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (state.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReplayButtonHeight)
                .clip(ReplayButtonShape)
                .background(trackColor)
                .clickable(
                    enabled = state.enabled,
                    onClickLabel = if (state.isExporting) strings.replayExportCancel else strings.replayExportButton,
                    role = Role.Button,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = strings.replayExportTitle
                    if (state.isExporting) {
                        stateDescription = strings.replayExportProgress.format(state.percent)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (state.isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFill.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = if (state.isExporting) Icons.Default.Close else Icons.Default.Movie,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (state.label == ReplayExportLabel.UNAVAILABLE) {
            Text(
                text = strings.replayExportNotEnoughGps,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
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
    val renderStartedAt = android.os.SystemClock.elapsedRealtime()
    val job = scope.launch {
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
                        deepLink = artifactDeepLink(rideWithPoints.ride),
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
            // TASK-305: one event for both outcomes, carrying the elapsed time. A render that
            // fails after forty seconds and one that fails immediately are different bugs, and
            // only a single event with both fields can tell them apart.
            val elapsed = android.os.SystemClock.elapsedRealtime() - renderStartedAt
            result
                .onSuccess { file ->
                    `in`.shvms.trackme.analytics.AnalyticsManager.trackExportRendered(
                        kind = `in`.shvms.trackme.analytics.ExportArtifactKind.VIDEO,
                        success = true,
                        durationMillis = elapsed,
                    )
                    shareReplay(context, file)
                }
                .onFailure { error ->
                    `in`.shvms.trackme.analytics.AnalyticsManager.trackExportRendered(
                        kind = `in`.shvms.trackme.analytics.ExportArtifactKind.VIDEO,
                        success = false,
                        durationMillis = elapsed,
                        failureReason = error::class.simpleName,
                    )
                    onFailure()
                }
        } catch (_: CancellationException) {
            throw CancellationException()
        }
    }
    // E9: dismissing the export preview mid-generation cancels the composition scope, which can
    // cancel this job *before its body ever runs* — a `finally` inside the coroutine would then
    // never execute and the captured map snapshot would be orphaned. `invokeOnCompletion` fires in
    // every terminal case (success, failure, and never-started cancellation), so the bitmap is
    // always released and the button always returns to its idle state.
    job.invokeOnCompletion {
        if (snapshot != null && !snapshot.isRecycled) snapshot.recycle()
        onFinished()
    }
    onJobCreated(job)
}

/** Walks the ContextWrapper chain to find the hosting Activity, if any. */
private fun Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Captures only the selected route on a ratio-matched temporary map surface.
 *
 * The map view is briefly attached to the host activity's window, off-screen via
 * [View.setTranslationX]. Google Maps' underlying GL/texture surface only renders once attached to
 * a real window; a [MapView] that is merely measured/laid out without ever joining the view
 * hierarchy produces a blank tile snapshot even though `snapshot()` still calls back successfully.
 */
private fun captureRouteSnapshot(
    context: Context,
    points: List<`in`.shvms.trackme.data.local.entity.GPSPointEntity>,
    size: Pair<Int, Int>,
    mapType: com.google.maps.android.compose.MapType,
    mapStyle: com.google.android.gms.maps.model.MapStyleOptions? = null,
    onResult: (CapturedMapSnapshot?) -> Unit
) {
    val width = size.first
    val height = size.second
    val rootView = context.findActivity()?.window?.decorView as? ViewGroup
    val mapView = MapView(context, GoogleMapOptions().mapType(googleMapTypeFor(mapType)))
    mapView.layoutParams = ViewGroup.LayoutParams(width, height)
    if (rootView != null) {
        // Pushed off the visible frame rather than hidden with alpha/visibility, both of which
        // stop the surface from actually rendering tiles on some devices.
        mapView.translationX = -(width * 2).toFloat()
        rootView.addView(mapView)
    }
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
    fun detach() {
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        rootView?.removeView(mapView)
    }
    val timeout = Runnable {
        if (completed.compareAndSet(false, true)) {
            detach()
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
        detach()
        onResult(captured)
    }
    mapView.getMapAsync { map ->
        val latLngs = points.map { LatLng(it.latitude, it.longitude) }
        val bounds = LatLngBounds.builder().apply { latLngs.forEach(::include) }.build()
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        mapStyle?.let { runCatching { map.setMapStyle(it) } }
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
    shareExportedArtifact(
        context = context,
        file = file,
        kind = `in`.shvms.trackme.analytics.ExportArtifactKind.VIDEO,
        chooserTitle = "TrackMe",
    )
}
