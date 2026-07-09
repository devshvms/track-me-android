package `in`.shvms.trackme.ui.update

import android.content.Context
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val releaseNotes: String,
    val isForceUpdate: Boolean = false,
    val updateUrl: String = "https://play.google.com/store/apps/details?id=in.shvms.trackme"
)

class AppUpdateChecker(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
    private val firestore = FirebaseFirestore.getInstance()

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    private fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkForUpdate(forceCheck: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val currentCode = getCurrentVersionCode()
                val currentName = getCurrentVersionName()

                // 1. Check Firestore remote config doc: config/app_release
                val doc = firestore.collection("config").document("app_release").get().await()
                if (doc.exists()) {
                    val latestCode = doc.getLong("latestVersionCode")?.toInt() ?: currentCode
                    val latestName = doc.getString("latestVersionName") ?: currentName
                    val notes = doc.getString("releaseNotes") ?: "We've added new features and performance improvements!"
                    val force = doc.getBoolean("isForceUpdate") ?: false
                    val url = doc.getString("updateUrl") ?: "https://play.google.com/store/apps/details?id=in.shvms.trackme"

                    if (latestCode > currentCode) {
                        if (forceCheck || force || shouldShowPrompt(latestCode)) {
                            _updateInfo.value = AppUpdateInfo(latestCode, latestName, notes, force, url)
                        }
                        return@withContext
                    }
                }

                // 2. Fallback check: GitHub Releases API
                val githubReleaseUrl = "https://api.github.com/repos/devshvms/track-me-android/releases/latest"
                val conn = (URL(githubReleaseUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val bodyNotes = json.optString("body", "Bug fixes and UI enhancements.")
                    val htmlUrl = json.optString("html_url", "https://github.com/devshvms/track-me-android/releases")

                    if (isNewerVersion(tagName, currentName)) {
                        val syntheticCode = currentCode + 1
                        if (forceCheck || shouldShowPrompt(syntheticCode)) {
                            _updateInfo.value = AppUpdateInfo(
                                latestVersionCode = syntheticCode,
                                latestVersionName = tagName,
                                releaseNotes = bodyNotes,
                                isForceUpdate = false,
                                updateUrl = htmlUrl
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore update check errors when offline
            }
        }
    }

    fun dismissUpdate(versionCode: Int) {
        prefs.edit()
            .putInt("dismissed_version_code", versionCode)
            .putLong("dismissed_timestamp", System.currentTimeMillis())
            .apply()
        _updateInfo.value = null
    }

    private fun shouldShowPrompt(versionCode: Int): Boolean {
        val dismissedVersion = prefs.getInt("dismissed_version_code", -1)
        val dismissedTime = prefs.getLong("dismissed_timestamp", 0L)
        val twentyFourHours = 24 * 60 * 60 * 1000L

        return (dismissedVersion != versionCode) || (System.currentTimeMillis() - dismissedTime > twentyFourHours)
    }

    private fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
