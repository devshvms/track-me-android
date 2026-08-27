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

    data class RecoverySummary(
        val recoveredCount: Int = 0,
        val discardedCount: Int = 0
    ) {
        val hasChanges: Boolean
            get() = recoveredCount > 0 || discardedCount > 0
    }

    /**
     * Inspects the database for uncompleted rides and auto-finalizes any orphaned ride
     * not currently being tracked.
     *
     * @param rideDao The Room DAO for rides and GPS points.
     * @param activeRideId The currently active ride ID in TrackingService (if any).
     * @return Counts of rides recovered and empty records discarded.
     */
    suspend fun recoverOrphanedRides(
        rideDao: RideDao,
        activeRideId: Long? = null
    ): RecoverySummary = withContext(Dispatchers.IO) {
        val uncompletedRides = rideDao.getUncompletedRides()
        var recoveredCount = 0
        var discardedCount = 0

        for (ride in uncompletedRides) {
            // Skip if this is the active live tracking session
            if (activeRideId != null && ride.id == activeRideId) {
                continue
            }

            val points = rideDao.getPointsForRideSync(ride.id)

            if (points.isEmpty()) {
                // No GPS data was recorded before the crash/force-stop -> clean up empty entry
                rideDao.deleteRide(ride.id)
                discardedCount++
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
            val activeDurationMillis =
                `in`.shvms.trackme.data.local.dashboardActiveDurationFromPoints(points) ?: 0L
            val avgSpeed = if (activeDurationMillis > 0L) {
                (totalDistance / (activeDurationMillis / 1000f)).toFloat()
            } else {
                0f
            }

            val persona = RideUtils.personaFromStoredName(ride.persona)
            val newTitle = if (RideUtils.isGeneratedTitle(ride.title, ride.startTime, persona)) {
                RideUtils.getDefaultTitle(ride.startTime, persona, maxSpeed * 3.6f)
            } else {
                ride.title
            }

            val calculation = PostRideCalculation(
                distance = totalDistance,
                maxSpeed = maxSpeed,
                avgSpeed = avgSpeed,
                pauseDuration = (durationMillis - activeDurationMillis).coerceAtLeast(0L),
                rawPointCount = points.size,
                elevationGainMeters = `in`.shvms.trackme.domain.processor.calculateElevationGainMeters(points),
            )

            val recoveredRide = ride.copy(
                endTime = endTime,
                title = newTitle,
                postRideCalculation = calculation
            ).let {
                `in`.shvms.trackme.data.local.withDashboardMetadata(
                    it,
                    activeDurationMillis,
                    points.size,
                    // TASK-246: a recovered ride has points, so it has a shape to draw.
                    `in`.shvms.trackme.data.local.dashboardRoutePolylineFromPoints(points),
                )
            }

            rideDao.updateRide(recoveredRide)
            recoveredCount++
        }

        RecoverySummary(
            recoveredCount = recoveredCount,
            discardedCount = discardedCount
        )
    }
}
