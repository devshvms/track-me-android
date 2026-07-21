package `in`.shvms.trackme.data.remote

import android.util.Log
import `in`.shvms.trackme.config.AppConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * D3 — transactional email trigger (Android). Fire-and-forget.
 *
 * The client passes ONLY a `type` enum; the Vercel backend owns the subject +
 * brand HTML and derives the recipient from the verified Firebase token
 * (self-only send). A missed transactional email must never block sign-up or
 * account deletion, so all failures are logged and swallowed by callers.
 *
 * Mirrors [LiveShareManager]'s HttpURLConnection + bearer-token pattern rather
 * than pulling in a new HTTP dependency.
 */
object NotificationManager {
    enum class EmailType(val wire: String) {
        WELCOME("welcome"),
        DELETE_ACCOUNT("delete_account")
    }

    /**
     * POST the notify request. Must be called while the user is still
     * authenticated (for [EmailType.DELETE_ACCOUNT], fire this BEFORE
     * `FirebaseUser.delete()` — the token is revoked afterwards).
     */
    suspend fun sendTransactional(type: EmailType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)?.await()?.token
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))

            val url = URL(AppConfig.LIVE_SHARE_BASE_URL + AppConfig.NOTIFY_SEND_ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(conn.outputStream).use {
                it.write(JSONObject().put("type", type.wire).toString())
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                Result.success(Unit)
            } else {
                // Never log the recipient — the server already redacts.
                Log.w("NotificationManager", "notify ${type.wire} failed: HTTP $code")
                Result.failure(Exception("notify failed: HTTP $code"))
            }
        } catch (e: Exception) {
            Log.w("NotificationManager", "notify ${type.wire} failed", e)
            Result.failure(e)
        }
    }
}
