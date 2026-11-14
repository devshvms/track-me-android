package `in`.shvms.trackme.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

data class TrustedWatcher(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val autoNotify: Boolean = true,
    val addedAtMillis: Long = 0L,
)

data class WatcherInboxInvite(
    val sessionId: String,
    val shareUrl: String,
    val riderName: String,
    val startedAt: Instant? = null,
    val expiresAt: Instant? = null,
)

data class ParsedWatcherInviteLink(
    val code: String,
    val inviterName: String?,
)

/**
 * Manages Trusted Watchers pairing (zero-READ_CONTACTS, invite-link & backend-verified),
 * user FCM token registration in `users/{uid}`, and active incoming live-share watcher inbox.
 */
class TrustedWatchersRepository {
    private val _watchers = MutableStateFlow<List<TrustedWatcher>>(emptyList())
    val watchers: StateFlow<List<TrustedWatcher>> = _watchers.asStateFlow()

    private val _inboxInvite = MutableStateFlow<WatcherInboxInvite?>(null)
    val inboxInvite: StateFlow<WatcherInboxInvite?> = _inboxInvite.asStateFlow()

    private var watcherListener: ListenerRegistration? = null
    private var boundScope: CoroutineScope? = null

    fun bindToAuth(authManager: AuthManager, scope: CoroutineScope) {
        boundScope = scope
        authManager.currentUser
            .onEach { user ->
                watcherListener?.remove()
                watcherListener = null
                if (user == null) {
                    _watchers.value = emptyList()
                    _inboxInvite.value = null
                } else {
                    attachWatcherListener(user.uid)
                    scope.launch(Dispatchers.IO) {
                        syncCurrentUserFcmToken(user.uid, user.displayName)
                        refreshInbox()
                    }
                }
            }
            .launchIn(scope)
    }

    private fun attachWatcherListener(uid: String) {
        watcherListener?.remove()
        watcherListener = FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("trusted_watchers")
            .limit(LiveShareManager.MAX_AUTO_WATCHERS.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val list = snapshot.documents.mapNotNull { doc ->
                    val watcherUid = doc.getString("uid")?.takeIf { it.isNotBlank() } ?: doc.id
                    if (watcherUid.isBlank()) return@mapNotNull null
                    val name = doc.getString("displayName")?.takeIf { it.isNotBlank() } ?: "TrackMe Rider"
                    val photoUrl = doc.getString("photoUrl")?.takeIf { it.isNotBlank() }
                    val autoNotify = doc.getBoolean("autoNotify") ?: true
                    val addedAtMillis = when (val raw = doc.get("addedAt")) {
                        is Number -> raw.toLong()
                        is com.google.firebase.Timestamp -> raw.toDate().time
                        else -> 0L
                    }
                    TrustedWatcher(
                        uid = watcherUid,
                        displayName = name,
                        photoUrl = photoUrl,
                        autoNotify = autoNotify,
                        addedAtMillis = addedAtMillis,
                    )
                }.sortedBy { it.addedAtMillis }
                _watchers.value = list
            }
    }

    fun syncFcmToken(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val scope = boundScope
        if (scope != null) {
            scope.launch(Dispatchers.IO) {
                writeUserTokenDoc(user.uid, token, user.displayName)
            }
        }
    }

    private suspend fun syncCurrentUserFcmToken(uid: String, displayName: String?) {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        if (!token.isNullOrBlank()) {
            writeUserTokenDoc(uid, token, displayName)
        }
    }

    private suspend fun writeUserTokenDoc(uid: String, token: String, displayName: String?) {
        runCatching {
            val payload = hashMapOf<String, Any>(
                "fcmToken" to token,
                "updatedAt" to System.currentTimeMillis(),
            )
            if (!displayName.isNullOrBlank()) {
                payload["displayName"] = displayName
            }
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .set(payload, SetOptions.merge())
                .await()
        }
    }

