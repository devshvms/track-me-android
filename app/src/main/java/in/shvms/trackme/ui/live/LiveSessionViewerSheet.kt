package `in`.shvms.trackme.ui.live

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.remote.LiveSessionFetchResult
import `in`.shvms.trackme.data.remote.LiveSessionSnapshot
import `in`.shvms.trackme.data.remote.LiveSessionViewerRepository
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.ui.components.animateSafely
import `in`.shvms.trackme.ui.history.ExportMarkerStyle
import `in`.shvms.trackme.ui.history.ExportMarkers
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * TASK-366 / SCOPE_1.9.3: Native in-app Live Share Viewer.
 * Renders the rider's cyan breadcrumb polyline, start marker, rotated heading cone, and live stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSessionViewerSheet(
    sessionId: String,
    repository: LiveSessionViewerRepository,
    imperial: Boolean,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val viewerId = remember(sessionId) { "android_${UUID.randomUUID()}" }

    var snapshot by remember(sessionId) { mutableStateOf<LiveSessionSnapshot?>(null) }
    var isExpiredOrMissing by remember(sessionId) { mutableStateOf(false) }
    var errorMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    var isLoading by remember(sessionId) { mutableStateOf(true) }
    var isFollowingRider by remember(sessionId) { mutableStateOf(true) }
    var hasCenteredInitially by remember(sessionId) { mutableStateOf(false) }
    var hasRenderedLocationInitially by remember(sessionId) { mutableStateOf(false) }
    var nowEpochMillis by remember(sessionId) { mutableLongStateOf(System.currentTimeMillis()) }

    // Lifecycle-aware polling: pauses completely when backgrounded and refreshes immediately on resume.
    LaunchedEffect(sessionId, lifecycleOwner) {
        AnalyticsManager.trackLiveShareViewed(sessionId, surface = "in_app_viewer")
        var stopPolling = false
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (stopPolling) return@repeatOnLifecycle
            var tick = 0
            while (isActive && !stopPolling) {
                nowEpochMillis = System.currentTimeMillis()
                when (val res = repository.fetchSnapshot(sessionId)) {
                    is LiveSessionFetchResult.Success -> {
                        snapshot = res.snapshot
                        isExpiredOrMissing = false
                        errorMessage = null
                        isLoading = false
                        if (!hasRenderedLocationInitially && res.snapshot.lastLocation != null) {
                            hasRenderedLocationInitially = true
                            AnalyticsManager.trackLiveShareLocationRendered(
                                sessionId = sessionId,
                                pathPoints = res.snapshot.path.size,
                                hasHeading = res.snapshot.lastLocation.headingDeg != null,
                                isStopped = !res.snapshot.isActive,
                            )
                        }
                        if (res.snapshot.isActive && tick % 3 == 0) {
                            repository.sendViewerPing(sessionId, viewerId)
                        }
                        if (!res.snapshot.isActive) {
                            stopPolling = true
                            break
                        }
                    }
                    LiveSessionFetchResult.NotFoundOrExpired -> {
                        isExpiredOrMissing = true
                        isLoading = false
                        stopPolling = true
                        break
                    }
                    is LiveSessionFetchResult.Error -> {
                        errorMessage = res.message
                        isLoading = false
                    }
                }
                tick++
                delay(5_000L)
            }
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(20.5937, 78.9629), 4f)
    }

    LaunchedEffect(cameraPositionState.isMoving) {
        if (
            cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            isFollowingRider = false
        }
    }

    val currentLatLng = snapshot?.lastLocation?.let { LatLng(it.lat, it.lng) }
    LaunchedEffect(currentLatLng, isFollowingRider) {
        val target = currentLatLng ?: return@LaunchedEffect
        if (!isFollowingRider && hasCenteredInitially) return@LaunchedEffect
        val zoom = cameraPositionState.position.zoom.coerceAtLeast(15.5f)
        cameraPositionState.animateSafely {
            CameraUpdateFactory.newLatLngZoom(target, zoom)
        }
        hasCenteredInitially = true
    }

    val startIcon = remember { runCatching { ExportMarkers.start(ExportMarkerStyle.StartFinish, 44) }.getOrNull() }
    val headingIcon = remember { runCatching { createHeadingConeMarkerBitmap() }.getOrNull() }
    val stationaryDotIcon = remember { runCatching { createStationaryDotMarkerBitmap() }.getOrNull() }

    val lastLoc = snapshot?.lastLocation
    val ageSeconds = remember(lastLoc?.timestampIso, nowEpochMillis) {
        LiveSessionViewerRepository.ageSeconds(lastLoc?.timestampIso, nowEpochMillis)
    }
    val isDelayedUpdate = remember(snapshot?.isActive, lastLoc?.powerSaveMode, lastLoc?.timestampIso, nowEpochMillis) {
        snapshot?.isActive == true && LiveSessionViewerRepository.isDelayedUpdate(
            powerSaveMode = lastLoc?.powerSaveMode == true,
            timestampIso = lastLoc?.timestampIso,
            nowEpochMillis = nowEpochMillis,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val titleText = snapshot?.username?.let { rider ->
                                String.format(Locale.getDefault(), strings.liveViewerTitle, rider)
                            } ?: strings.liveViewerLoading
                            Text(
                                text = titleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val active = snapshot?.isActive == true
                            if (snapshot != null || isExpiredOrMissing) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (active) {
                                        Color(0xFF06B6D4).copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sensors,
                                            contentDescription = null,
                                            tint = if (active) Color(0xFF06B6D4) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = if (active) strings.liveViewerActiveBadge else strings.liveViewerEndedBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (active) Color(0xFF06B6D4) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (isDelayedUpdate) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                ) {
                                    Text(
                                        text = strings.liveViewerDelayedBadge,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFF59E0B),
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = strings.cancel)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val pathLatLng = remember(snapshot?.path) {
                        snapshot?.path.orEmpty().map { LatLng(it.lat, it.lng) }
                    }
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = false,
                            compassEnabled = true,
                            myLocationButtonEnabled = false,
                        ),
                    ) {
                        if (pathLatLng.size >= 2) {
                            Polyline(
                                points = pathLatLng,
                                color = Color(0xFF06B6D4),
                                width = 10f,
                                geodesic = true,
                            )
                        }
                        pathLatLng.firstOrNull()?.let { startPoint ->
                            Marker(
                                state = MarkerState(position = startPoint),
                                icon = startIcon,
                                anchor = Offset(0.5f, 0.5f),
                                title = snapshot?.username,
                            )
                        }
                        currentLatLng?.let { livePoint ->
                            val hasHeading = lastLoc?.headingDeg != null && snapshot?.isActive == true
                            val bearing = lastLoc?.headingDeg ?: 0f
                            Marker(
                                state = MarkerState(position = livePoint),
                                icon = if (hasHeading) headingIcon else (stationaryDotIcon ?: headingIcon),
                                rotation = if (hasHeading) bearing else 0f,
                                flat = true,
                                anchor = Offset(0.5f, 0.5f),
                                title = snapshot?.username,
                            )
                        }
                    }

                    if (isLoading && snapshot == null) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text(
                                    text = strings.liveViewerLoading,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    } else if (isExpiredOrMissing || (snapshot != null && !snapshot!!.isActive)) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                        ) {
                            Text(
                                text = snapshot?.stopReason?.takeIf { it.isNotBlank() }
                                    ?: snapshot?.endReason?.takeIf { it.isNotBlank() }
                                    ?: strings.liveViewerExpiredNotice,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (errorMessage != null && snapshot == null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else if (snapshot?.isActive == true && currentLatLng == null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            tonalElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = strings.liveViewerWaitingFirstFix,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    } else if (errorMessage != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            shape = RoundedCornerShape(50),
                            color = Color(0xFFF59E0B).copy(alpha = 0.92f),
                        ) {
                            Text(
                                text = strings.liveViewerReconnecting,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1F2937),
                            )
                        }
                    }

                    // Map camera controls: Recenter (when panned away) & Fit route (when trail has 2+ points)
                    if (currentLatLng != null || pathLatLng.size >= 2) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!isFollowingRider && currentLatLng != null) {
                                FilledTonalButton(
                                    onClick = {
                                        isFollowingRider = true
                                        coroutineScope.launch {
                                            val zoom = cameraPositionState.position.zoom.coerceAtLeast(15.5f)
                                            cameraPositionState.animateSafely {
                                                CameraUpdateFactory.newLatLngZoom(currentLatLng, zoom)
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFF06B6D4),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = strings.liveViewerRecenter,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            if (pathLatLng.size >= 2) {
                                FilledTonalButton(
                                    onClick = {
                                        isFollowingRider = false
                                        val boundsBuilder = LatLngBounds.Builder()
                                        pathLatLng.forEach { boundsBuilder.include(it) }
                                        val bounds = boundsBuilder.build()
                                        coroutineScope.launch {
                                            cameraPositionState.animateSafely {
                                                CameraUpdateFactory.newLatLngBounds(bounds, 96)
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomOutMap,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = strings.liveViewerFitRoute,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val destinationText = snapshot?.destination?.takeIf { it.isNotBlank() }
                        val showFreshnessRow = destinationText != null || ageSeconds != null
                        if (showFreshnessRow) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (destinationText != null) {
                                    Row(
                                        modifier = Modifier.weight(1f, fill = false),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Flag,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = Color(0xFF06B6D4),
                                        )
                                        Text(
                                            text = destinationText,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }
                                if (ageSeconds != null) {
                                    val ageLabel = if (ageSeconds < 60) "${ageSeconds}s" else "${ageSeconds / 60}m"
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = String.format(Locale.getDefault(), strings.liveViewerUpdatedAgo, ageLabel),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                val speedText = lastLoc?.speedMps?.let {
                                    UnitFormatter.speed(it.toDouble(), imperial)
                                } ?: "—"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFF06B6D4),
                                    )
                                    Text(
                                        text = speedText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                lastLoc?.batteryLevel?.let { battery ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BatteryStd,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "$battery%",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val browserUrl = "${AppConfig.LIVE_SHARE_BASE_URL}/live/$sessionId"
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.liveViewerOpenInBrowser)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun createHeadingConeMarkerBitmap(): BitmapDescriptor {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Forward translucent heading cone pointing UP (0 deg = North, Marker rotation rotates clockwise)
    val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(72, 6, 182, 212)
        style = Paint.Style.FILL
    }
    val conePath = Path().apply {
        moveTo(cx, cy)
        lineTo(cx - 26f, 6f)
        lineTo(cx + 26f, 6f)
        close()
    }
    canvas.drawPath(conePath, conePaint)

    // White halo ring
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 18f, haloPaint)

    // Cyan directional dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(6, 182, 212)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 13f, dotPaint)

    // Crisp white directional triangle pointing forward
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val arrowPath = Path().apply {
        moveTo(cx, cy - 9f)
        lineTo(cx - 6f, cy + 5f)
        lineTo(cx, cy + 2f)
        lineTo(cx + 6f, cy + 5f)
        close()
    }
    canvas.drawPath(arrowPath, arrowPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun createStationaryDotMarkerBitmap(): BitmapDescriptor {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Outer subtle translucent pulse ring
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(48, 6, 182, 212)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 22f, ringPaint)

    // White halo ring
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 15f, haloPaint)

    // Solid cyan center dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(6, 182, 212)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, 11f, dotPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
