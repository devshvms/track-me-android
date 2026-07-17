package `in`.shvms.trackme.utils

import android.content.Context

/** Checks the same internal volume used by Room before a ride writes another point. */
object StorageHealthMonitor {
    const val LOW_STORAGE_THRESHOLD_BYTES = 50L * 1024L * 1024L

    fun isLowStorage(context: Context): Boolean =
        context.filesDir.usableSpace < LOW_STORAGE_THRESHOLD_BYTES

    internal fun isLowStorage(availableBytes: Long, thresholdBytes: Long = LOW_STORAGE_THRESHOLD_BYTES): Boolean =
        availableBytes < thresholdBytes
}
