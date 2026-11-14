package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import com.google.android.gms.maps.model.LatLng

data class LiveViewerSpeedStatus(
    val isStationary: Boolean,
    val isStoppedTimerActive: Boolean,
    val stoppedMinutes: Int,
    val displayText: String,
    val a11yText: String,
)

data class LiveViewerPoint(
    val lat: Double,
    val lng: Double,
)

data class LiveViewerDeadlineStatus(
    val isOverdue: Boolean,
    val deadlineEpochMs: Long,
    val timeFormatted: String,
    val overdueMinutes: Long,
)

data class ViewerForegroundReconnectionState(
    val shouldFetchImmediately: Boolean,
    val resetConsecutiveErrors: Boolean,
    val clearErrorMessage: Boolean,
)

data class ViewerNetworkReconnectionState(
    val shouldFetchImmediately: Boolean,
    val resetConsecutiveErrors: Boolean,
    val showOfflineToast: Boolean,
)

/**
 * CIE-044: Spectator idle inactivity and render throttling evaluation.
 */
data class SpectatorThrottleEvaluation(
    val isInactive: Boolean,
    val isThrottled: Boolean,
    val pollDelayMs: Long,
    val shouldAnimateMap: Boolean,
    val noticeText: String? = null,
)

data class LiveViewerLastLocation(
    val lat: Double,
    val lng: Double,
    /** Wire contract on `/api/track/[sessionId]/location` reports speed in km/h. */
    val speedKmh: Float?,
    val headingDeg: Float?,
    val batteryLevel: Int?,
    val powerSaveMode: Boolean,
    val timestampIso: String?,
) {
    val speedMps: Float?
        get() = speedKmh?.let { kotlin.math.max(0f, it) / 3.6f }
}

