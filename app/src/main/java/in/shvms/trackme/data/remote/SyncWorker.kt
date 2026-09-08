package `in`.shvms.trackme.data.remote

import android.content.Context
import androidx.work.*
import `in`.shvms.trackme.TrackMeApp
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TrackMeApp ?: return Result.failure()
        return try {
            val outcome = app.firestoreSyncManager.syncPeriodic()
            // SCOPE_1.8.7 §6.1.5 #23. Every attempt is recorded, success or failure — the episode
            // only ends on a success, so a run that is never told about a success never ends.
            `in`.shvms.trackme.service.notifications.SyncFailureNotifier(applicationContext)
                .recordAttempt(
                    succeeded = outcome is SyncResult.Success,
                    unsyncedRideCount = app.database.rideDao().countUnsyncedRides(),
                    strings = `in`.shvms.trackme.ui.localization.getAppStrings(
                        app.preferencesManager.appLanguage.value
                    ),
                    bulletin = app.bulletinStore,
                )
            when (outcome) {
                is SyncResult.Success -> {
                    val time = System.currentTimeMillis()
                    applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        .edit().putLong("last_sync_time", time).apply()
                    Result.success()
                }
                is SyncResult.Error -> Result.retry()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "TrackMePeriodicSyncWorker"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
