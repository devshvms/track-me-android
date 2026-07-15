package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

enum class LiveShareStatus {
    IDLE,
    STARTING,
    ACTIVE,
    EXPIRED,
    ERROR
}

data class LiveShareState(
    val status: LiveShareStatus = LiveShareStatus.IDLE,
    val sessionId: String? = null,
    val shareLink: String? = null,
    val expiresAt: Instant? = null,
    val startedAt: Instant? = null,
    val stopOnRideEnd: Boolean = false
)

class LiveShareManager {
    private val _state = MutableStateFlow(LiveShareState())
    val state: StateFlow<LiveShareState> = _state.asStateFlow()

    suspend fun startSession(durationMinutes: Int, username: String? = null, stopOnRideEnd: Boolean = false): Result<LiveShareState> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(status = LiveShareStatus.STARTING)
        try {
            val url = URL(AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("durationMinutes", durationMinutes)
                if (username != null) {
                    put("username", username)
                }
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == 200) {
                val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseString)
                
                val sessionId = responseJson.getString("sessionId")
                val shareLink = responseJson.getString("shareLink")
                val expiresAtStr = responseJson.getString("expiresAt")
                
                val expiresAt = Instant.parse(expiresAtStr)
                
                val newState = LiveShareState(
                    status = LiveShareStatus.ACTIVE,
                    sessionId = sessionId,
                    shareLink = shareLink,
                    expiresAt = expiresAt,
                    startedAt = Instant.now(),
                    stopOnRideEnd = stopOnRideEnd
                )
                _state.value = newState
                
                `in`.shvms.trackme.analytics.AnalyticsManager.trackLiveShareStarted(sessionId, 0)
                
                Result.success(newState)
            } else {
                _state.value = LiveShareState(status = LiveShareStatus.IDLE)
                Result.failure(Exception("Live sharing service is temporarily unavailable (Error ${conn.responseCode}). Please try again later."))
            }
        } catch (e: Exception) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            Result.failure(Exception(formatGracefulError(e)))
        }
    }

    suspend fun pushLocation(lat: Double, lon: Double, batteryLevel: Int?, speed: Float?, heading: Float?): Result<Unit> = withContext(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState.status != LiveShareStatus.ACTIVE || currentState.sessionId == null) {
            return@withContext Result.failure(Exception("No active session"))
        }

        if (currentState.expiresAt != null && Instant.now().isAfter(currentState.expiresAt)) {
            _state.value = currentState.copy(status = LiveShareStatus.EXPIRED)
            return@withContext Result.failure(Exception("Session expired"))
        }

        try {
            val endpoint = String.format(AppConfig.LIVE_SHARE_LOCATION_ENDPOINT_TEMPLATE, currentState.sessionId)
            val url = URL(AppConfig.LIVE_SHARE_BASE_URL + endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
                if (batteryLevel != null) put("batteryLevel", batteryLevel)
                if (speed != null) put("speed", speed)
                if (heading != null) put("heading", heading)
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            if (conn.responseCode == 200) {
                Result.success(Unit)
            } else if (conn.responseCode == 404) {
                _state.value = currentState.copy(status = LiveShareStatus.EXPIRED)
                Result.failure(Exception("Session not found or expired (404)"))
            } else {
                Result.failure(Exception("Failed to push location: ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopSession(reason: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState.status != LiveShareStatus.ACTIVE || currentState.sessionId == null) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            return@withContext Result.success(Unit)
        }

        try {
            val endpoint = String.format(AppConfig.LIVE_SHARE_STOP_ENDPOINT_TEMPLATE, currentState.sessionId)
            val url = URL(AppConfig.LIVE_SHARE_BASE_URL + endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                if (reason != null) {
                    put("stopReason", reason)
                }
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val durationSecs = currentState.startedAt?.let {
                java.time.Duration.between(it, Instant.now()).seconds
            } ?: 0L
            
            `in`.shvms.trackme.analytics.AnalyticsManager.trackLiveShareEnded(currentState.sessionId, durationSecs)
            
            // Regardless of backend response, we stop it locally
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            
            if (conn.responseCode == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to stop session on server: ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            Result.failure(Exception(formatGracefulError(e)))
        }
    }

    companion object {
        fun formatGracefulError(e: Throwable?): String {
            val msg = e?.message ?: return "Live share service is temporarily unreachable. Please try again."
            return when {
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("No address associated with hostname", ignoreCase = true) ||
                msg.contains("UnknownHostException", ignoreCase = true) ||
                msg.contains("shvms.in", ignoreCase = true) ->
                    "Unable to reach live share server. Please verify your internet connection and try again."
                msg.contains("ConnectException", ignoreCase = true) ||
                msg.contains("SocketTimeoutException", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ->
                    "Connection timed out. Please check your internet connection and try again."
                msg.contains("SSLException", ignoreCase = true) ||
                msg.contains("SSLHandshakeException", ignoreCase = true) ->
                    "Secure connection to live sharing server failed. Please try again."
                msg.contains("Failed to start session", ignoreCase = true) ||
                msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("404") ->
                    "Live sharing service is temporarily unavailable. Please try again later."
                else -> "Unable to connect to live share service. Please check your network connection."
            }
        }
    }
}