    suspend fun setAutoNotify(watcherUid: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return@withContext Result.failure(Exception("Sign in required"))
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("trusted_watchers")
                .document(watcherUid)
                .update("autoNotify", enabled)
                .await()
            Unit
        }
    }

    suspend fun removeWatcher(watcherUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return@withContext Result.failure(Exception("Sign in required"))
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("trusted_watchers")
                .document(watcherUid)
                .delete()
                .await()
            AnalyticsManager.trackTrustedWatcherRemoved()
            Unit
        }
    }

    suspend fun createWatcherInviteLink(): Result<String> = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return@withContext Result.failure(LiveShareHttpException(HttpURLConnection.HTTP_UNAUTHORIZED))
        runCatching {
            val body = JSONObject().apply {
                put("action", "create_watcher_invite")
                if (!user.displayName.isNullOrBlank()) put("displayName", user.displayName)
            }.toString()
            val result = executeAuthenticatedPost(
                url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT,
                jsonBody = body,
            )
            if (result.first == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(result.second)
                val inviteUrl = json.optString("inviteUrl").trim()
                if (inviteUrl.isNotEmpty()) {
                    inviteUrl
                } else {
                    val code = json.optString("code").trim()
                    if (!validInviteCodeRegex.matches(code)) {
                        throw Exception("Invalid watcher invite generated by server.")
                    }
                    buildInviteLink(code, user.displayName)
                }
            } else {
                val serverMsg = runCatching { JSONObject(result.second).optString("error") }.getOrNull()
                throw Exception(serverMsg?.takeIf { it.isNotBlank() } ?: "Could not create watcher invite (${result.first}).")
            }
        }
    }

    suspend fun acceptWatcherInvite(
        code: String,
        inviterName: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmedCode = code.trim()
        if (!validInviteCodeRegex.matches(trimmedCode)) {
            return@withContext Result.failure(Exception("Valid single-use invite code is required."))
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: return@withContext Result.failure(Exception("Sign in required to accept watcher invite."))
        runCatching {
            val body = JSONObject().apply {
                put("action", "accept_watcher")
                put("code", trimmedCode)
                if (!inviterName.isNullOrBlank()) put("inviterName", inviterName)
                if (!user.displayName.isNullOrBlank()) put("displayName", user.displayName)
            }.toString()

            val result = executeAuthenticatedPost(
                url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT,
                jsonBody = body,
            )
            when (result.first) {
                HttpURLConnection.HTTP_OK -> {
                    AnalyticsManager.trackTrustedWatcherAdded()
                    Unit
                }
                409 -> throw Exception("This rider already has ${LiveShareManager.MAX_AUTO_WATCHERS} trusted watchers.")
                else -> {
                    val serverMsg = runCatching { JSONObject(result.second).optString("error") }.getOrNull()
                    throw Exception(serverMsg?.takeIf { it.isNotBlank() } ?: "Could not accept watcher invite (${result.first}).")
                }
            }
        }
    }

    private suspend fun executeAuthenticatedPost(url: String, jsonBody: String): Pair<Int, String> {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw LiveShareHttpException(HttpURLConnection.HTTP_UNAUTHORIZED)
        var token = user.getIdToken(false).await().token
            ?: throw Exception("Missing Firebase ID token")
        var result = executePost(url = url, token = token, jsonBody = jsonBody)
        if (result.first == HttpURLConnection.HTTP_UNAUTHORIZED) {
            token = user.getIdToken(true).await().token ?: throw LiveShareHttpException(HttpURLConnection.HTTP_UNAUTHORIZED)
            result = executePost(url = url, token = token, jsonBody = jsonBody)
        }
        return result
    }

    suspend fun refreshInbox(): WatcherInboxInvite? = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            _inboxInvite.value = null
            return@withContext null
        }
        val invite = runCatching {
            var token = user.getIdToken(false).await().token ?: return@runCatching null
            var (code, body) = executeGet(
                url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT + "?inbox=1",
                token = token,
            )
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                token = user.getIdToken(true).await().token ?: return@runCatching null
                val retry = executeGet(
                    url = AppConfig.LIVE_SHARE_BASE_URL + AppConfig.LIVE_SHARE_START_ENDPOINT + "?inbox=1",
                    token = token,
                )
                code = retry.first
                body = retry.second
            }
            if (code == HttpURLConnection.HTTP_OK) parseWatcherInboxResponse(body) else null
        }.getOrNull()
        _inboxInvite.value = invite
        invite
    }

    private fun executeGet(url: String, token: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun executePost(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val validInviteCodeRegex = Regex("^[A-Za-z0-9_-]{12,128}$")

        fun buildInviteLink(code: String, inviterName: String?): String {
            val encodedCode = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8.name())
            val cleanedName = inviterName?.trim()?.takeIf { it.isNotEmpty() }
            return if (cleanedName != null) {
                val encodedName = URLEncoder.encode(cleanedName, StandardCharsets.UTF_8.name())
                "${AppConfig.LIVE_SHARE_WATCHER_INVITE_PREFIX}?code=$encodedCode&inviterName=$encodedName"
            } else {
                "${AppConfig.LIVE_SHARE_WATCHER_INVITE_PREFIX}?code=$encodedCode"
            }
        }

        fun parseWatcherInviteUri(uriString: String?): ParsedWatcherInviteLink? {
            if (uriString.isNullOrBlank()) return null
            return runCatching {
                val uri = URI(uriString.trim())
                val scheme = uri.scheme?.lowercase() ?: return@runCatching null
                val host = uri.host?.lowercase() ?: ""
                val path = uri.path ?: ""
                val isHttpsWatcher = (scheme == "https" || scheme == "http") &&
                    (host == "trackme.shvms.in" || host == "www.trackme.shvms.in") &&
                    (path == "/live/watcher" || path == "/live/watcher/")
                val isCustomWatcher = scheme == "trackme" && host == "live" &&
                    (path == "/watcher" || path == "/watcher/")
                if (!isHttpsWatcher && !isCustomWatcher) return@runCatching null

                val query = uri.rawQuery ?: return@runCatching null
                val params = query.split("&").mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null
                    else {
                        val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                        val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                        key to value
                    }
                }.toMap()

                val code = params["code"]?.trim()?.takeIf { validInviteCodeRegex.matches(it) }
                    ?: return@runCatching null
                val inviterName = params["inviterName"]?.trim()?.takeIf { it.isNotEmpty() }
                ParsedWatcherInviteLink(code = code, inviterName = inviterName)
            }.getOrNull()
        }

        private fun parseTimestamp(obj: JSONObject, key: String): Instant? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return when (val raw = obj.opt(key)) {
                is Number -> epochNumberToInstant(raw.toDouble())
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) null
                    else trimmed.toDoubleOrNull()?.let { epochNumberToInstant(it) }
                        ?: runCatching { Instant.parse(trimmed) }.getOrNull()
                }
                else -> null
            }
        }

        private fun epochNumberToInstant(value: Double): Instant? {
            if (!value.isFinite() || value <= 0.0) return null
            val millis = if (value >= 1e12) value.toLong() else (value * 1000.0).toLong()
            return runCatching { Instant.ofEpochMilli(millis) }.getOrNull()
        }

        fun parseWatcherInboxResponse(body: String): WatcherInboxInvite? {
            if (body.isBlank()) return null
            return runCatching {
                val json = JSONObject(body)
                val activeObj = json.optJSONObject("activeInvite")
                    ?: json.optJSONObject("invite")
                    ?: return@runCatching null
                val sessionId = activeObj.optString("sessionId").trim()
                val shareUrl = activeObj.optString("shareUrl").trim()
                if (sessionId.isEmpty() || shareUrl.isEmpty()) return@runCatching null
                val riderName = activeObj.optString("riderName").trim().ifEmpty { "A TrackMe rider" }
                val startedAt = parseTimestamp(activeObj, "startedAt")
                val expiresAt = parseTimestamp(activeObj, "expiresAt")
                WatcherInboxInvite(
                    sessionId = sessionId,
                    shareUrl = shareUrl,
                    riderName = riderName,
                    startedAt = startedAt,
                    expiresAt = expiresAt,
                )
            }.getOrNull()
        }
    }
}
