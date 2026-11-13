package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.config.AppConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val stopOnRideEnd: Boolean = false,
    val activeViewers: Int = 0,
    val isLiveSharePrimary: Boolean = false,
    val notifiedWatchersCount: Int = 0,
    val sharedWatcherNames: List<String> = emptyList(),
)

class LiveShareHttpException(val statusCode: Int) : Exception(
    "Live share request failed with HTTP $statusCode"
)

internal data class ParsedLiveShareStartResponse(
    val sessionId: String,
    val shareLink: String,
    val expiresAt: Instant,
    val notifiedWatchersCount: Int,
)

class LiveShareManager {
    private val _state = MutableStateFlow(LiveShareState())
    val state: StateFlow<LiveShareState> = _state.asStateFlow()

    private suspend fun firebaseIdToken(forceRefresh: Boolean = false): String {
        return FirebaseAuth.getInstance().currentUser
            ?.getIdToken(forceRefresh)
            ?.await()
            ?.token
            ?: throw Exception("You must be signed in to start live sharing.")
    }

    private fun HttpURLConnection.setBearerToken(token: String) {
        setRequestProperty("Authorization", "Bearer $token")
    }

    suspend fun startSession(
        durationMinutes: Int,
        username: String? = null,
        stopOnRideEnd: Boolean = false,
        watcherUids: List<String> = emptyList(),
        isLiveSharePrimary: Boolean = false,
        sharedWatcherNames: List<String> = emptyList(),
    ): Result<LiveShareState> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            status = LiveShareStatus.STARTING,
            isLiveSharePrimary = isLiveSharePrimary,
        )
        try {
            val requestBody = buildStartRequestBody(durationMinutes, username, watcherUids)
            var token = firebaseIdToken(forceRefresh = false)
            var (statusCode, body) = executePost(
                url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT,
                token = token,
                jsonBody = requestBody.toString(),
            )
            if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                token = firebaseIdToken(forceRefresh = true)
                val retry = executePost(
                    url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT,
                    token = token,
                    jsonBody = requestBody.toString(),
                )
                statusCode = retry.first
                body = retry.second
            }

            if (statusCode == HttpURLConnection.HTTP_OK) {
                val parsed = parseStartResponse(body)
                val newState = LiveShareState(
                    status = LiveShareStatus.ACTIVE,
                    sessionId = parsed.sessionId,
                    shareLink = parsed.shareLink,
                    expiresAt = parsed.expiresAt,
                    startedAt = Instant.now(),
                    stopOnRideEnd = stopOnRideEnd,
                    activeViewers = 0,
                    isLiveSharePrimary = isLiveSharePrimary,
                    notifiedWatchersCount = parsed.notifiedWatchersCount,
                    sharedWatcherNames = sharedWatcherNames,
                )
                _state.value = newState

                `in`.shvms.trackme.analytics.AnalyticsManager.trackLiveShareStarted(parsed.sessionId, 0)

                Result.success(newState)
            } else {
                _state.value = LiveShareState(status = LiveShareStatus.IDLE)
                val error = if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    LiveShareHttpException(statusCode)
                } else {
                    Exception("Live sharing service is temporarily unavailable (Error $statusCode). Please try again later.")
                }
                Result.failure(error)
            }
        } catch (e: Exception) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            Result.failure(Exception(formatGracefulError(e)))
        }
    }

    /** Pushes the current location snapshot to `/api/track/[sessionId]/location`. [speed] is in km/h. */
    suspend fun pushLocation(
        lat: Double,
        lon: Double,
        batteryLevel: Int?,
        speed: Float?,
        heading: Float?,
        powerSaveMode: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val currentState = _state.value
        val activeSessionId = currentState.sessionId
        if (currentState.status != LiveShareStatus.ACTIVE || activeSessionId == null) {
            return@withContext Result.failure(Exception("No active session"))
        }

        if (currentState.expiresAt != null && Instant.now().isAfter(currentState.expiresAt)) {
            _state.value = currentState.copy(status = LiveShareStatus.EXPIRED)
            return@withContext Result.failure(Exception("Session expired"))
        }

        try {
            val requestBody = JSONObject().apply {
                put("lat", lat)
                put("lng", lon)
                if (batteryLevel != null) put("batteryLevel", batteryLevel)
                if (speed != null) put("speed", speed)
                if (heading != null) put("heading", heading)
                put("powerSaveMode", powerSaveMode)
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            }.toString()

            val endpoint = String.format(AppConfig.LIVE_SHARE_LOCATION_ENDPOINT_TEMPLATE, activeSessionId)
            val fullUrl = AppConfig.LIVE_SHARE_BASE_URL + endpoint
            var token = firebaseIdToken(forceRefresh = false)
            var (statusCode, body) = executePost(fullUrl, token, requestBody)
            if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                token = firebaseIdToken(forceRefresh = true)
                val retry = executePost(fullUrl, token, requestBody)
                statusCode = retry.first
                body = retry.second
            }

            when (statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val viewers = parseActiveViewers(body, fallback = currentState.activeViewers)
                    val latest = _state.value
                    if (latest.status == LiveShareStatus.ACTIVE &&
                        latest.sessionId == activeSessionId &&
                        latest.activeViewers != viewers
                    ) {
                        _state.value = latest.copy(activeViewers = viewers)
                    }
                    Result.success(Unit)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    _state.value = _state.value.copy(status = LiveShareStatus.EXPIRED)
                    Result.failure(Exception("Session not found or expired (404)"))
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    _state.value = _state.value.copy(status = LiveShareStatus.ERROR)
                    Result.failure(LiveShareHttpException(statusCode))
                }
                else -> Result.failure(Exception("Failed to push location: $statusCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Promotes a Direct Share recording into a standard standalone ride UI state (e.g., if user
     * switches back to full telemetry or keeps recording after stopping live share).
     */
    fun demotePrimaryToRideOnly() {
        val current = _state.value
        if (current.isLiveSharePrimary) {
            _state.value = current.copy(isLiveSharePrimary = false)
        }
    }

    suspend fun stopSession(reason: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState.status != LiveShareStatus.ACTIVE || currentState.sessionId == null) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            return@withContext Result.success(Unit)
        }

        try {
            val token = firebaseIdToken(forceRefresh = false)
            val endpoint = String.format(AppConfig.LIVE_SHARE_STOP_ENDPOINT_TEMPLATE, currentState.sessionId)
            val requestBody = JSONObject().apply {
                if (reason != null) {
                    put("stopReason", reason)
                }
            }.toString()
            val (statusCode, _) = executePost(AppConfig.LIVE_SHARE_BASE_URL + endpoint, token, requestBody)

            val durationSecs = currentState.startedAt?.let {
                java.time.Duration.between(it, Instant.now()).seconds
            } ?: 0L

            `in`.shvms.trackme.analytics.AnalyticsManager.trackLiveShareEnded(currentState.sessionId, durationSecs)

            // Regardless of backend response, we stop it locally
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)

            when (statusCode) {
                HttpURLConnection.HTTP_OK -> Result.success(Unit)
                HttpURLConnection.HTTP_UNAUTHORIZED -> Result.failure(LiveShareHttpException(statusCode))
                else -> Result.failure(Exception("Failed to stop session on server: $statusCode"))
            }
        } catch (e: Exception) {
            _state.value = LiveShareState(status = LiveShareStatus.IDLE)
            Result.failure(Exception(formatGracefulError(e)))
        }
    }

    private fun executePost(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setBearerToken(token)
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonBody)
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_AUTO_WATCHERS = 5

        internal fun buildStartRequestBody(
            durationMinutes: Int,
            username: String?,
            watcherUids: List<String>,
        ): JSONObject {
            val cleanedWatchers = watcherUids
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_AUTO_WATCHERS)
            return JSONObject().apply {
                put("durationMinutes", durationMinutes)
                if (!username.isNullOrBlank()) {
                    put("username", username)
                }
                if (cleanedWatchers.isNotEmpty()) {
                    put("watcherUids", JSONArray(cleanedWatchers))
                }
            }
        }

        internal fun parseStartResponse(body: String): ParsedLiveShareStartResponse {
            val json = JSONObject(body)
            return ParsedLiveShareStartResponse(
                sessionId = json.getString("sessionId"),
                shareLink = json.getString("shareLink"),
                expiresAt = Instant.parse(json.getString("expiresAt")),
                notifiedWatchersCount = json.optInt("notifiedWatchersCount", 0).coerceAtLeast(0),
            )
        }

        internal fun parseActiveViewers(body: String, fallback: Int = 0): Int {
            if (body.isBlank()) return fallback
            return runCatching {
                val json = JSONObject(body)
                if (json.has("activeViewers")) json.optInt("activeViewers", fallback).coerceAtLeast(0)
                else fallback
            }.getOrDefault(fallback)
        }

        /**
         * Calculates an adaptive, jittered exponential backoff delay for location pushes during live sharing (CIE-003).
         * Progression:
         * - 0 failures: [baseIntervalMs] (standard broadcast cadence, e.g. 5,000ms)
         * - 1 failure:  base + 1,000ms + jitter
         * - 2 failures: base + 3,000ms + jitter
         * - 3 failures: base + 6,000ms + jitter
         * - 4+ failures: capped at [maxIntervalMs] (default 15,000ms)
         */
        fun computePushBackoffDelay(
            consecutiveFailures: Int,
            baseIntervalMs: Long = 5_000L,
            maxIntervalMs: Long = 15_000L,
            randomProvider: () -> Double = { Math.random() },
        ): Long {
            if (consecutiveFailures <= 0) return baseIntervalMs
            val stepIncrements = longArrayOf(1_000L, 3_000L, 6_000L, 10_000L)
            val stepIndex = (consecutiveFailures - 1).coerceIn(0, stepIncrements.lastIndex)
            val step = stepIncrements[stepIndex]
            val jitter = (randomProvider() * 500L).toLong()
            return (baseIntervalMs + step + jitter).coerceAtMost(maxIntervalMs)
        }

        fun isAuthenticationError(e: Throwable?): Boolean {
            return e is LiveShareHttpException && e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
        }

        fun formatGracefulError(e: Throwable?): String {
            val msg = e?.message ?: return "Live share service is temporarily unreachable. Please try again."
            return when {
                isAuthenticationError(e) || msg.contains("HTTP 401", ignoreCase = true) || msg.contains("401 Unauthorized", ignoreCase = true) ->
                    "Your sign-in expired. Please sign in again to share your location."
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