data class LiveSessionSnapshot(
    val sessionId: String,
    val username: String,
    val status: String,
    val startedAt: String? = null,
    val stopReason: String?,
    val endReason: String?,
    val endedAt: String?,
    val destination: String?,
    val deadlineAt: String?,
    val lastLocation: LiveViewerLastLocation?,
    val path: List<LiveViewerPoint>,
) {
    val isActive: Boolean
        get() = status.equals("active", ignoreCase = true)

    fun formatDuration(nowEpochMillis: Long = System.currentTimeMillis()): String? {
        return LiveSessionViewerRepository.formatDuration(startedAt, endedAt, nowEpochMillis)
    }

    val totalDistanceMeters: Double
        get() = LiveSessionViewerRepository.computePathDistanceMeters(path)

    fun formatDistance(useMiles: Boolean = false): String {
        return LiveSessionViewerRepository.formatDistance(totalDistanceMeters, useMiles)
    }

    fun deadlineStatus(
        nowEpochMillis: Long = System.currentTimeMillis(),
        timeFormatter: ((Long) -> String)? = null,
    ): LiveViewerDeadlineStatus? {
        return LiveSessionViewerRepository.evaluateDeadline(deadlineAt, nowEpochMillis, timeFormatter)
    }

    fun isStaleSignal(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return LiveSessionViewerRepository.isStaleSignal(
            isActive = isActive,
            timestampIso = lastLocation?.timestampIso,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /**
     * CIE-039: Evaluates and formats a concluded trip summary with stop reason, elapsed duration,
     * total distance, and average speed with accessible TalkBack announcement text.
     */
    fun concludedSummary(
        useMiles: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): LiveViewerConcludedSummary {
        return LiveSessionViewerRepository.formatConcludedSummary(
            username = username,
            startedAt = startedAt,
            endedAt = endedAt,
            stopReason = stopReason ?: endReason,
            totalDistanceMeters = totalDistanceMeters,
            useMiles = useMiles,
            nowEpochMillis = nowEpochMillis,
        )
    }
}

data class LiveViewerConcludedSummary(
    val statusText: String,
    val detailsText: String,
    val a11yText: String,
    val avgSpeedStr: String?,
    val durationStr: String,
    val distanceStr: String,
)

sealed class LiveSessionFetchResult {
    data class Success(val snapshot: LiveSessionSnapshot) : LiveSessionFetchResult()
    data object NotFoundOrExpired : LiveSessionFetchResult()
    data class Error(val message: String) : LiveSessionFetchResult()
}

/**
 * TASK-366: Lightweight client for the anonymous viewer endpoints:
 * - `GET /api/track/{sessionId}/location` (Edge-cached, public by contract)
 * - `POST /api/track/{sessionId}/ping` (Viewer presence heartbeat so the rider sees `activeViewers`)
 */
class LiveSessionViewerRepository {

    fun getCachedSnapshot(sessionId: String): LiveSessionSnapshot? = Companion.getCachedSnapshot(sessionId)

    fun shouldAnimateTransition(isReducedMotion: Boolean): Boolean = Companion.shouldAnimateTransition(isReducedMotion)

    fun clampBatteryLevel(battery: Int?): Int? = Companion.clampBatteryLevel(battery)
    fun shouldAnnounceLowBattery(previousBattery: Int?, newBattery: Int?): Boolean = Companion.shouldAnnounceLowBattery(previousBattery, newBattery)
    fun computeViewerAdaptivePollDelay(powerSaveMode: Boolean, batteryLevel: Int?, basePollDelay: Long = 5_000L): Long =
        Companion.computeViewerAdaptivePollDelay(powerSaveMode, batteryLevel, basePollDelay)

    fun evaluateSpectatorThrottleState(
        isTripEnded: Boolean,
        isBackground: Boolean = false,
        lastInteractionEpochMs: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        riderPowerSave: Boolean = false,
        riderBatteryLevel: Int? = null,
        basePollMs: Long = 5_000L,
        inactivityThresholdMs: Long = 300_000L,
    ): SpectatorThrottleEvaluation = Companion.evaluateSpectatorThrottleState(
        isTripEnded = isTripEnded,
        isBackground = isBackground,
        lastInteractionEpochMs = lastInteractionEpochMs,
        nowEpochMs = nowEpochMs,
        riderPowerSave = riderPowerSave,
        riderBatteryLevel = riderBatteryLevel,
        basePollMs = basePollMs,
        inactivityThresholdMs = inactivityThresholdMs,
    )

    fun shouldDecimatePolyline(pointCount: Int, minPointsThreshold: Int = 200): Boolean =
        Companion.shouldDecimatePolyline(pointCount, minPointsThreshold)

    fun decimatePoints(
        points: List<LiveViewerPoint>,
        epsilonMeters: Double = 3.5,
        minPointsThreshold: Int = 200
    ): List<LiveViewerPoint> = Companion.decimatePoints(points, epsilonMeters, minPointsThreshold)

    fun decimateLatLngs(
        points: List<LatLng>,
        epsilonMeters: Double = 3.5,
        minPointsThreshold: Int = 200
    ): List<LatLng> = Companion.decimateLatLngs(points, epsilonMeters, minPointsThreshold)

    fun computeElapsedSeconds(
        startedAt: String?,
        endedAt: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long? = Companion.computeElapsedSeconds(startedAt, endedAt, nowEpochMillis)

    fun computeAverageSpeed(
        distanceMeters: Double,
        elapsedSeconds: Long,
    ): Double? = Companion.computeAverageSpeed(distanceMeters, elapsedSeconds)

    fun formatAverageSpeed(
        avgSpeedMps: Double?,
        useMiles: Boolean = false,
    ): String? = Companion.formatAverageSpeed(avgSpeedMps, useMiles)

    fun formatConcludedSummary(
        username: String?,
        startedAt: String?,
        endedAt: String?,
        stopReason: String?,
        totalDistanceMeters: Double,
        useMiles: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): LiveViewerConcludedSummary = Companion.formatConcludedSummary(
        username = username,
        startedAt = startedAt,
        endedAt = endedAt,
        stopReason = stopReason,
        totalDistanceMeters = totalDistanceMeters,
        useMiles = useMiles,
        nowEpochMillis = nowEpochMillis,
    )

    fun computeOffCenterDistanceMeters(
        centerLat: Double,
        centerLng: Double,
        riderLat: Double,
        riderLng: Double,
    ): Double = Companion.computeOffCenterDistanceMeters(centerLat, centerLng, riderLat, riderLng)

    fun formatOffCenterDistance(
        distanceMeters: Double?,
        useMiles: Boolean = false,
        thresholdMeters: Double = 250.0,
    ): String? = Companion.formatOffCenterDistance(distanceMeters, useMiles, thresholdMeters)

    fun formatRecenterButtonLabel(
        distanceMeters: Double?,
        useMiles: Boolean = false,
        thresholdMeters: Double = 250.0,
        baseLabel: String = "Recenter",
    ): String = Companion.formatRecenterButtonLabel(distanceMeters, useMiles, thresholdMeters, baseLabel)

    fun formatRecenterAccessibilityLabel(
        distanceMeters: Double?,
        useMiles: Boolean = false,
        thresholdMeters: Double = 250.0,
        baseLabel: String = "Recenter on rider",
    ): String = Companion.formatRecenterAccessibilityLabel(distanceMeters, useMiles, thresholdMeters, baseLabel)

    fun shouldResumeFollowMode(
        isFollowing: Boolean,
        isEnded: Boolean,
        lastInteractionEpochMs: Long?,
        nowEpochMillis: Long = System.currentTimeMillis(),
        timeoutMs: Long = 20_000L,
    ): Boolean = Companion.shouldResumeFollowMode(
        isFollowing = isFollowing,
        isEnded = isEnded,
        lastInteractionEpochMs = lastInteractionEpochMs,
        nowEpochMillis = nowEpochMillis,
        timeoutMs = timeoutMs,
    )

    fun computePollLatencyMs(
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis()
    ): Long = Companion.computePollLatencyMs(startTimeMs, endTimeMs)

    fun shouldSampleViewerHealth(
        sampleRate: Double = 0.01,
        randomProvider: () -> Double = { Math.random() }
    ): Boolean = Companion.shouldSampleViewerHealth(sampleRate, randomProvider)

    fun classifyViewerError(
        statusCode: Int?,
        errorMessage: String?
    ): String = Companion.classifyViewerError(statusCode, errorMessage)

    fun buildViewerHealthTelemetryPayload(
        sessionId: String,
        pollLatencyMs: Long,
        statusCode: Int,
        isActive: Boolean,
        hasLocation: Boolean,
        batteryLevel: Int?,
        pointCount: Int,
        isStale: Boolean = false
    ): Map<String, Any> = Companion.buildViewerHealthTelemetryPayload(
        sessionId = sessionId,
        pollLatencyMs = pollLatencyMs,
        statusCode = statusCode,
        isActive = isActive,
        hasLocation = hasLocation,
        batteryLevel = batteryLevel,
        pointCount = pointCount,
        isStale = isStale,
    )

    fun shouldPreserveViewerState(
        hasRenderedLocation: Boolean,
        isOffline: Boolean
    ): Boolean = Companion.shouldPreserveViewerState(hasRenderedLocation, isOffline)

    fun evaluateReconnectionResync(
        isOnline: Boolean,
        wasOffline: Boolean,
        hasCachedSnapshot: Boolean,
        isTripEnded: Boolean,
        isFetchInProgress: Boolean = false
    ): ReconnectionResyncEvaluation = Companion.evaluateReconnectionResync(
        isOnline = isOnline,
        wasOffline = wasOffline,
        hasCachedSnapshot = hasCachedSnapshot,
        isTripEnded = isTripEnded,
        isFetchInProgress = isFetchInProgress
    )

    /**
     * CIE-043: Formats localized share text for forwarding a live session.
     */
    fun formatLiveShareMessage(
        username: String?,
        shareUrl: String,
        nameTemplate: String = "Track %1\$s's live ride on TrackMe: %2\$s",
        fallbackTemplate: String = "Track this live ride on TrackMe: %1\$s"
    ): String = Companion.formatLiveShareMessage(username, shareUrl, nameTemplate, fallbackTemplate)

    /**
     * CIE-043: Constructs direct external navigation URL to rider coordinate.
     */
    fun buildDirectionsUrl(lat: Double, lng: Double, provider: String = "google_maps"): String? =
        Companion.buildDirectionsUrl(lat, lng, provider)

    suspend fun fetchSnapshot(sessionId: String): LiveSessionFetchResult = withContext(Dispatchers.IO) {
        val cleanId = sessionId.trim()
        if (cleanId.isEmpty()) return@withContext LiveSessionFetchResult.NotFoundOrExpired
        val encodedId = java.net.URLEncoder.encode(cleanId, java.nio.charset.StandardCharsets.UTF_8.name())
        val url = "${AppConfig.LIVE_SHARE_BASE_URL}${AppConfig.LIVE_SHARE_LOCATION_ENDPOINT_TEMPLATE.format(encodedId)}"
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND || code == 410) {
                    return@runCatching LiveSessionFetchResult.NotFoundOrExpired
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    return@runCatching LiveSessionFetchResult.Error(
                        err?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                    )
                }
                val parsed = parseSnapshot(cleanId, body)
                    ?: return@runCatching LiveSessionFetchResult.Error("Invalid response payload")
                cacheSnapshot(cleanId, parsed)
                LiveSessionFetchResult.Success(parsed)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { error ->
            LiveSessionFetchResult.Error(error.message ?: "Network error")
        }
    }

    suspend fun sendViewerPing(sessionId: String, viewerId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanId = sessionId.trim()
        val cleanViewer = viewerId.trim()
        if (cleanId.isEmpty() || cleanViewer.isEmpty()) return@withContext false
        val url = "${AppConfig.LIVE_SHARE_BASE_URL}/api/track/$cleanId/ping"
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            try {
                val payload = JSONObject().put("viewerId", cleanViewer).toString()
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        /**
         * CIE-035: In-memory snapshot cache to eliminate blank screen flicker and loading spinners
         * across device orientation (portrait <-> landscape) and theme configuration changes.
         */
        private val snapshotCache = java.util.concurrent.ConcurrentHashMap<String, LiveSessionSnapshot>()

        fun getCachedSnapshot(sessionId: String): LiveSessionSnapshot? {
            val clean = sessionId.trim()
            return if (clean.isNotEmpty()) snapshotCache[clean] else null
        }

        fun cacheSnapshot(sessionId: String, snapshot: LiveSessionSnapshot) {
            val clean = sessionId.trim()
            if (clean.isNotEmpty()) {
                snapshotCache[clean] = snapshot
            }
        }

        fun clearCachedSnapshot(sessionId: String) {
            val clean = sessionId.trim()
            if (clean.isNotEmpty()) {
                snapshotCache.remove(clean)
            }
        }

        fun clearAllCachedSnapshots() {
            snapshotCache.clear()
        }

        /**
         * CIE-035: Evaluates camera movement necessity considering follow mode and initial centering.
         */
        fun evaluateViewportPreservation(
            isFollowingRider: Boolean,
            lastTargetLat: Double?,
            lastTargetLng: Double?,
            newTargetLat: Double?,
            newTargetLng: Double?,
            hasCenteredInitially: Boolean,
        ): Boolean {
            if (newTargetLat == null || newTargetLng == null) return false
            if (!isFollowingRider && hasCenteredInitially) return false
            return !hasCenteredInitially || shouldAnimateCamera(
                lastTargetLat = lastTargetLat,
                lastTargetLng = lastTargetLng,
                newTargetLat = newTargetLat,
                newTargetLng = newTargetLng,
            )
        }

        /**
         * CIE-036: Evaluates whether camera and UI transitions should be animated based on reduced motion accessibility preference.
         * When reduced motion is preferred, transitions snap immediately to prevent motion sickness and vestibular discomfort.
         */
        fun shouldAnimateTransition(isReducedMotion: Boolean): Boolean = !isReducedMotion

        /**
         * CIE-037: Sanitize and clamp battery level to 0-100%, rejecting negative simulator fallbacks (-1) and out-of-bounds values.
         */
        fun clampBatteryLevel(battery: Int?): Int? {
            if (battery == null || battery < 0 || battery > 100) return null
            return battery.coerceIn(0, 100)
        }

        /**
         * CIE-037: Evaluates whether an auditory/TalkBack announcement should be made for low battery.
         * Throttles announcements to prevent auditory spam:
         * - Fires when first transitioning into low battery (<= 15%).
         * - Fires when battery drops to critical level (<= 5%) from a higher level.
         * - Suppresses redundant announcements on intermediate 1% drops (e.g. 14%, 13%, 12%).
         */
        fun shouldAnnounceLowBattery(previousBattery: Int?, newBattery: Int?): Boolean {
            val curr = clampBatteryLevel(newBattery) ?: return false
            val prev = clampBatteryLevel(previousBattery)

            // 1. Initial warning when dipping into low battery (<= 15%)
            if (curr <= 15 && (prev == null || prev > 15)) {
                return true
            }

            // 2. Critical warning when dipping into <= 5%
            if (curr <= 5 && prev != null && prev > 5) {
                return true
            }

            return false
        }

        /**
         * CIE-037: Computes adaptive polling interval for the live viewer.
         * When the rider is in power-save mode (or battery <= 15%), broadcaster pushes occur at ~15s cadence.
         * Adapting the viewer polling interval to 12s avoids burning ~66% unnecessary Redis queries on the $0/mo free tier.
         */
        fun computeViewerAdaptivePollDelay(
            powerSaveMode: Boolean,
            batteryLevel: Int?,
            basePollDelay: Long = 5_000L
        ): Long {
            val clampedBat = clampBatteryLevel(batteryLevel)
            val isEffectivePowerSave = powerSaveMode || (clampedBat != null && clampedBat <= 15)
            return if (isEffectivePowerSave) 12_000L else basePollDelay
        }

        /**
         * CIE-044: Evaluates spectator idle inactivity and render throttling state across Web & Mobile.
         */
        fun evaluateSpectatorThrottleState(
            isTripEnded: Boolean,
            isBackground: Boolean = false,
            lastInteractionEpochMs: Long? = null,
            nowEpochMs: Long = System.currentTimeMillis(),
            riderPowerSave: Boolean = false,
            riderBatteryLevel: Int? = null,
            basePollMs: Long = 5_000L,
            inactivityThresholdMs: Long = 300_000L // 5 minutes
        ): SpectatorThrottleEvaluation {
            if (isTripEnded) {
                return SpectatorThrottleEvaluation(
                    isInactive = false,
                    isThrottled = false,
                    pollDelayMs = basePollMs,
                    shouldAnimateMap = false,
                    noticeText = null
                )
            }

            if (isBackground) {
                return SpectatorThrottleEvaluation(
                    isInactive = false,
                    isThrottled = true,
                    pollDelayMs = 30_000L,
                    shouldAnimateMap = false,
                    noticeText = null
                )
            }

            val elapsed = if (lastInteractionEpochMs != null) {
                maxOf(0L, nowEpochMs - lastInteractionEpochMs)
            } else {
                0L
            }

            val isInactive = lastInteractionEpochMs != null && elapsed >= inactivityThresholdMs

            if (isInactive) {
                return SpectatorThrottleEvaluation(
                    isInactive = true,
                    isThrottled = true,
                    pollDelayMs = 15_000L,
                    shouldAnimateMap = false,
                    noticeText = "Viewer idle · Updates slowed to save battery"
                )
            }

            val clampedBat = clampBatteryLevel(riderBatteryLevel)
            val isEffectivePowerSave = riderPowerSave || (clampedBat != null && clampedBat <= 15)
            if (isEffectivePowerSave) {
                return SpectatorThrottleEvaluation(
                    isInactive = false,
                    isThrottled = true,
                    pollDelayMs = 12_000L,
                    shouldAnimateMap = true,
                    noticeText = "Power save active · Location updates every ~15s"
                )
            }

            return SpectatorThrottleEvaluation(
                isInactive = false,
                isThrottled = false,
                pollDelayMs = basePollMs,
                shouldAnimateMap = true,
                noticeText = null
            )
        }

        /**
         * CIE-037: Formats user-facing disclosure when updates arrive at relaxed interval due to power saving.
         */
        fun formatPowerSaveCadenceNotice(powerSaveMode: Boolean, batteryLevel: Int?): String? {
            val clampedBat = clampBatteryLevel(batteryLevel)
            val isEffectivePowerSave = powerSaveMode || (clampedBat != null && clampedBat <= 15)
            if (!isEffectivePowerSave) return null
            return "Power save active · Location updates every ~15s"
        }

        /**
         * CIE-038: Evaluates whether breadcrumb polyline decimation should be activated.
         * Activates when point count strictly exceeds minPointsThreshold (> 200).
         */
        fun shouldDecimatePolyline(pointCount: Int, minPointsThreshold: Int = 200): Boolean {
            return minPointsThreshold in 1 until pointCount
        }

        /**
         * CIE-038: Computes perpendicular distance in meters from a point (pLat, pLng)
         * to a line segment defined by (aLat, aLng) and (bLat, bLng) using equirectangular projection.
         */
        fun computePerpendicularDistanceMeters(
            pLat: Double,
            pLng: Double,
            aLat: Double,
            aLng: Double,
            bLat: Double,
            bLng: Double
        ): Double {
            val midLatRad = ((aLat + bLat) / 2.0) * (kotlin.math.PI / 180.0)
            val dx = (bLng - aLng) * kotlin.math.cos(midLatRad)
            val dy = bLat - aLat
            val lineLenSq = dx * dx + dy * dy

            if (lineLenSq < 1e-12) {
                val pMidLatRad = ((aLat + pLat) / 2.0) * (kotlin.math.PI / 180.0)
                val px = (pLng - aLng) * kotlin.math.cos(pMidLatRad)
                val py = pLat - aLat
                return kotlin.math.sqrt(px * px + py * py) * 111320.0
            }

            val px = (pLng - aLng) * kotlin.math.cos(midLatRad)
            val py = pLat - aLat
            val t = kotlin.math.max(0.0, kotlin.math.min(1.0, (px * dx + py * dy) / lineLenSq))

            val projX = t * dx
            val projY = t * dy
            val distSq = (px - projX) * (px - projX) + (py - projY) * (py - projY)
            return kotlin.math.sqrt(distSq) * 111320.0
        }

        /**
         * CIE-038: Decimates a sequence of coordinates using the Ramer-Douglas-Peucker (RDP) algorithm.
         * Reduces GPU memory allocation and frame drops during extended trips (1,000+ points)
         * while preserving turns, corners, and overall geometry within epsilonMeters tolerance.
         */
        fun decimatePoints(
            points: List<LiveViewerPoint>,
            epsilonMeters: Double = 3.5,
            minPointsThreshold: Int = 200
        ): List<LiveViewerPoint> {
            if (points.size <= 2) return points
            if (minPointsThreshold > 0 && points.size <= minPointsThreshold) return points

            fun rdp(subList: List<LiveViewerPoint>): List<LiveViewerPoint> {
                if (subList.size <= 2) return subList
                val start = subList.first()
                val end = subList.last()

                var maxDist = 0.0
                var maxIndex = 0

                for (i in 1 until subList.size - 1) {
                    val curr = subList[i]
                    val dist = computePerpendicularDistanceMeters(
                        curr.lat, curr.lng,
                        start.lat, start.lng,
                        end.lat, end.lng
                    )
                    if (dist > maxDist) {
                        maxDist = dist
                        maxIndex = i
                    }
                }

                return if (maxDist > epsilonMeters && maxIndex > 0) {
                    val left = rdp(subList.subList(0, maxIndex + 1))
                    val right = rdp(subList.subList(maxIndex, subList.size))
                    left.dropLast(1) + right
                } else {
                    listOf(start, end)
                }
            }

            return rdp(points)
        }

        fun decimateLatLngs(
            points: List<LatLng>,
            epsilonMeters: Double = 3.5,
            minPointsThreshold: Int = 200
        ): List<LatLng> {
            if (points.size <= 2) return points
            if (minPointsThreshold > 0 && points.size <= minPointsThreshold) return points
            val livePoints = points.map { LiveViewerPoint(it.latitude, it.longitude) }
            val decimated = decimatePoints(livePoints, epsilonMeters, minPointsThreshold)
            return decimated.map { LatLng(it.lat, it.lng) }
        }

        /**
         * TASK-366 / SCOPE_1.9.3 / CIE-031: Builds a canonical live share URL with optional rider attribution.
         * Appending ?name=... enables rich social link previews on messaging apps (WhatsApp, iMessage, Telegram, etc.).
         */
        fun buildShareUrl(baseUrl: String, sessionId: String, riderName: String? = null): String {
            val cleanBase = baseUrl.trimEnd('/')
            val cleanSession = URLEncoder.encode(sessionId.trim(), "UTF-8")
            val cleanName = riderName?.trim()?.takeIf { it.isNotBlank() }
            return if (cleanName != null) {
                val encodedName = URLEncoder.encode(cleanName, "UTF-8")
                "$cleanBase/live/$cleanSession?name=$encodedName"
            } else {
                "$cleanBase/live/$cleanSession"
            }
        }

        /**
         * TASK-366 / SCOPE_1.9.3 / CIE-032: Deadband thresholds to throttle camera pans and heading rotations,
         * reducing battery and GPU draw while stationary or experiencing GPS/compass jitter.
         */
        const val CAMERA_DEADBAND_METERS = 3.0
        const val BEARING_DEADBAND_DEGREES = 3.0f

        fun computeDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            if (lat1 == lat2 && lng1 == lng2) return 0.0
            return haversineDistanceMeters(lat1, lng1, lat2, lng2)
        }

        fun shouldAnimateCamera(
            lastTargetLat: Double?,
            lastTargetLng: Double?,
            newTargetLat: Double,
            newTargetLng: Double,
            thresholdMeters: Double = CAMERA_DEADBAND_METERS,
        ): Boolean {
            if (lastTargetLat == null || lastTargetLng == null) return true
            val dist = computeDistanceMeters(lastTargetLat, lastTargetLng, newTargetLat, newTargetLng)
            return dist >= thresholdMeters
        }

        fun shouldUpdateBearing(
            currentBearing: Float,
            targetBearing: Float,
            isStationary: Boolean,
            thresholdDegrees: Float = BEARING_DEADBAND_DEGREES,
        ): Boolean {
            if (isStationary) return false
            if (targetBearing.isNaN() || targetBearing.isInfinite()) return false
            if (currentBearing.isNaN() || currentBearing.isInfinite()) return true
            val delta = abs(computeShortestArcDelta(currentBearing.toDouble(), targetBearing.toDouble()))
            return delta >= thresholdDegrees
        }

        fun parseSnapshot(sessionId: String, body: String): LiveSessionSnapshot? {
            if (body.isBlank()) return null
            return runCatching {
                val json = JSONObject(body)
                val username = json.optString("username").trim().ifEmpty { "TrackMe Rider" }
                val status = json.optString("status").trim().ifEmpty { "active" }
                val stopReason = json.optString("stopReason").trim().takeIf { it.isNotEmpty() }
                val endReason = json.optString("endReason").trim().takeIf { it.isNotEmpty() }
                val endedAt = json.optString("endedAt").trim().takeIf { it.isNotEmpty() }
                val startedAt = if (json.has("startedAt") && !json.isNull("startedAt")) json.opt("startedAt")?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
                val destination = json.optString("destination").trim().takeIf { it.isNotEmpty() }
                val deadlineAt = json.optString("deadlineAt").trim().takeIf { it.isNotEmpty() }
                    ?: json.optString("etaAt").trim().takeIf { it.isNotEmpty() }

                val locObj = json.optJSONObject("lastLocation")
                val lastLocation = locObj?.let { loc ->
                    val lat = loc.optDouble("lat", Double.NaN)
                    val lng = when {
                        loc.has("lng") -> loc.optDouble("lng", Double.NaN)
                        loc.has("lon") -> loc.optDouble("lon", Double.NaN)
                        else -> Double.NaN
                    }
                    if (!lat.isFinite() || !lng.isFinite()) null
                    else LiveViewerLastLocation(
                        lat = lat,
                        lng = lng,
                        speedKmh = if (loc.has("speed") && !loc.isNull("speed")) loc.optDouble("speed").toFloat().takeIf { it.isFinite() } else null,
                        headingDeg = if (loc.has("heading") && !loc.isNull("heading")) loc.optDouble("heading").toFloat().takeIf { it.isFinite() } else null,
                        batteryLevel = if (loc.has("batteryLevel") && !loc.isNull("batteryLevel")) loc.optInt("batteryLevel") else null,
                        powerSaveMode = loc.optBoolean("powerSaveMode", false),
                        timestampIso = loc.optString("timestamp").trim().takeIf { it.isNotEmpty() },
                    )
                }

                val pathArray = json.optJSONArray("path")
                val path = ArrayList<LiveViewerPoint>(pathArray?.length() ?: 0)
                if (pathArray != null) {
                    for (i in 0 until pathArray.length()) {
                        val pair = pathArray.optJSONArray(i)
                        if (pair != null && pair.length() >= 2) {
                            val pLat = pair.optDouble(0, Double.NaN)
                            val pLng = pair.optDouble(1, Double.NaN)
                            if (pLat.isFinite() && pLng.isFinite()) {
                                path.add(LiveViewerPoint(pLat, pLng))
                            }
                            continue
                        }
                        val obj = pathArray.optJSONObject(i) ?: continue
                        val pLat = obj.optDouble("lat", Double.NaN)
                        val pLng = when {
                            obj.has("lng") -> obj.optDouble("lng", Double.NaN)
                            obj.has("lon") -> obj.optDouble("lon", Double.NaN)
                            else -> Double.NaN
                        }
                        if (pLat.isFinite() && pLng.isFinite()) {
                            path.add(LiveViewerPoint(pLat, pLng))
                        }
                    }
                }
                if (path.isEmpty() && lastLocation != null) {
                    path.add(LiveViewerPoint(lastLocation.lat, lastLocation.lng))
                }

                LiveSessionSnapshot(
                    sessionId = sessionId,
                    username = username,
                    status = status,
                    startedAt = startedAt,
                    stopReason = stopReason,
                    endReason = endReason,
                    endedAt = endedAt,
                    destination = destination,
                    deadlineAt = deadlineAt,
                    lastLocation = lastLocation,
                    path = path,
                )
            }.getOrNull()
        }

        const val DELAYED_UPDATE_THRESHOLD_SECONDS = 45
        const val STALE_SIGNAL_THRESHOLD_SECONDS = 180

        fun parseTimestampMs(raw: String?): Long? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            clean.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            clean.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }?.let { return it.toLong() }
            val instantMs = runCatching { java.time.Instant.parse(clean).toEpochMilli() }.getOrNull()
            if (instantMs != null) return instantMs
            return runCatching { java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli() }.getOrNull()
        }

        fun ageSeconds(
            timestampIso: String?,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): Int? {
            val clean = timestampIso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsedMs = parseTimestampMs(clean) ?: return null
            return kotlin.math.max(0L, (nowEpochMillis - parsedMs) / 1000L).toInt()
        }

        fun isDelayedUpdate(
            powerSaveMode: Boolean,
            timestampIso: String?,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): Boolean {
            if (powerSaveMode) return true
            val age = ageSeconds(timestampIso, nowEpochMillis) ?: return false
            return age >= DELAYED_UPDATE_THRESHOLD_SECONDS
        }

        fun isStaleSignal(
            isActive: Boolean,
            timestampIso: String?,
            nowEpochMillis: Long = System.currentTimeMillis(),
            thresholdSeconds: Int = STALE_SIGNAL_THRESHOLD_SECONDS,
        ): Boolean {
            if (!isActive) return false
            val age = ageSeconds(timestampIso, nowEpochMillis) ?: return false
            return age >= thresholdSeconds
        }

        /**
         * Formats the elapsed or total trip duration into a concise string.
         * Examples:
         * - < 1 min: "< 1m"
         * - 45 min: "45m"
         * - 1 hour 15 min: "1h 15m"
         * - 2 hours exactly: "2h"
         */
        fun formatDuration(
            startedAtRaw: String?,
            endedAtRaw: String? = null,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): String? {
            val startMs = parseTimestampMs(startedAtRaw) ?: return null
            val endMs = parseTimestampMs(endedAtRaw) ?: nowEpochMillis
            val durationMs = kotlin.math.max(0L, endMs - startMs)
            val totalMinutes = durationMs / 60_000L

            if (totalMinutes < 1L) {
                return "< 1m"
            }
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L

            return when {
                hours == 0L -> "${minutes}m"
                minutes == 0L -> "${hours}h"
                else -> "${hours}h ${minutes}m"
            }
        }

        /**
         * Computes an adaptive, jittered polling delay for Live Share viewers during network errors or server hiccups (CIE-003).
         * Backs off consecutive errors to avoid stampeding Redis and Vercel serverless ($0/mo cost mandate).
         * Progression:
         * - 0 errors: [baseDelayMs] (standard 5,000ms polling)
         * - 1 error:  base + 1,000ms + jitter (~6,000-6,500ms)
         * - 2 errors: base + 3,000ms + jitter (~8,000-8,500ms)
         * - 3 errors: base + 6,000ms + jitter (~11,000-11,500ms)
         * - 4+ errors: capped at [maxDelayMs] (15,000ms)
         */
        fun computeViewerBackoffDelay(
            consecutiveErrors: Int,
            baseDelayMs: Long = 5_000L,
            maxDelayMs: Long = 15_000L,
            randomProvider: () -> Double = { Math.random() },
        ): Long {
            if (consecutiveErrors <= 0) return baseDelayMs
            val increments = longArrayOf(1_000L, 3_000L, 6_000L, 10_000L)
            val stepIndex = (consecutiveErrors - 1).coerceIn(0, increments.lastIndex)
            val step = increments[stepIndex]
            val jitter = (randomProvider() * 500L).toLong()
            return (baseDelayMs + step + jitter).coerceAtMost(maxDelayMs)
        }

        const val MAX_PLAUSIBLE_JUMP_METERS = 50_000.0
        const val MIN_ACCUMULATION_DISPLACEMENT_METERS = 2.0

        /**
         * Computes geodesic distance in meters between two lat/lng coordinates using the Haversine formula.
         */
        fun haversineDistanceMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val r = 6_371_000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0)
            val c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)))
            return r * c
        }

        /**
         * Computes accumulated distance in meters across the session's coordinate trail.
         * Discards sub-2m stationarity jitter and supersonic single-hop glitches (> 50 km).
         * Discards Null Island coordinates.
         */
        fun computePathDistanceMeters(path: List<LiveViewerPoint>?): Double {
            if (path.isNullOrEmpty() || path.size < 2) return 0.0
            var totalMeters = 0.0
            var prev: LiveViewerPoint? = null

            for (pt in path) {
                if (!pt.lat.isFinite() || !pt.lng.isFinite()) continue
                if (pt.lat < -90.0 || pt.lat > 90.0 || pt.lng < -180.0 || pt.lng > 180.0) continue
                if (kotlin.math.abs(pt.lat) < 0.0001 && kotlin.math.abs(pt.lng) < 0.0001) continue

                val prevPt = prev
                if (prevPt != null) {
                    val dist = haversineDistanceMeters(prevPt.lat, prevPt.lng, pt.lat, pt.lng)
                    if (dist >= MIN_ACCUMULATION_DISPLACEMENT_METERS && dist <= MAX_PLAUSIBLE_JUMP_METERS) {
                        totalMeters += dist
                        prev = pt
                    }
                } else {
                    prev = pt
                }
            }
            return totalMeters
        }

        /**
         * Formats meters into human-readable metric or imperial distance ("14.2 km" or "8.8 mi").
         */
        fun formatDistance(meters: Double, useMiles: Boolean = false): String {
            if (!meters.isFinite() || meters <= 0.0) {
                return if (useMiles) "0.0 mi" else "0.0 km"
            }
            return if (useMiles) {
                val miles = meters / 1609.344
                String.format(java.util.Locale.US, "%.1f mi", miles)
            } else {
                val km = meters / 1000.0
                String.format(java.util.Locale.US, "%.1f km", km)
            }
        }

        /**
         * Evaluates a session's scheduled deadline or ETA (CIE-025).
         * Calculates overdue status and formats the scheduled time string (e.g. "6:30 PM").
         */
        fun evaluateDeadline(
            deadlineRaw: String?,
            nowEpochMillis: Long = System.currentTimeMillis(),
            timeFormatter: ((Long) -> String)? = null,
        ): LiveViewerDeadlineStatus? {
            val deadlineMs = parseTimestampMs(deadlineRaw) ?: return null
            val isOverdue = nowEpochMillis > deadlineMs
            val overdueMinutes = if (isOverdue) (nowEpochMillis - deadlineMs) / 60_000L else 0L
            val formattedTime = timeFormatter?.invoke(deadlineMs) ?: run {
                val date = java.util.Date(deadlineMs)
                val format = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                format.format(date)
            }
            return LiveViewerDeadlineStatus(
                isOverdue = isOverdue,
                deadlineEpochMs = deadlineMs,
                timeFormatted = formattedTime,
                overdueMinutes = overdueMinutes,
            )
        }

        /**
         * Evaluates foreground reconnection transition for active live session viewers (CIE-027).
         *
         * When app returns to foreground:
         * - If trip has ended or session is expired/missing, suppresses polling.
         * - If active, triggers immediate location fetch without waiting for delay timer,
         *   resets consecutive error backoff counter, and clears stale error banner.
         */
        fun evaluateForegroundReconnection(
            isActive: Boolean,
            isExpiredOrMissing: Boolean,
        ): ViewerForegroundReconnectionState {
            val shouldFetch = isActive && !isExpiredOrMissing
            return ViewerForegroundReconnectionState(
                shouldFetchImmediately = shouldFetch,
                resetConsecutiveErrors = true,
                clearErrorMessage = true,
            )
        }

        /**
         * Evaluates network reconnection state transitions for live session viewers (CIE-034).
         *
         * When connection state changes:
         * - If trip has ended or session is missing/expired, suppresses network toasts and polling.
         * - If currently offline: shows non-blocking persistent offline toast pill.
         * - If transitioning from offline to online (reconnecting):
         *   triggers immediate location fetch without waiting for delay timer,
         *   and resets consecutive error backoff counter.
         */
        fun evaluateNetworkReconnection(
            isOffline: Boolean,
            wasOffline: Boolean,
            isActive: Boolean,
            isExpiredOrMissing: Boolean = false,
        ): ViewerNetworkReconnectionState {
            if (!isActive || isExpiredOrMissing) {
                return ViewerNetworkReconnectionState(
                    shouldFetchImmediately = false,
                    resetConsecutiveErrors = false,
                    showOfflineToast = false,
                )
            }
            val isReconnecting = wasOffline && !isOffline
            return ViewerNetworkReconnectionState(
                shouldFetchImmediately = isReconnecting,
                resetConsecutiveErrors = isReconnecting,
                showOfflineToast = isOffline,
            )
        }

        /**
         * Calculates the shortest signed angular difference between two compass bearings in degrees.
         * Returns a value in [-180.0, 180.0). Positive indicates clockwise turn, negative indicates counter-clockwise.
         *
         * Prevents 350-degree spin-around jitter when heading crosses the 0°/360° north boundary (CIE-028).
         */
        fun computeShortestArcDelta(fromAngle: Double, toAngle: Double): Double {
            if (!fromAngle.isFinite() || !toAngle.isFinite()) return 0.0
            var c = fromAngle % 360.0
            if (c < 0.0) c += 360.0
            var t = toAngle % 360.0
            if (t < 0.0) t += 360.0
            return ((t - c + 540.0) % 360.0) - 180.0
        }

        /**
         * Calculates continuous rotation angle by applying shortest-arc delta to existing continuous angle.
         * Used for smooth map marker rotation transitions without boundary wrap flips (CIE-028).
         */
        fun computeContinuousBearing(currentContinuousBearing: Float, targetBearing: Float): Float {
            if (!targetBearing.isFinite()) return currentContinuousBearing
            if (!currentContinuousBearing.isFinite()) return targetBearing
            val delta = computeShortestArcDelta(currentContinuousBearing.toDouble(), targetBearing.toDouble()).toFloat()
            return currentContinuousBearing + delta
        }

        const val STATIONARY_SPEED_THRESHOLD_KMH = 3.0f

        /**
         * Tracks stationary start timestamp across location updates (CIE-029).
         * Resets to null when moving (>= 3.0 km/h).
         */
        fun computeStoppedSinceEpochMs(
            speedKmh: Float?,
            previousStoppedSinceMs: Long?,
            lastLocationTimestampEpochMs: Long?,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): Long? {
            if (speedKmh != null && speedKmh >= STATIONARY_SPEED_THRESHOLD_KMH) {
                return null
            }
            if (previousStoppedSinceMs != null) {
                return previousStoppedSinceMs
            }
            if (lastLocationTimestampEpochMs != null && (nowEpochMillis - lastLocationTimestampEpochMs) in 0L..1_800_000L) {
                return lastLocationTimestampEpochMs
            }
            return nowEpochMillis
        }

        /**
         * Evaluates live rider speed vs stationary state and stopped duration (CIE-029).
         * When stationary for >= 60 seconds, displays "Stopped · Xm" instead of "0 km/h".
         */
        fun evaluateSpeedOrStopped(
            speedKmh: Float?,
            stoppedSinceEpochMs: Long?,
            nowEpochMillis: Long = System.currentTimeMillis(),
            useMiles: Boolean = false,
            strings: AppStrings,
        ): LiveViewerSpeedStatus {
            val isSpeedValid = speedKmh != null && speedKmh.isFinite()
            val effectiveSpeed = if (isSpeedValid) kotlin.math.max(0f, speedKmh!!) else null
            val isStationary = effectiveSpeed == null || effectiveSpeed < STATIONARY_SPEED_THRESHOLD_KMH
            val unit = if (useMiles) "mph" else "km/h"

            if (!isStationary) {
                val rawVal = if (useMiles) (effectiveSpeed!! * 0.621371f) else effectiveSpeed!!
                val rounded = kotlin.math.round(rawVal).toInt()
                val text = "$rounded $unit"
                val a11y = String.format(java.util.Locale.getDefault(), strings.liveViewerSpeedA11y, text)
                return LiveViewerSpeedStatus(
                    isStationary = false,
                    isStoppedTimerActive = false,
                    stoppedMinutes = 0,
                    displayText = text,
                    a11yText = a11y,
                )
            }

            if (stoppedSinceEpochMs != null && nowEpochMillis >= stoppedSinceEpochMs) {
                val stoppedDurationMs = kotlin.math.max(0L, nowEpochMillis - stoppedSinceEpochMs)
                val stoppedMinutes = (stoppedDurationMs / 60_000L).toInt()
                if (stoppedMinutes >= 1) {
                    val durationStr = formatDurationMs(stoppedDurationMs)
                    val text = String.format(java.util.Locale.getDefault(), strings.liveViewerStopped, durationStr)
                    val a11y = String.format(java.util.Locale.getDefault(), strings.liveViewerStoppedA11y, durationStr)
                    return LiveViewerSpeedStatus(
                        isStationary = true,
                        isStoppedTimerActive = true,
                        stoppedMinutes = stoppedMinutes,
                        displayText = text,
                        a11yText = a11y,
                    )
                }
            }

            val zeroText = "0 $unit"
            val zeroA11y = String.format(java.util.Locale.getDefault(), strings.liveViewerSpeedA11y, zeroText)
            return LiveViewerSpeedStatus(
                isStationary = true,
                isStoppedTimerActive = false,
                stoppedMinutes = 0,
                displayText = zeroText,
                a11yText = zeroA11y,
            )
        }

        fun formatDurationMs(durationMs: Long): String {
            val totalMinutes = kotlin.math.max(0L, durationMs) / 60_000L
            if (totalMinutes < 1L) return "< 1m"
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours == 0L -> "${minutes}m"
                minutes == 0L -> "${hours}h"
                else -> "${hours}h ${minutes}m"
            }
        }

        /**
         * CIE-039: Computes elapsed trip duration in whole seconds between startedAt and endedAt (or now).
         * Discards negative values and invalid timestamps.
         */
        fun computeElapsedSeconds(
            startedAt: String?,
            endedAt: String? = null,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): Long? {
            val startMs = parseTimestampMs(startedAt) ?: return null
            val endMs = parseTimestampMs(endedAt) ?: nowEpochMillis
            val elapsedMs = kotlin.math.max(0L, endMs - startMs)
            return elapsedMs / 1000L
        }

        /**
         * CIE-039: Computes average speed in meters per second across the concluded trip.
         * Discards sub-5s micro trips, negative distances, and supersonic single-hop glitches (> 200 km/h = 55.55 m/s).
         */
        fun computeAverageSpeed(
            distanceMeters: Double,
            elapsedSeconds: Long,
        ): Double? {
            if (!distanceMeters.isFinite() || distanceMeters <= 0.0 || elapsedSeconds < 5L) {
                return null
            }
            val speedMps = distanceMeters / elapsedSeconds.toDouble()
            if (!speedMps.isFinite() || speedMps < 0.1 || speedMps > 55.55) {
                return null
            }
            return speedMps
        }

        /**
         * CIE-039: Formats average speed in m/s into localized metric (km/h) or imperial (mph) string.
         */
        fun formatAverageSpeed(
            avgSpeedMps: Double?,
            useMiles: Boolean = false,
        ): String? {
            if (avgSpeedMps == null || !avgSpeedMps.isFinite() || avgSpeedMps <= 0.0) {
                return null
            }
            return if (useMiles) {
                val mph = avgSpeedMps * 2.236936
                String.format(java.util.Locale.US, "%.1f mph", mph)
            } else {
                val kmh = avgSpeedMps * 3.6
                String.format(java.util.Locale.US, "%.1f km/h", kmh)
            }
        }

        /**
         * CIE-039: Formats a unified concluded trip recap with stop reason, elapsed duration,
         * total distance, and average speed with accessible TalkBack announcement text.
         */
        fun formatConcludedSummary(
            username: String?,
            startedAt: String?,
            endedAt: String?,
            stopReason: String?,
            totalDistanceMeters: Double,
            useMiles: Boolean = false,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): LiveViewerConcludedSummary {
            val elapsedSec = computeElapsedSeconds(startedAt, endedAt, nowEpochMillis)
            val durationStr = formatDuration(startedAt, endedAt, nowEpochMillis) ?: "0m"
            val distanceStr = formatDistance(totalDistanceMeters, useMiles)
            val avgSpeedMps = computeAverageSpeed(totalDistanceMeters, elapsedSec ?: 0L)
            val avgSpeedStr = formatAverageSpeed(avgSpeedMps, useMiles)

            val statusText = stopReason?.trim()?.takeIf { it.isNotEmpty() } ?: "Trip completed"
            val detailsText = if (avgSpeedStr != null) {
                "$distanceStr · $durationStr · Ø $avgSpeedStr"
            } else {
                "$distanceStr · $durationStr"
            }
            val rider = username?.trim()?.takeIf { it.isNotEmpty() } ?: "Rider"
            val a11yText = if (avgSpeedStr != null) {
                "$rider's trip ended: $statusText. Covered $distanceStr in $durationStr, average speed $avgSpeedStr."
            } else {
                "$rider's trip ended: $statusText. Covered $distanceStr in $durationStr."
            }

            return LiveViewerConcludedSummary(
                statusText = statusText,
                detailsText = detailsText,
                a11yText = a11yText,
                avgSpeedStr = avgSpeedStr,
                durationStr = durationStr,
                distanceStr = distanceStr,
            )
        }

        /**
         * CIE-040: Computes geodesic distance in meters between viewport camera center and rider coordinate.
         */
        fun computeOffCenterDistanceMeters(
            centerLat: Double,
            centerLng: Double,
            riderLat: Double,
            riderLng: Double,
        ): Double {
            return computeDistanceMeters(centerLat, centerLng, riderLat, riderLng)
        }

        /**
         * CIE-040: Formats off-center distance badge string when distance exceeds threshold (default 250m).
         * Returns null when rider is close to center (< 250m) so button remains clean.
         */
        fun formatOffCenterDistance(
            distanceMeters: Double?,
            useMiles: Boolean = false,
            thresholdMeters: Double = 250.0,
        ): String? {
            if (distanceMeters == null || !distanceMeters.isFinite() || distanceMeters < thresholdMeters) {
                return null
            }
            return if (useMiles) {
                val miles = distanceMeters / 1609.344
                String.format(java.util.Locale.US, "%.1f mi", miles)
            } else {
                val km = distanceMeters / 1000.0
                String.format(java.util.Locale.US, "%.1f km", km)
            }
        }

        /**
         * CIE-040: Formats recenter button label with optional distance badge ("Recenter · 1.4 km").
         */
        fun formatRecenterButtonLabel(
            distanceMeters: Double?,
            useMiles: Boolean = false,
            thresholdMeters: Double = 250.0,
            baseLabel: String = "Recenter",
        ): String {
            val distStr = formatOffCenterDistance(distanceMeters, useMiles, thresholdMeters)
            return if (distStr != null) {
                "$baseLabel · $distStr"
            } else {
                baseLabel
            }
        }

        /**
         * CIE-040: Formats recenter button accessibility label ("Recenter on rider, 1.4 km away").
         */
        fun formatRecenterAccessibilityLabel(
            distanceMeters: Double?,
            useMiles: Boolean = false,
            thresholdMeters: Double = 250.0,
            baseLabel: String = "Recenter on rider",
        ): String {
            val distStr = formatOffCenterDistance(distanceMeters, useMiles, thresholdMeters)
            return if (distStr != null) {
                "$baseLabel, $distStr away"
            } else {
                baseLabel
            }
        }

        /**
         * CIE-040: Evaluates whether follow mode should automatically resume after inactivity timeout (default 20s).
         * Suppresses auto-resume when trip has ended or when user is currently in follow mode.
         */
        fun shouldResumeFollowMode(
            isFollowing: Boolean,
            isEnded: Boolean,
            lastInteractionEpochMs: Long?,
            nowEpochMillis: Long = System.currentTimeMillis(),
            timeoutMs: Long = 20_000L,
        ): Boolean {
            if (isFollowing || isEnded || lastInteractionEpochMs == null) {
                return false
            }
            val elapsed = nowEpochMillis - lastInteractionEpochMs
            return elapsed >= timeoutMs
        }

        // MARK: - CIE-041: Health Telemetry & Latency Diagnostics

        /**
         * CIE-041: Computes poll latency in milliseconds.
         * Clamps negative durations or non-finite values to 0.
         */
        fun computePollLatencyMs(
            startTimeMs: Long,
            endTimeMs: Long = System.currentTimeMillis()
        ): Long {
            val elapsed = endTimeMs - startTimeMs
            return if (elapsed > 0) elapsed else 0L
        }

        /**
         * CIE-041: Evaluates 1% sampling rate gate for viewer health telemetry.
         * Strict $0/month free-tier protection for PostHog event quotas.
         */
        fun shouldSampleViewerHealth(
            sampleRate: Double = 0.01,
            randomProvider: () -> Double = { Math.random() }
        ): Boolean {
            val clampedRate = sampleRate.coerceIn(0.0, 1.0)
            return randomProvider() < clampedRate
        }

        /**
         * CIE-041: Classifies viewer error codes for telemetry and diagnostics.
         */
        fun classifyViewerError(
            statusCode: Int?,
            errorMessage: String?
        ): String {
            if (statusCode == 404 || statusCode == 410) {
                return "not_found_or_expired"
            }
            if (statusCode == 429) {
                return "rate_limited"
            }
            if (statusCode != null && statusCode in 500..599) {
                return "server_error"
            }
            val msg = (errorMessage ?: "").lowercase()
            if (msg.contains("404") || msg.contains("expired") || msg.contains("not found")) {
                return "not_found_or_expired"
            }
            if (msg.contains("429") || msg.contains("rate limit")) {
                return "rate_limited"
            }
            if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("server error")) {
                return "server_error"
            }
            if (msg.contains("json") || msg.contains("syntax") || msg.contains("malformed") || msg.contains("invalid response") || msg.contains("decode") || msg.contains("corrupt")) {
                return "malformed_response"
            }
            if (statusCode == 0 || msg.contains("network") || msg.contains("offline") || msg.contains("internet") || msg.contains("timed out") || msg.contains("connection") || msg.contains("failed to connect") || msg.contains("unknownhost")) {
                return "offline_network"
            }
            return "unknown"
        }

        /**
         * CIE-041: Builds sanitized, privacy-safe health telemetry payload without raw coordinates or PII.
         */
        fun buildViewerHealthTelemetryPayload(
            sessionId: String,
            pollLatencyMs: Long,
            statusCode: Int,
            isActive: Boolean,
            hasLocation: Boolean,
            batteryLevel: Int?,
            pointCount: Int,
            isStale: Boolean = false
        ): Map<String, Any> {
            val cleanId = sessionId.trim()
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(64)
            val clampedLatency = if (pollLatencyMs > 0) pollLatencyMs else 0L
            val clampedBattery = clampBatteryLevel(batteryLevel)
            val clampedPoints = if (pointCount > 0) pointCount else 0

            val payload = mutableMapOf<String, Any>(
                "session_id" to cleanId,
                "sessionId" to cleanId,
                "poll_latency_ms" to clampedLatency,
                "pollLatencyMs" to clampedLatency,
                "status_code" to statusCode,
                "statusCode" to statusCode,
                "is_active" to isActive,
                "isActive" to isActive,
                "has_location" to hasLocation,
                "hasLocation" to hasLocation,
                "point_count" to clampedPoints,
                "pointCount" to clampedPoints,
                "is_stale" to isStale,
                "isStale" to isStale,
                "platform" to "android"
            )
            if (clampedBattery != null) {
                payload["battery_level"] = clampedBattery
                payload["batteryLevel"] = clampedBattery
            }
            return payload
        }

        // MARK: - CIE-042: Reconnection State Memory & Cache Resync

        data class ReconnectionResyncEvaluation(
            val shouldFetchImmediately: Boolean,
            val shouldUseCachedState: Boolean,
            val resetConsecutiveErrors: Boolean,
            val statusMessage: String?
        )

        /**
         * CIE-042: Evaluates whether map viewport, breadcrumb polyline, and rider marker should be preserved
         * during temporary cellular dropouts. Prevents jarring map resets.
         */
        fun shouldPreserveViewerState(
            hasRenderedLocation: Boolean,
            isOffline: Boolean
        ): Boolean {
            return hasRenderedLocation
        }

        /**
         * CIE-042: Evaluates offline-to-online reconnection resynchronization logic.
         */
        fun evaluateReconnectionResync(
            isOnline: Boolean,
            wasOffline: Boolean,
            hasCachedSnapshot: Boolean,
            isTripEnded: Boolean,
            isFetchInProgress: Boolean = false
        ): ReconnectionResyncEvaluation {
            if (isTripEnded) {
                return ReconnectionResyncEvaluation(
                    shouldFetchImmediately = false,
                    shouldUseCachedState = hasCachedSnapshot,
                    resetConsecutiveErrors = false,
                    statusMessage = null
                )
            }

            if (!isOnline) {
                return ReconnectionResyncEvaluation(
                    shouldFetchImmediately = false,
                    shouldUseCachedState = hasCachedSnapshot,
                    resetConsecutiveErrors = false,
                    statusMessage = "Viewer Offline"
                )
            }

            if (isOnline && wasOffline) {
                return ReconnectionResyncEvaluation(
                    shouldFetchImmediately = !isFetchInProgress,
                    shouldUseCachedState = hasCachedSnapshot,
                    resetConsecutiveErrors = true,
                    statusMessage = "Reconnecting..."
                )
            }

            return ReconnectionResyncEvaluation(
                shouldFetchImmediately = false,
                shouldUseCachedState = hasCachedSnapshot,
                resetConsecutiveErrors = false,
                statusMessage = null
            )
        }

        /**
         * CIE-043: Formats localized share text for forwarding a live session.
         */
        fun formatLiveShareMessage(
            username: String?,
            shareUrl: String,
            nameTemplate: String = "Track %1\$s's live ride on TrackMe: %2\$s",
            fallbackTemplate: String = "Track this live ride on TrackMe: %1\$s"
        ): String {
            val cleanName = username?.trim()?.takeIf { it.isNotBlank() }
            return if (cleanName != null) {
                String.format(Locale.getDefault(), nameTemplate, cleanName, shareUrl)
            } else {
                String.format(Locale.getDefault(), fallbackTemplate, shareUrl)
            }
        }

        /**
         * CIE-043: Constructs direct external navigation URL to rider coordinate.
         * Validates latitude (-90..90) and longitude (-180..180).
         */
        fun buildDirectionsUrl(
            lat: Double,
            lng: Double,
            provider: String = "google_maps"
        ): String? {
            if (!lat.isFinite() || !lng.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null

            return when (provider) {
                "apple_maps" -> "https://maps.apple.com/?daddr=$lat,$lng"
                else -> "https://maps.google.com/?daddr=$lat,$lng"
            }
        }

        /**
         * CIE-045: Metrics describing breadcrumb decimation efficiency.
         */
        data class DecimationMetrics(
            val pointsPreDecimation: Int,
            val pointsPostDecimation: Int,
            val pointsSaved: Int,
            val decimationRatio: Double,
            val estimatedBytesSaved: Int
        )

        /**
         * CIE-045: Computes polling delay under Low-Data Mode.
         * When Low-Data Mode is enabled, polling slows to 30,000ms (30s) to conserve cellular data.
         */
        fun computeLowDataPollDelay(isLowDataMode: Boolean, defaultDelayMs: Long = 5_000L): Long {
            return if (isLowDataMode) 30_000L else maxOf(1_000L, defaultDelayMs)
        }

        /**
         * CIE-045: Computes decimation metrics comparing raw points to decimated stored points.
         * Standard JSON format for coordinate pair `[lat, lng]` consumes ~28 bytes.
         */
        fun computeDecimationMetrics(preCount: Int, postCount: Int): DecimationMetrics {
            val safePre = maxOf(0, preCount)
            val safePost = maxOf(0, postCount)
            val pointsSaved = maxOf(0, safePre - safePost)
            val decimationRatio = if (safePre > 0) pointsSaved.toDouble() / safePre.toDouble() else 0.0
            val estimatedBytesSaved = pointsSaved * 28

            return DecimationMetrics(
                pointsPreDecimation = safePre,
                pointsPostDecimation = safePost,
                pointsSaved = pointsSaved,
                decimationRatio = decimationRatio,
                estimatedBytesSaved = estimatedBytesSaved
            )
        }

        /**
         * CIE-045: Builds a sanitized, non-PII telemetry payload for low-data mode and decimation.
         * Strictly adheres to privacy invariants: zero raw coordinates, session tokens, or personal info.
         */
        fun buildLowDataTelemetryPayload(
            isLowDataMode: Boolean,
            preCount: Int = 0,
            postCount: Int = 0
        ): Map<String, Any> {
            val metrics = computeDecimationMetrics(preCount, postCount)
            val roundedRatio = Math.round(metrics.decimationRatio * 1000.0) / 1000.0
            return mapOf(
                "low_data_mode" to isLowDataMode,
                "points_pre_decimation" to metrics.pointsPreDecimation,
                "points_post_decimation" to metrics.pointsPostDecimation,
                "points_saved" to metrics.pointsSaved,
                "decimation_ratio" to roundedRatio,
                "estimated_bytes_saved" to metrics.estimatedBytesSaved
            )
        }

        /**
         * CIE-045: Formats user-facing status notice when low-data mode is active.
         */
        fun formatLowDataNoticeText(isLowDataMode: Boolean): String? {
            if (!isLowDataMode) return null
            return "Low-data mode active · Polling every 30s"
        }
    }
}

