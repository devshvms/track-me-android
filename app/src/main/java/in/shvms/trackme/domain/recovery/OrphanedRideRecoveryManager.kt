package `in`.shvms.trackme.domain.recovery

import android.location.Location
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.utils.RideUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-responsibility manager designed to inspect and recover orphaned ride sessions
 * caused by sudden device power-off, OS force-kills, or battery depletion.
 *
 * Reconstructs complete ride statistics (distance, speed, duration, end timestamp)
 * from persisted SQLite GPS points up to the exact moment the interruption occurred.
 */
object OrphanedRideRecoveryManager {

    /**
     * Inspects the database for uncompleted rides and auto-finalizes any orphaned ride
     * not currently being tracked.
     *
     * @param rideDao The Room DAO for rides and GPS points.
     * @param activeRideId The currently active ride ID in TrackingService (if any).
     * @return Number of orphaned rides recovered or cleaned up.
     */
    suspend fun recoverOrphanedRides(
        rideDao: RideDao,
        activeRideId: Long? = null
    ): Int = withContext(Dispatchers.IO) {
        val uncompletedRides = rideDao.getUncompletedRides()
        var processedCount = 0

        for (ride in uncompletedRides) {
            // Skip if this is the active live tracking session
            if (activeRideId != null && ride.id == activeRideId) {
                continue
            }

            val points = rideDao.getPointsForRideSync(ride.id)

            if (points.isEmpty()) {
                // No GPS data was recorded before the crash/force-stop -> clean up empty entry
                rideDao.deleteRide(ride.id)
                processedCount++
                continue
            }

            // Reconstruct metrics from recorded GPS trajectory
            var totalDistance = 0.0
            var maxSpeed = 0f

            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]

                val results = FloatArray(1)
                Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude,
                    results
                )
                totalDistance += results[0]

                if (curr.speed > maxSpeed) {
                    maxSpeed = curr.speed
                }
            }

            val startTime = ride.startTime
            val endTime = points.last().timestamp
            val durationMillis = (endTime - startTime).coerceAtLeast(0L)
            val avgSpeed = if (durationMillis > 0L) {
                (totalDistance / (durationMillis / 1000f)).toFloat()
            } else {
                0f
            }

            val newTitle = if (ride.title == RideUtils.getDefaultTitle(ride.startTime)) {
                RideUtils.getDefaultTitle(ride.startTime, maxSpeed * 3.6f)
            } else {
                ride.title
            }

            val calculation = PostRideCalculation(
                distance = totalDistance,
                maxSpeed = maxSpeed,
                avgSpeed = avgSpeed,
                pauseDuration = 0L
            )

            val recoveredRide = ride.copy(
                endTime = endTime,
                title = newTitle,
                postRideCalculation = calculation
            )

            rideDao.updateRide(recoveredRide)
            processedCount++
        }

        processedCount
    }
}
