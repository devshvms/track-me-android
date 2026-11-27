package `in`.shvms.trackme.domain.recovery

import android.location.Location
import `in`.shvms.trackme.data.local.RideStatsStore
import `in`.shvms.trackme.data.local.PendingRevealStore
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.domain.stats.GoodRideSummary
import `in`.shvms.trackme.domain.stats.RevealSelector
import `in`.shvms.trackme.domain.stats.previousBestFor
import `in`.shvms.trackme.domain.tracking.DwellDetector
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
     * The facts a recovered ride can be described with.
     *
     * SCOPE_1.8.7 §6.1.1 scenario 1 needs these, not just a count. §4.2 N1 is "every notification
     * carries a fact, not a feeling": *"Your ride was saved"* on its own is the kind of line that
     * could have been written without reading the user's data. *"12.3 km, recording stopped at
     * 14:32"* could not.
     */
    private const val DWELL_ANCHOR_RADIUS_METERS = 75.0
    private const val STATIONARY_SPEED_THRESHOLD_MPS = 1.0f // 3.6 km/h
    private const val TRAIL_DWELL_PRUNE_MIN_MS = 10L * 60 * 1000
    private const val MIN_PRE_DWELL_MOVEMENT_METERS = 100.0

    data class TrailingDwellCutoff(
        val keepEndIndexInclusive: Int,
        val cutoffTimestampMillis: Long,
    )

    data class RecoveredRide(
        val rideId: Long,
        val endTimeMillis: Long,
        val distanceMeters: Double,
        val activeDurationMillis: Long = 0L,
        val qualifiesForStats: Boolean = false,
    )

    /**
     * TASK-385: Identifies a trailing stationary cluster (>= 10 min within 75m and <= 3.6 km/h)
     * following real movement (>= 100m), so recovered rides dropped indoors don't preserve hours
     * of stationary tail. Parity with iOS `RideRecoveryManager.trailingDwellCutoff`.
     */
    internal fun findTrailingDwellCutoff(sortedPoints: List<GPSPointEntity>): TrailingDwellCutoff? {
        if (sortedPoints.size < 2) return null
        val tailAnchor = sortedPoints.last()
        var tailStartIdx = sortedPoints.lastIndex
        while (tailStartIdx > 0) {
            val prev = sortedPoints[tailStartIdx - 1]
            val dist = DwellDetector.computeDistanceMeters(
                tailAnchor.latitude,
                tailAnchor.longitude,
                prev.latitude,
                prev.longitude,
            )
            if (dist <= DWELL_ANCHOR_RADIUS_METERS && prev.speed <= STATIONARY_SPEED_THRESHOLD_MPS) {
                tailStartIdx--
            } else {
                break
            }
        }
        if (tailStartIdx <= 0 || tailStartIdx >= sortedPoints.lastIndex) return null
        val dwellDurationMs = tailAnchor.timestamp - sortedPoints[tailStartIdx].timestamp
        if (dwellDurationMs < TRAIL_DWELL_PRUNE_MIN_MS) return null

        var preDwellDistance = 0.0
        for (i in 1..tailStartIdx) {
            val prev = sortedPoints[i - 1]
            val curr = sortedPoints[i]
            preDwellDistance += DwellDetector.computeDistanceMeters(
                prev.latitude,
                prev.longitude,
                curr.latitude,
                curr.longitude,
            )
        }
        if (preDwellDistance < MIN_PRE_DWELL_MOVEMENT_METERS) return null

        return TrailingDwellCutoff(
            keepEndIndexInclusive = tailStartIdx,
            cutoffTimestampMillis = sortedPoints[tailStartIdx].timestamp,
        )
    }

    data class RecoverySummary(
        val recoveredCount: Int = 0,
        val discardedCount: Int = 0,
        /**
         * Details of what was recovered, newest last. Empty when nothing was — and deliberately
         * carries no title: a ride title can be user-written, and a notification is rendered on a
         * lock screen where whoever is holding the phone may not be its owner.
         */
        val recovered: List<RecoveredRide> = emptyList(),
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
        val recovered = mutableListOf<RecoveredRide>()

        for (ride in uncompletedRides) {
            // Skip if this is the active live tracking session
            if (activeRideId != null && ride.id == activeRideId) {
                continue
            }

            val rawPoints = rideDao.getPointsForRideSync(ride.id).sortedBy { it.timestamp }

            if (rawPoints.isEmpty()) {
                // No GPS data was recorded before the crash/force-stop -> clean up empty entry
                rideDao.deleteRide(ride.id)
                discardedCount++
                continue
            }

            val dwellCutoff = findTrailingDwellCutoff(rawPoints)
            val validCutoff = dwellCutoff?.takeIf { it.cutoffTimestampMillis > ride.startTime }
            val wasDwellPruned = validCutoff != null
            val points = if (validCutoff != null) {
                rideDao.deletePointsAfterTimestamp(ride.id, validCutoff.cutoffTimestampMillis)
                rawPoints.subList(0, validCutoff.keepEndIndexInclusive + 1)
            } else {
                rawPoints
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
            val checkpoint = if (!wasDwellPruned && ride.trackingAlgorithmVersion == 2) ride.postRideCalculation else null
            if (checkpoint != null) {
                totalDistance = checkpoint.distance
                maxSpeed = checkpoint.maxSpeed
            }
            val activeDurationMillis = if (checkpoint != null) ride.dashboardActiveDurationMillis
                else `in`.shvms.trackme.data.local.dashboardActiveDurationFromPoints(points) ?: 0L
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
                    `in`.shvms.trackme.domain.`import`.RideContentHash.of(points),
                )
            }

            rideDao.updateRide(recoveredRide)
            recoveredCount++
            recovered += RecoveredRide(
                rideId = ride.id,
                endTimeMillis = endTime,
                distanceMeters = totalDistance,
                activeDurationMillis = activeDurationMillis,
                qualifiesForStats = recoveredRide.qualifiesForStats,
            )
        }

        RecoverySummary(
            recoveredCount = recoveredCount,
            discardedCount = discardedCount,
            recovered = recovered,
        )
    }

    /**
     * TASK-385: Folds qualifying recovered rides idempotently into [RideStatsStore] and stages
     * the earned reveal / streak transition once per rideId.
     */
    suspend fun creditRecoveredRides(
        summary: RecoverySummary,
        rideStatsStore: RideStatsStore,
        pendingRevealStore: PendingRevealStore,
        rideDao: RideDao? = null,
        onFirstRideOfWeek: (streakWeeks: Int, froze: Boolean) -> Unit = { _, _ -> },
    ) {
        for (ride in summary.recovered) {
            if (!ride.qualifiesForStats) continue
            val transition = rideStatsStore.recordGoodRide(
                GoodRideSummary(
                    rideId = ride.rideId,
                    finishedAtMillis = ride.endTimeMillis,
                    durationMillis = ride.activeDurationMillis,
                    distanceMeters = ride.distanceMeters,
                )
            )
            if (transition.alreadyProcessed) continue
            RevealSelector.select(transition)?.let { reveal ->
                pendingRevealStore.put(reveal)
                rideDao?.setEarnedReveal(
                    rideId = ride.rideId,
                    kind = reveal.kind.name,
                    previousBest = previousBestFor(reveal.kind, transition),
                    milestoneCount = reveal.milestoneRideCount,
                )
            }
            if (transition.isFirstRideOfWeek) {
                onFirstRideOfWeek(transition.streakWeeks, transition.streakFroze)
            }
        }
    }
}
