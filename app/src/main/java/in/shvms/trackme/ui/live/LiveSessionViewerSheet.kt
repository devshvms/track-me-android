package `in`.shvms.trackme.ui.live

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.animateFloatAsState
import `in`.shvms.trackme.theme.LocalTrackMeMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import `in`.shvms.trackme.ui.components.moveSafely
import `in`.shvms.trackme.ui.components.rememberIsOffline
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

    val cachedSnapshot: LiveSessionSnapshot? = remember(sessionId) { repository.getCachedSnapshot(sessionId) }
    var snapshot by remember(sessionId) { mutableStateOf<LiveSessionSnapshot?>(cachedSnapshot) }
    var isExpiredOrMissing by remember(sessionId) { mutableStateOf(false) }
    var errorMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    var isLoading by remember(sessionId) { mutableStateOf(snapshot == null) }
    var isFollowingRider by rememberSaveable(sessionId) { mutableStateOf(true) }
    var hasCenteredInitially by rememberSaveable(sessionId) { mutableStateOf(cachedSnapshot != null) }
    var hasRenderedLocationInitially by rememberSaveable(sessionId) { mutableStateOf(cachedSnapshot != null) }
    var nowEpochMillis by remember(sessionId) { mutableLongStateOf(System.currentTimeMillis()) }
    var refreshTrigger by remember(sessionId) { mutableIntStateOf(0) }
    var lastTrackedLowBattery by remember(sessionId) { mutableStateOf<Int?>(null) }
    var continuousBearing by remember(sessionId) { mutableFloatStateOf(0f) }
    var hasInitializedBearing by remember(sessionId) { mutableStateOf(false) }
    var stoppedSinceEpochMs by remember(sessionId) { mutableStateOf<Long?>(null) }
    var isImperial by rememberSaveable(sessionId) { mutableStateOf(imperial) }
    val isOffline = rememberIsOffline()
    var wasOffline by remember(sessionId) { mutableStateOf(false) }
    var showReconnectedToast by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(isOffline) {
        if (isOffline) {
            wasOffline = true
            showReconnectedToast = false
        } else if (wasOffline) {
            wasOffline = false
            errorMessage = null
            refreshTrigger++
            showReconnectedToast = true
            delay(3500)
            showReconnectedToast = false
        }
    }

    val isReducedMotion = remember {
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    val motion = LocalTrackMeMotion.current
    val animatedBearing by animateFloatAsState(
        targetValue = continuousBearing,
        animationSpec = if (isReducedMotion) androidx.compose.animation.core.snap() else motion.spatialBounded.spec(),
        label = "viewer_heading_rotation",
    )

    // Lifecycle-aware polling: pauses completely when backgrounded and refreshes immediately on resume or retry.
    LaunchedEffect(sessionId, lifecycleOwner, refreshTrigger) {
        AnalyticsManager.trackLiveShareViewed(sessionId, surface = "in_app_viewer")
        var stopPolling = false
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (stopPolling) return@repeatOnLifecycle
            var tick = 0
            var consecutiveErrors = 0
            // Clear any stale error message upon returning to foreground so fresh fetch displays cleanly (CIE-027)
            errorMessage = null
            while (isActive && !stopPolling) {
                nowEpochMillis = System.currentTimeMillis()
                val nextDelay = when (val res = repository.fetchSnapshot(sessionId)) {
                    is LiveSessionFetchResult.Success -> {
                        consecutiveErrors = 0
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
                        val battery = LiveSessionViewerRepository.clampBatteryLevel(res.snapshot.lastLocation?.batteryLevel)
                        if (res.snapshot.isActive && battery != null && battery <= 15 && battery != lastTrackedLowBattery) {
                            lastTrackedLowBattery = battery
                            AnalyticsManager.trackLiveShareViewerLowBattery(sessionId, battery)
                        }
                        if (res.snapshot.isActive && tick % 3 == 0) {
                            repository.sendViewerPing(sessionId, viewerId)
                        }
                        if (!res.snapshot.isActive) {
                            stopPolling = true
                            break
                        }
                        LiveSessionViewerRepository.computeViewerAdaptivePollDelay(
                            powerSaveMode = res.snapshot.lastLocation?.powerSaveMode == true,
                            batteryLevel = res.snapshot.lastLocation?.batteryLevel,
                            basePollDelay = 5_000L
                        )
                    }
                    LiveSessionFetchResult.NotFoundOrExpired -> {
                        isExpiredOrMissing = true
                        isLoading = false
                        stopPolling = true
                        break
                    }
                    is LiveSessionFetchResult.Error -> {
                        consecutiveErrors++
                        errorMessage = res.message
                        isLoading = false
                        LiveSessionViewerRepository.computeViewerBackoffDelay(consecutiveErrors)
                    }
                }
                tick++
                delay(nextDelay)
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

    var lastCameraTarget by remember(sessionId) { mutableStateOf<LatLng?>(null) }
    val currentLatLng = snapshot?.lastLocation?.let { LatLng(it.lat, it.lng) }
    LaunchedEffect(currentLatLng, isFollowingRider) {
        val target = currentLatLng ?: return@LaunchedEffect
        if (!isFollowingRider && hasCenteredInitially) return@LaunchedEffect
        val shouldMove = !hasCenteredInitially || LiveSessionViewerRepository.shouldAnimateCamera(
            lastTargetLat = lastCameraTarget?.latitude,
            lastTargetLng = lastCameraTarget?.longitude,
            newTargetLat = target.latitude,
            newTargetLng = target.longitude,
        )
        if (shouldMove) {
            lastCameraTarget = target
            val zoom = cameraPositionState.position.zoom.coerceAtLeast(15.5f)
            if (isReducedMotion) {
                cameraPositionState.moveSafely {
                    CameraUpdateFactory.newLatLngZoom(target, zoom)
                }
            } else {
                cameraPositionState.animateSafely {
                    CameraUpdateFactory.newLatLngZoom(target, zoom)
                }
            }
            hasCenteredInitially = true
        }
    }

    val startIcon = remember { runCatching { ExportMarkers.start(ExportMarkerStyle.StartFinish, 44) }.getOrNull() }
    val finishIcon = remember { runCatching { ExportMarkers.finish(ExportMarkerStyle.StartFinish, 44) }.getOrNull() }
    val headingIcon = remember { runCatching { createHeadingConeMarkerBitmap() }.getOrNull() }
    val stationaryDotIcon = remember { runCatching { createStationaryDotMarkerBitmap() }.getOrNull() }

    val lastLoc = snapshot?.lastLocation
    val ageSeconds = remember(lastLoc?.timestampIso, nowEpochMillis) {
        LiveSessionViewerRepository.ageSeconds(lastLoc?.timestampIso, nowEpochMillis)
    }
    val isStaleSignal = remember(snapshot?.isActive, lastLoc?.timestampIso, nowEpochMillis) {
        snapshot?.isStaleSignal(nowEpochMillis) == true
    }
    val isDelayedUpdate = remember(snapshot?.isActive, lastLoc?.powerSaveMode, lastLoc?.timestampIso, isStaleSignal, nowEpochMillis) {
        snapshot?.isActive == true && !isStaleSignal && LiveSessionViewerRepository.isDelayedUpdate(
            powerSaveMode = lastLoc?.powerSaveMode == true,
            timestampIso = lastLoc?.timestampIso,
            nowEpochMillis = nowEpochMillis,
        )
    }

    val lastLocEpochMs = remember(lastLoc?.timestampIso) {
        LiveSessionViewerRepository.parseTimestampMs(lastLoc?.timestampIso)
    }

    LaunchedEffect(lastLoc?.speedKmh, lastLocEpochMs) {
        stoppedSinceEpochMs = LiveSessionViewerRepository.computeStoppedSinceEpochMs(
            speedKmh = lastLoc?.speedKmh,
            previousStoppedSinceMs = stoppedSinceEpochMs,
            lastLocationTimestampEpochMs = lastLocEpochMs,
            nowEpochMillis = nowEpochMillis,
        )
    }

    val speedStatus = remember(lastLoc?.speedKmh, stoppedSinceEpochMs, nowEpochMillis, isImperial, strings) {
        LiveSessionViewerRepository.evaluateSpeedOrStopped(
            speedKmh = lastLoc?.speedKmh,
            stoppedSinceEpochMs = stoppedSinceEpochMs,
            nowEpochMillis = nowEpochMillis,
            useMiles = isImperial,
            strings = strings,
        )
    }

    LaunchedEffect(snapshot?.lastLocation?.headingDeg, speedStatus.isStationary) {
        val target = snapshot?.lastLocation?.headingDeg ?: return@LaunchedEffect
        if (!hasInitializedBearing) {
            continuousBearing = target
            hasInitializedBearing = true
        } else if (LiveSessionViewerRepository.shouldUpdateBearing(continuousBearing, target, speedStatus.isStationary)) {
            continuousBearing = LiveSessionViewerRepository.computeContinuousBearing(continuousBearing, target)
        }
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
                                    modifier = Modifier.semantics(mergeDescendants = true) {},
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
                            if (isStaleSignal) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                    modifier = Modifier.semantics {
                                        contentDescription = strings.liveViewerSignalPausedA11y
                                    },
                                ) {
                                    Text(
                                        text = strings.liveViewerSignalPausedBadge,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFEF4444),
                                    )
                                }
                            } else if (isDelayedUpdate) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                    modifier = Modifier.semantics {
                                        contentDescription = strings.liveViewerDelayedBadgeA11y
                                    },
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
                            Icon(Icons.Default.Close, contentDescription = strings.close)
                        }
                    },
                    actions = {
                        val currentUnitText = if (isImperial) "mph" else "km/h"
                        TextButton(
                            onClick = { isImperial = !isImperial },
                            modifier = Modifier.semantics {
                                role = Role.Button
                                contentDescription = String.format(Locale.getDefault(), strings.liveViewerToggleUnitsA11y, currentUnitText)
                            },
                        ) {
                            Text(
                                text = currentUnitText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
                            val renderPath = remember(pathLatLng) {
                                LiveSessionViewerRepository.decimateLatLngs(pathLatLng, epsilonMeters = 3.5, minPointsThreshold = 200)
                            }
                            Polyline(
                                points = renderPath,
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
                            val isEnded = snapshot?.isActive == false
                            val isStationary = speedStatus.isStationary
                            val hasHeading = lastLoc?.headingDeg != null && !isEnded && !isStationary
                            val markerIcon = if (isEnded) {
                                finishIcon ?: (stationaryDotIcon ?: headingIcon)
                            } else if (hasHeading) {
                                headingIcon
                            } else {
                                stationaryDotIcon ?: headingIcon
                            }
                            Marker(
                                state = MarkerState(position = livePoint),
                                icon = markerIcon,
                                rotation = if (hasHeading) animatedBearing else 0f,
                                flat = !isEnded,
                                anchor = Offset(0.5f, 0.5f),
                                title = if (isEnded) {
                                    snapshot?.destination?.takeIf { it.isNotBlank() } ?: snapshot?.username
                                } else {
                                    snapshot?.username
                                },
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
                        val noticeText = snapshot?.stopReason?.takeIf { it.isNotBlank() }
                            ?: snapshot?.endReason?.takeIf { it.isNotBlank() }
                            ?: if (snapshot != null && !snapshot!!.isActive) strings.liveViewerRiderEndedNotice else strings.liveViewerExpiredNotice
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp)
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = noticeText
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 4.dp,
                        ) {
                            Text(
                                text = noticeText,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (errorMessage != null && snapshot == null) {
                        val errorBannerA11y = "${errorMessage.orEmpty()}. ${strings.retry}"
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp)
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = errorBannerA11y
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            tonalElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = errorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                FilledTonalButton(
                                    onClick = { refreshTrigger++ },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                        contentColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                ) {
                                    Text(
                                        text = strings.retry,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
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
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isOffline && snapshot != null) {
                                val offlineA11y = strings.liveViewerNetworkOfflineToast
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                    tonalElevation = 6.dp,
                                    shadowElevation = 4.dp,
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = offlineA11y
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = strings.liveViewerNetworkOfflineToast,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else if (showReconnectedToast && snapshot != null) {
                                val reconnectedA11y = strings.liveViewerNetworkReconnectedToast
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                    tonalElevation = 6.dp,
                                    shadowElevation = 4.dp,
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = reconnectedA11y
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = strings.liveViewerNetworkReconnectedToast,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else if (isStaleSignal && currentLatLng != null) {
                                val staleA11y = "${strings.liveViewerSignalPausedBanner}. ${strings.retry}"
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                    tonalElevation = 4.dp,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = staleA11y
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = strings.liveViewerSignalPausedBanner,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        IconButton(
                                            onClick = { refreshTrigger++ },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = strings.retry,
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            } else if (errorMessage != null) {
                                val reconnectingA11y = "${strings.liveViewerReconnecting}. ${strings.retry}"
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.95f),
                                    tonalElevation = 4.dp,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = reconnectingA11y
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = strings.liveViewerReconnecting,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF1F2937),
                                        )
                                        FilledTonalButton(
                                            onClick = { refreshTrigger++ },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = Color.White.copy(alpha = 0.9f),
                                                contentColor = Color(0xFF1F2937),
                                            ),
                                        ) {
                                            Text(
                                                text = strings.retry,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }

                            // CIE-022 / CIE-037: Low battery warning banner (<15%) with TalkBack liveRegion announcement
                            val battery = LiveSessionViewerRepository.clampBatteryLevel(lastLoc?.batteryLevel)
                            val isLowBattery = battery != null && battery <= 15 && snapshot?.isActive == true
                            if (isLowBattery && currentLatLng != null) {
                                val lowBatteryBannerText = String.format(Locale.getDefault(), strings.liveViewerLowBatteryBanner, battery)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                    tonalElevation = 4.dp,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = lowBatteryBannerText
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BatteryAlert,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = lowBatteryBannerText,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
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
                                            if (isReducedMotion) {
                                                cameraPositionState.moveSafely {
                                                    CameraUpdateFactory.newLatLngZoom(currentLatLng, zoom)
                                                }
                                            } else {
                                                cameraPositionState.animateSafely {
                                                    CameraUpdateFactory.newLatLngZoom(currentLatLng, zoom)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        role = Role.Button
                                        contentDescription = strings.liveViewerRecenterA11y
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
                                            if (isReducedMotion) {
                                                cameraPositionState.moveSafely {
                                                    CameraUpdateFactory.newLatLngBounds(bounds, 96)
                                                }
                                            } else {
                                                cameraPositionState.animateSafely {
                                                    CameraUpdateFactory.newLatLngBounds(bounds, 96)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        role = Role.Button
                                        contentDescription = strings.liveViewerFitRouteA11y
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
                        val currentSnapshot = snapshot
                        val destinationText = currentSnapshot?.destination?.takeIf { it.isNotBlank() }
                        val deadlineStatus = if (currentSnapshot?.isActive == true) currentSnapshot.deadlineStatus(nowEpochMillis) else null
                        val showHeaderMeta = destinationText != null || ageSeconds != null || deadlineStatus != null
                        if (showHeaderMeta) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (destinationText != null || ageSeconds != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (destinationText != null) {
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f, fill = false)
                                                    .semantics(mergeDescendants = true) {
                                                        contentDescription = String.format(Locale.getDefault(), strings.liveViewerDestinationA11y, destinationText)
                                                    },
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
                                                modifier = Modifier.semantics(mergeDescendants = true) {},
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
                                deadlineStatus?.let { deadline ->
                                    val deadlineColor = if (deadline.isOverdue) Color(0xFFD97706) else Color(0xFF4338CA)
                                    val deadlineBg = if (deadline.isOverdue) Color(0xFFFEF3C7) else Color(0xFFEEF2FF)
                                    val deadlineLabel = if (deadline.isOverdue) {
                                        String.format(Locale.getDefault(), strings.liveViewerOverdueBy, deadline.timeFormatted)
                                    } else {
                                        String.format(Locale.getDefault(), strings.liveViewerExpectedBy, deadline.timeFormatted)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = deadlineBg,
                                        modifier = Modifier.semantics(mergeDescendants = true) {
                                            contentDescription = deadlineLabel
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (deadline.isOverdue) Icons.Default.Warning else Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = deadlineColor,
                                            )
                                            Text(
                                                text = deadlineLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = deadlineColor,
                                                maxLines = 1,
                                            )
                                        }
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
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                            ) {
                                val speedText = speedStatus.displayText
                                val currentUnitText = if (isImperial) "mph" else "km/h"
                                Row(
                                    modifier = Modifier
                                        .clickable { isImperial = !isImperial }
                                        .semantics(mergeDescendants = true) {
                                            role = Role.Button
                                            contentDescription = "${speedStatus.a11yText}. ${String.format(Locale.getDefault(), strings.liveViewerToggleUnitsA11y, currentUnitText)}"
                                        },
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

                                lastLoc?.batteryLevel?.let { rawBattery ->
                                    val battery = LiveSessionViewerRepository.clampBatteryLevel(rawBattery) ?: return@let
                                    // CIE-008: Visual alert & accessibility announcement when rider battery <= 15%
                                    val isLowBattery = battery <= 15
                                    val batteryColor = if (isLowBattery) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    val batteryA11y = if (isLowBattery) {
                                        String.format(Locale.getDefault(), strings.liveViewerBatteryLowA11y, battery)
                                    } else {
                                        String.format(Locale.getDefault(), strings.liveViewerBatteryA11y, battery)
                                    }
                                    Row(
                                        modifier = Modifier.semantics(mergeDescendants = true) {
                                            contentDescription = batteryA11y
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isLowBattery) Icons.Default.BatteryAlert else Icons.Default.BatteryStd,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = batteryColor,
                                        )
                                        Text(
                                            text = "$battery%",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isLowBattery) FontWeight.SemiBold else FontWeight.Normal,
                                            color = batteryColor,
                                        )
                                    }
                                }

                                val durationText = snapshot?.formatDuration(nowEpochMillis)
                                durationText?.let { duration ->
                                    Row(
                                        modifier = Modifier.semantics(mergeDescendants = true) {
                                            contentDescription = String.format(Locale.getDefault(), strings.liveViewerDurationLabel, duration)
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color(0xFF06B6D4),
                                        )
                                        Text(
                                            text = duration,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                val totalDistance = snapshot?.totalDistanceMeters ?: 0.0
                                if (totalDistance > 0.0) {
                                    val distanceText = snapshot?.formatDistance(useMiles = isImperial)
                                        ?: UnitFormatter.rideDistance(totalDistance, isImperial)
                                    Row(
                                        modifier = Modifier
                                            .clickable { isImperial = !isImperial }
                                            .semantics(mergeDescendants = true) {
                                                role = Role.Button
                                                contentDescription = "${String.format(Locale.getDefault(), strings.liveViewerDistanceLabel, distanceText)}. ${String.format(Locale.getDefault(), strings.liveViewerToggleUnitsA11y, currentUnitText)}"
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Route,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = distanceText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        val riderName = currentSnapshot?.username?.trim()?.takeIf { it.isNotBlank() }
                                        val browserUrl = LiveSessionViewerRepository.buildShareUrl(AppConfig.LIVE_SHARE_BASE_URL, sessionId, riderName)
                                        val shareMessage = if (riderName != null) {
                                            String.format(Locale.getDefault(), strings.liveViewerShareMessageWithName, riderName, browserUrl)
                                        } else {
                                            String.format(Locale.getDefault(), strings.liveViewerShareMessageFallback, browserUrl)
                                        }
                                        AnalyticsManager.trackLiveShareViewerShareClicked(sessionId)
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, strings.liveViewerShareRideSubject)
                                            putExtra(Intent.EXTRA_TEXT, shareMessage)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(sendIntent, strings.liveViewerShareRide))
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        role = Role.Button
                                        contentDescription = strings.liveViewerShareRideA11y
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.liveViewerShareRide)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val riderName = currentSnapshot?.username?.trim()?.takeIf { it.isNotBlank() }
                                        val browserUrl = LiveSessionViewerRepository.buildShareUrl(AppConfig.LIVE_SHARE_BASE_URL, sessionId, riderName)
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        role = Role.Button
                                        contentDescription = strings.liveViewerOpenInBrowserA11y
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
