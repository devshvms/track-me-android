package `in`.shvms.trackme.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.TrackMeApp

import com.google.firebase.auth.FirebaseUser
import `in`.shvms.trackme.config.AppConfig

import `in`.shvms.trackme.domain.export.GPXExporterImpl
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL



class SettingsViewModel(private val app: TrackMeApp) : ViewModel() {
    val currentUser = app.authManager.currentUser
    val syncResult = app.firestoreSyncManager.syncResult

    private val prefs = app.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val _lastSyncTime = MutableStateFlow(prefs.getLong("last_sync_time", 0L))
    val lastSyncTime = _lastSyncTime.asStateFlow()

    val syncedRidesCount = app.database.rideDao().getAllRidesWithPoints().map { rides ->
        rides.count { it.ride.isSynced }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    
    val totalRidesCount = combine(
        app.database.rideDao().getAllRidesWithPoints(),
        app.firestoreSyncManager.totalCloudRidesCount
    ) { rides, cloudCount ->
        val unsyncedLocalCount = rides.count { !it.ride.isSynced }
        if (app.authManager.currentUser.value != null && cloudCount > 0) {
            maxOf(rides.size, cloudCount + unsyncedLocalCount)
        } else {
            rides.size
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        _lastSyncTime.value = prefs.getLong("last_sync_time", 0L)
        app.firestoreSyncManager.refreshCloudCount()
        viewModelScope.launch {
            syncResult.collect { result ->
                if (result is `in`.shvms.trackme.data.remote.SyncResult.Success) {
                    val time = System.currentTimeMillis()
                    prefs.edit().putLong("last_sync_time", time).apply()
                    _lastSyncTime.value = time
                    app.firestoreSyncManager.refreshCloudCount()
                }
            }
        }
    }

    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
        val result = app.authManager.signInWithGoogle(context)
        if (result.isSuccess) {
            app.firestoreSyncManager.syncRecent(10)
            app.firestoreSyncManager.refreshCloudCount()
        }
        return result
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                app.database.rideDao().deleteSyncedPoints()
                app.database.rideDao().deleteSyncedRides()
                
                // Clear emergency settings and stop active broadcast
                app.database.emergencyDao().deleteSettings()
                app.database.emergencyDao().deleteAllContacts()
                app.emergencyManager.stopEmergency()
            } catch (e: Exception) {
                // Ignore DB error and ensure we still sign out
            }
            app.authManager.signOut()
        }
    }

    fun syncData() {
        app.firestoreSyncManager.syncAll()
    }

    sealed class ExportRequestResult {
        data class Queued(val status: String, val message: String) : ExportRequestResult()
        data class Completed(val downloadUrl: String) : ExportRequestResult()
    }


    suspend fun requestCompleteDataExport(): Result<ExportRequestResult> {
        val user = currentUser.value ?: return Result.failure(Exception("You must be logged in to request a complete cloud export."))
        val email = user.email ?: return Result.failure(Exception("No verified email address associated with your account."))
        
        `in`.shvms.trackme.analytics.AnalyticsManager.trackDataDownloadRequested()

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(AppConfig.LIVE_SHARE_BASE_URL + "/api/export/request")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val requestBody = JSONObject().apply {
                    put("userId", user.uid)
                    put("userEmail", email)
                    put("clientOS", "Android")
                    val formats = JSONArray()
                    formats.put("GPX")
                    formats.put("JSON_ARCHIVE")
                    put("exportFormats", formats)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                }

                if (conn.responseCode == 200) {
                    val responseString = conn.inputStream.bufferedReader().use { it.readText() }
                    val responseJson = JSONObject(responseString)
                    val status = responseJson.optString("status", "QUEUED")
                    val message = responseJson.optString("message", "Your archive is being prepared. Check back shortly—your download link will appear here once ready.")
                    val downloadUrl = responseJson.optString("downloadUrl")

                    if (status == "COMPLETED" && downloadUrl.isNotEmpty()) {
                        Result.success(ExportRequestResult.Completed(downloadUrl))
                    } else {
                        Result.success(
                            ExportRequestResult.Queued(
                                status = status,
                                message = message
                            )
                        )
                    }
                } else {
                    Result.failure(Exception("Failed to request export. Server returned ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }


    suspend fun exportAllMyData(context: Context): Result<File> {

        return try {
            val exportsDir = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME)
            if (!exportsDir.exists()) exportsDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val zipFile = File(exportsDir, "TrackMe_Archive_$timestamp.zip")

            val ridesWithPoints = app.database.rideDao().getAllRidesWithPointsSync()
            val gpxExporter = GPXExporterImpl()

            val jsonArray = JSONArray()
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (rideWithPoints in ridesWithPoints) {
                        try {
                            val gpxFile = gpxExporter.export(rideWithPoints, context)
                            val entryName = "gpx/Ride_${rideWithPoints.ride.id}.gpx"
                            zos.putNextEntry(ZipEntry(entryName))
                            gpxFile.inputStream().use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        } catch (e: Exception) {
                            // Continue archiving remaining rides
                        }

                        val rideJson = JSONObject().apply {
                            put("id", rideWithPoints.ride.id)
                            put("startTime", rideWithPoints.ride.startTime)
                            put("endTime", rideWithPoints.ride.endTime)
                            put("distanceMeters", rideWithPoints.ride.postRideCalculation?.distance ?: 0.0)

                            put("isSynced", rideWithPoints.ride.isSynced)
                            val pointsArray = JSONArray()
                            for (point in rideWithPoints.points) {
                                val pObj = JSONObject().apply {
                                    put("latitude", point.latitude)
                                    put("longitude", point.longitude)
                                    put("altitude", point.altitude)
                                    put("speed", point.speed)
                                    put("timestamp", point.timestamp)
                                }
                                pointsArray.put(pObj)
                            }
                            put("points", pointsArray)
                        }
                        jsonArray.put(rideJson)
                    }

                    val archiveIndex = JSONObject().apply {
                        put("exportTimestamp", timestamp)
                        put("userEmail", currentUser.value?.email ?: "anonymous")
                        put("totalRides", ridesWithPoints.size)
                        put("rides", jsonArray)
                    }
                    zos.putNextEntry(ZipEntry("trackme_data_archive.json"))
                    zos.write(archiveIndex.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
            Result.success(zipFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCloudData(): Result<Unit> {

        val result = app.firestoreSyncManager.deleteAllCloudData()
        if (result.isSuccess) {
            // Mark all local rides as unsynced
            app.database.rideDao().markAllAsUnsynced()
        }
        return result
    }

    suspend fun deleteAccountAndData(feedbackText: String): Result<Unit> {
        `in`.shvms.trackme.analytics.AnalyticsManager.trackAccountDeletionRequested(feedbackText)
        
        // 1. Submit feedback
        app.firestoreSyncManager.submitFeedback(feedbackText, "account_deletion")
        
        // 2. Delete cloud data
        app.firestoreSyncManager.deleteAllCloudData()
        
        // 3. Delete Firebase Auth Account
        val deleteAuthResult = app.authManager.deleteAccount()
        if (deleteAuthResult.isSuccess) {
            // 4. Wipe local data
            app.database.rideDao().deleteAllPoints()
            app.database.rideDao().deleteAllRides()
            app.database.emergencyDao().deleteSettings()
            app.database.emergencyDao().deleteAllContacts()
            app.emergencyManager.stopEmergency()
            app.authManager.signOut()
            return Result.success(Unit)
        }
        
        return deleteAuthResult
    }
}

class SettingsViewModelFactory(private val app: TrackMeApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
