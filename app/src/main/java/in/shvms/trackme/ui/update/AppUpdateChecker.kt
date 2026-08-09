package `in`.shvms.trackme.ui.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.google.android.play.core.appupdate.AppUpdateInfo as PlayAppUpdateInfo

/**
 * What the update dialog renders.
 *
 * Deliberately carries no version *name*. Play reports only a version code for the available
 * update, and the obvious substitute — the newest GitHub release tag — is wrong precisely when it
 * matters: with alpha running ahead of open testing, the tag names a build the user is not being
 * offered. The version now reaches the user through the release notes themselves, which are
 * written per release and travel with the copy they describe.
 */
data class AppUpdatePrompt(
    val latestVersionCode: Int,
    val releaseNotes: String,
    val isImmediate: Boolean = false,
    val updateUrl: String = PLAY_STORE_URL,
)

const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=in.shvms.trackme"

/**
 * Asks **Google Play** whether an update exists — not GitHub, not a remote config document.
 *
 * This distinction is the whole point. Play answers for *this user, on their track, for their
 * account*: someone on open testing is told about open-testing builds and nothing else. The
 * previous implementation compared against the newest GitHub release tag, which exists the moment
 * CI runs a build — so a tester on 1.6.5 was offered a 1.6.6 that only lived on the alpha track,
 * and tapping through to the store showed them nothing to install. Track-awareness is not
 * something this class maintains; it is a property of the API it calls.
 *
 * Release notes are fetched separately from GitHub and are **display-only**. If that fetch fails
 * or returns notes for a different build, the user sees slightly stale copy — never a phantom
 * prompt.
 */
class AppUpdateChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
    private val manager = AppUpdateManagerFactory.create(context)

    private val _prompt = MutableStateFlow<AppUpdatePrompt?>(null)
    val prompt: StateFlow<AppUpdatePrompt?> = _prompt.asStateFlow()

    /** Emits once a flexible update has finished downloading and only needs a restart. */
    private val _readyToInstall = MutableStateFlow(false)
    val readyToInstall: StateFlow<Boolean> = _readyToInstall.asStateFlow()

    /** Play's handle for the pending update. Required to start the flow; not part of the UI state. */
    private var pending: PlayAppUpdateInfo? = null

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) _readyToInstall.value = true
    }

    init {
        // Never unregistered, deliberately. This object is created once by TrackMeApp and lives as
        // long as the process, so an unregister method would only ever be called at a point where
        // the listener is about to be collected anyway — dead API that implies a lifecycle this
        // class does not have.
        manager.registerListener(installListener)
    }

    /**
     * @return true when Play has an update this user can actually install.
     *
     * Returns false — quietly — on sideloaded or non-Play builds, where Play throws
     * `ERROR_APP_NOT_OWNED` / `ERROR_PLAY_STORE_NOT_FOUND`. A debug build simply never prompts,
     * which is correct rather than a failure worth surfacing.
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): Boolean {
        return try {
            val info = manager.appUpdateInfo.await()
            pending = info

            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                // A flexible update already downloaded in a previous session still needs a restart.
                if (info.installStatus() == InstallStatus.DOWNLOADED) _readyToInstall.value = true
                return false
            }

            val versionCode = info.availableVersionCode()

            // Replaces the old hand-maintained `isForceUpdate` flag. Both signals come from the
            // Play Console release itself, so there is no config document to keep in sync.
            val immediate = (info.updatePriority() >= HIGH_PRIORITY) ||
                ((info.clientVersionStalenessDays() ?: 0) >= STALE_DAYS)

            if (forceCheck || immediate || shouldShowPrompt(versionCode)) {
                _prompt.value = AppUpdatePrompt(
                    latestVersionCode = versionCode,
                    releaseNotes = fetchReleaseNotes(),
                    isImmediate = immediate,
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hands off to Play's own update flow, so the user never leaves the app.
     *
     * @return false if the flow could not be started, in which case the caller should fall back to
     *   opening the store listing.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val info = pending ?: return false
        val type = if (_prompt.value?.isImmediate == true) {
            AppUpdateType.IMMEDIATE
        } else {
            AppUpdateType.FLEXIBLE
        }
        return try {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(type).build(),
            )
        } catch (e: Exception) {
            false
        }
    }

    /** Restarts the app into the downloaded flexible update. */
    fun completeUpdate() {
        _readyToInstall.value = false
        manager.completeUpdate()
    }

    /**
     * Hides the restart prompt without discarding the download — Play keeps it, and the next
     * [checkForUpdate] finds it still staged and offers the restart again.
     */
    fun dismissInstallPrompt() {
        _readyToInstall.value = false
    }

    fun dismissUpdate(versionCode: Int) {
        prefs.edit()
            .putInt(KEY_DISMISSED_VERSION, versionCode)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            .apply()
        _prompt.value = null
    }

    /**
     * Suppresses a version the user dismissed, for 24 hours.
     *
     * This now keys off Play's real `availableVersionCode`. It previously used `currentCode + 1`,
     * a synthetic number that differed between installs, so "Later" was recorded against a value
     * that rarely matched the next check and the suppression barely held.
     */
    private fun shouldShowPrompt(versionCode: Int): Boolean {
        val dismissedVersion = prefs.getInt(KEY_DISMISSED_VERSION, -1)
        val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0L)
        return dismissedVersion != versionCode ||
            System.currentTimeMillis() - dismissedAt > DISMISS_WINDOW_MS
    }

    /**
     * Best-effort notes for display only. Failure yields generic copy, because missing notes must
     * never suppress an update Play has already confirmed.
     */
    private suspend fun fetchReleaseNotes(): String = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(GITHUB_LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) return@withContext FALLBACK_NOTES
            JSONObject(conn.inputStream.bufferedReader().readText())
                .optString("body", "")
                .trim()
                .ifEmpty { FALLBACK_NOTES }
        } catch (e: Exception) {
            FALLBACK_NOTES
        }
    }

    private companion object {
        const val HIGH_PRIORITY = 4
        const val STALE_DAYS = 30
        const val DISMISS_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
        const val KEY_DISMISSED_AT = "dismissed_timestamp"
        const val GITHUB_LATEST_RELEASE =
            "https://api.github.com/repos/devshvms/track-me-android/releases/latest"
        const val FALLBACK_NOTES = "Bug fixes and performance improvements."
    }
}
