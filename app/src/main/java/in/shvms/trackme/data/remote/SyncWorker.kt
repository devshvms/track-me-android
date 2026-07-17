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
            when (app.firestoreSyncManager.syncPeriodic()) {
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
