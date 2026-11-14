package `in`.shvms.trackme.domain.tracking

import kotlin.math.*

/**
 * TASK-369: Spatial-temporal dwell detector and continuous trip chaining engine.
 *
 * Solves the critical drop-off gap where riders start a ride but forget to tap "End Ride"
 * upon reaching their destination, leaving recordings running for hours indoors.
 *
 * Core Invariants:
 * 1. Dwell Anchor: When speed drops below stationary threshold (< 3.6 km/h / 1.0 m/s)
 *    for >= 45 seconds, a spatial anchor (lat, lng, anchorTimestamp) is dropped.
 * 2. Bounding Geofence (R <= 75m): Indoor pacing between rooms in an apartment/office
 *    and multipath GPS drift remain within 75m of the anchor. As long as displacement <= 75m,
 *    the rider is classified as DWELLED, suppressing false movement.
 * 3. Auto-End: When dwell duration reaches dwellTimeoutMs (e.g. 5m, 10m, 15m),
 *    triggers AUTO_END. Prunes trailing dwell points back to anchorTimestamp so metrics
 *    (duration, average speed, splits) reflect actual transit.
 * 4. Trip Chaining & Breakout: In standby, when distance > 150m and speed indicates
 *    sustained movement (> 8 km/h cycling, > 20 km/h vehicle, > 4.5 km/h run for >= 20s),
 *    triggers AUTO_START for the next trip.
 */
enum class DwellState {
    ACTIVE_TRACKING,
    DWELLING,
    TRIP_AUTO_ENDED,
    STANDBY_WAITING_FOR_NEXT_TRIP,
}

data class DwellEvaluationResult(
    val state: DwellState,
    val shouldAutoEndRide: Boolean = false,
    val autoEndTimestampEpochMs: Long? = null,
    val shouldPruneTrailingPoints: Boolean = false,
    val shouldAutoStartNextRide: Boolean = false,
    val autoStartTimestampEpochMs: Long? = null,
    val dwellAnchorLat: Double? = null,
    val dwellAnchorLng: Double? = null,
    val currentDwellDurationMs: Long = 0L,
    val distanceFromAnchorMeters: Double = 0.0,
)

