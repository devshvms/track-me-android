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

data class LiveViewerLastLocation(
    val lat: Double,
    val lng: Double,
    val speedMps: Float?,
    val headingDeg: Float?,
    val batteryLevel: Int?,
    val powerSaveMode: Boolean,
    val timestampIso: String?,
)

data class LiveSessionSnapshot(
    val sessionId: String,
    val username: String,
    val status: String,
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
                val destination = json.optString("destination").trim().takeIf { it.isNotEmpty() }
                val deadlineAt = json.optString("deadlineAt").trim().takeIf { it.isNotEmpty() }

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
                        speedMps = if (loc.has("speed") && !loc.isNull("speed")) loc.optDouble("speed").toFloat().takeIf { it.isFinite() } else null,
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
    }
}
