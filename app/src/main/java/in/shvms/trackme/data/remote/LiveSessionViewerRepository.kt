package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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
}

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
    }
}