class DwellDetector(
    var isAutoTripChainingEnabled: Boolean = false,
    var autoEndDwellMinutes: Int = 10,
    var isAutoEndCropDwellEnabled: Boolean = true,
    var stationarySpeedThresholdKmh: Double = 3.6,
    var dwellAnchorRadiusMeters: Double = 75.0,
    var breakoutRadiusMeters: Double = 150.0,
    var breakoutSpeedThresholdKmh: Double = 8.0,
    var stationaryConfirmationDurationMs: Long = 45_000L,
    var breakoutConfirmationDurationMs: Long = 20_000L,
) {
    var state: DwellState = DwellState.ACTIVE_TRACKING
        private set

    // Tracking the start of potential stillness
    private var lowSpeedStartEpochMs: Long? = null

    // Confirmed dwell anchor dropped when low speed sustained >= stationaryConfirmationDurationMs
    private var anchorLat: Double? = null
    private var anchorLng: Double? = null
    private var anchorTimestampEpochMs: Long? = null

    // Standby breakout tracking
    private var breakoutStartEpochMs: Long? = null

    fun reset() {
        state = DwellState.ACTIVE_TRACKING
        lowSpeedStartEpochMs = null
        anchorLat = null
        anchorLng = null
        anchorTimestampEpochMs = null
        breakoutStartEpochMs = null
    }

    fun transitionToStandby(lastAnchorLat: Double?, lastAnchorLng: Double?, nowEpochMs: Long) {
        state = DwellState.STANDBY_WAITING_FOR_NEXT_TRIP
        anchorLat = lastAnchorLat
        anchorLng = lastAnchorLng
        anchorTimestampEpochMs = nowEpochMs
        lowSpeedStartEpochMs = null
        breakoutStartEpochMs = null
    }

    fun evaluate(
        lat: Double,
        lng: Double,
        speedKmh: Double,
        nowEpochMs: Long,
    ): DwellEvaluationResult {
        // If feature is disabled, return normal active tracking
        if (!isAutoTripChainingEnabled) {
            return DwellEvaluationResult(state = DwellState.ACTIVE_TRACKING)
        }

        val dwellTimeoutMs = autoEndDwellMinutes.coerceIn(1, 60) * 60_000L

        when (state) {
            DwellState.ACTIVE_TRACKING -> {
                val isLowSpeed = speedKmh <= stationarySpeedThresholdKmh
                if (isLowSpeed) {
                    val lowStart = lowSpeedStartEpochMs ?: nowEpochMs.also { lowSpeedStartEpochMs = it }
                    val lowDuration = nowEpochMs - lowStart

                    if (lowDuration >= stationaryConfirmationDurationMs) {
                        // Drop anchor
                        state = DwellState.DWELLING
                        anchorLat = lat
                        anchorLng = lng
                        anchorTimestampEpochMs = lowStart
                        return DwellEvaluationResult(
                            state = DwellState.DWELLING,
                            dwellAnchorLat = lat,
                            dwellAnchorLng = lng,
                            currentDwellDurationMs = lowDuration,
                            distanceFromAnchorMeters = 0.0,
                        )
                    }
                } else {
                    lowSpeedStartEpochMs = null
                }
                return DwellEvaluationResult(state = DwellState.ACTIVE_TRACKING)
            }

            DwellState.DWELLING -> {
                val aLat = anchorLat ?: lat
                val aLng = anchorLng ?: lng
                val aTime = anchorTimestampEpochMs ?: nowEpochMs
                val distFromAnchor = computeDistanceMeters(aLat, aLng, lat, lng)

                // If user moves beyond the 75m dwell geofence at transit speed, they broke out before auto-end
                if (distFromAnchor > dwellAnchorRadiusMeters && speedKmh > stationarySpeedThresholdKmh) {
                    // Resume active tracking
                    state = DwellState.ACTIVE_TRACKING
                    lowSpeedStartEpochMs = null
                    anchorLat = null
                    anchorLng = null
                    anchorTimestampEpochMs = null
                    return DwellEvaluationResult(
                        state = DwellState.ACTIVE_TRACKING,
                        distanceFromAnchorMeters = distFromAnchor,
                    )
                }

                // Still within the 75m dwell radius (indoor pacing or multipath GPS drift)
                val dwellDuration = nowEpochMs - aTime
                if (dwellDuration >= dwellTimeoutMs) {
                    // Auto-end triggered!
                    state = DwellState.TRIP_AUTO_ENDED
                    return DwellEvaluationResult(
                        state = DwellState.TRIP_AUTO_ENDED,
                        shouldAutoEndRide = true,
                        autoEndTimestampEpochMs = aTime,
                        shouldPruneTrailingPoints = isAutoEndCropDwellEnabled,
                        dwellAnchorLat = aLat,
                        dwellAnchorLng = aLng,
                        currentDwellDurationMs = dwellDuration,
                        distanceFromAnchorMeters = distFromAnchor,
                    )
                }

                return DwellEvaluationResult(
                    state = DwellState.DWELLING,
                    dwellAnchorLat = aLat,
                    dwellAnchorLng = aLng,
                    currentDwellDurationMs = dwellDuration,
                    distanceFromAnchorMeters = distFromAnchor,
                )
            }

            DwellState.TRIP_AUTO_ENDED -> {
                return DwellEvaluationResult(
                    state = DwellState.TRIP_AUTO_ENDED,
                    autoEndTimestampEpochMs = anchorTimestampEpochMs,
                )
            }

            DwellState.STANDBY_WAITING_FOR_NEXT_TRIP -> {
                val aLat = anchorLat
                val aLng = anchorLng

                val dist = if (aLat != null && aLng != null) {
                    computeDistanceMeters(aLat, aLng, lat, lng)
                } else {
                    breakoutRadiusMeters + 1.0
                }

                val isMovingAway = dist > breakoutRadiusMeters && speedKmh >= breakoutSpeedThresholdKmh
                if (isMovingAway) {
                    val bStart = breakoutStartEpochMs ?: nowEpochMs.also { breakoutStartEpochMs = it }
                    val bDuration = nowEpochMs - bStart

                    if (bDuration >= breakoutConfirmationDurationMs) {
                        // Confirmed new trip!
                        state = DwellState.ACTIVE_TRACKING
                        lowSpeedStartEpochMs = null
                        anchorLat = null
                        anchorLng = null
                        anchorTimestampEpochMs = null
                        breakoutStartEpochMs = null
                        return DwellEvaluationResult(
                            state = DwellState.ACTIVE_TRACKING,
                            shouldAutoStartNextRide = true,
                            autoStartTimestampEpochMs = bStart,
                            distanceFromAnchorMeters = dist,
                        )
                    }
                } else {
                    breakoutStartEpochMs = null
                }

                return DwellEvaluationResult(
                    state = DwellState.STANDBY_WAITING_FOR_NEXT_TRIP,
                    dwellAnchorLat = aLat,
                    dwellAnchorLng = aLng,
                    distanceFromAnchorMeters = dist,
                )
            }
        }
    }

    companion object {
        fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) return 0.0
            if (lat1 !in -90.0..90.0 || lat2 !in -90.0..90.0) return 0.0
            if (lon1 !in -180.0..180.0 || lon2 !in -180.0..180.0) return 0.0
            if (lat1 == lat2 && lon1 == lon2) return 0.0

            val r = 6_371_000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
            val c = 2.0 * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
            return r * c
        }
    }
}
