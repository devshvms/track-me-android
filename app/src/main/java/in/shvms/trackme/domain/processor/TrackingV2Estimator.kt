package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Debug-only TASK-274 movement classification. V1 remains the production authority. */
enum class TrackingV2MovementState {
    MOVING,
    POSSIBLY_MOVING,
    STATIONARY,
    GPS_DEGRADED,
    UNKNOWN,
}

enum class TrackingV2PowerMode {
    NORMAL,
    BATTERY_SAVER,
    FOREGROUND_ONLY,
    GPS_DISABLED_WHEN_SCREEN_OFF,
    ALL_LOCATION_DISABLED,
    UNKNOWN,
}

data class TrackingV2Point(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Raw evidence consumed by V2. Nullable speed is intentional: missing speed is not zero speed.
 * [elapsedRealtimeMillis] must be monotonic and from the same boot.
 */
data class TrackingV2Sample(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float,
    val elapsedRealtimeMillis: Long,
    val gpsSpeedMetersPerSecond: Float?,
    val gpsSpeedAccuracyMetersPerSecond: Float?,
    val motionEnergyMetersPerSecondSquared: Float?,
    val motionSampleAgeMillis: Long?,
    val cumulativeStepCount: Long?,
    val stepAgeMillis: Long?,
    val stepCadenceHz: Float?,
    val persona: RidePersona,
    val powerMode: TrackingV2PowerMode,
)

data class TrackingV2Snapshot(
    val distanceMeters: Double = 0.0,
    val currentSpeedMetersPerSecond: Float = 0f,
    val movementState: TrackingV2MovementState = TrackingV2MovementState.UNKNOWN,
    val routeSegments: List<List<TrackingV2Point>> = emptyList(),
    val sampleCount: Int = 0,
    val missingSpeedCount: Int = 0,
    val degradedSampleCount: Int = 0,
    val rejectedOutlierCount: Int = 0,
    /** Calibrated step-only estimate. Kept beside the new named diagnostics for UI compatibility. */
    val stepDistanceMeters: Double = 0.0,
    /** GPS-only estimate from admitted coherent coordinate windows, independent of step evidence. */
    val coordinateDistanceMeters: Double = 0.0,
    /** Fixed persona-default stride multiplied by accepted detector steps. */
    val rawStepDistanceMeters: Double = 0.0,
    /** Same accepted detector steps recomputed using the current robust GPS-calibrated stride. */
    val calibratedStepDistanceMeters: Double = 0.0,
    val detectedStepCount: Long = 0L,
    val discardedImplausibleStepCount: Long = 0L,
    val strideLengthMeters: Float = 0.72f,
    val calibrationAttemptCount: Int = 0,
    val calibrationAcceptedCount: Int = 0,
    val calibrationRejectedCount: Int = 0,
    val calibrationCandidateMinMeters: Float? = null,
    val calibrationCandidateMedianMeters: Float? = null,
    val calibrationCandidateMaxMeters: Float? = null,
    val pedometerAvailable: Boolean = false,
    val powerMode: TrackingV2PowerMode = TrackingV2PowerMode.UNKNOWN,
    val isPostProcessed: Boolean = false,
)

/**
 * Pure, process-local tracking experiment. It deliberately has no Room, Android or network import.
 *
 * Distance and route point density are separate: every admitted filtered movement contributes to
 * distance, while the map only receives a point after enough movement or a real turn. This avoids
 * the V1 defect where advancing the anchor after rejecting a small segment forgets that movement.
 */
class TrackingV2Estimator {
    private val window = ArrayDeque<TrackingV2Sample>()
    private val routeSegments = mutableListOf<MutableList<TrackingV2Point>>()

    /** GPS-confirmed hybrid distance. Unconfirmed recent steps are projected on top at publish. */
    private var hybridCommittedDistanceMeters = 0.0
    private var hybridBridgeStepCount = 0L
    private var coordinateDistanceMeters = 0.0
    private var detectedStepCount = 0L
    private var discardedImplausibleStepCount = 0L
    private var sampleCount = 0
    private var missingSpeedCount = 0
    private var degradedSampleCount = 0
    private var rejectedOutlierCount = 0

    private var lastSample: TrackingV2Sample? = null
    private var lastStepCount: Long? = null
    private var lastCoordinatePoint: TrackingV2Point? = null
    private var lastCoordinateTimeMillis: Long? = null
    private var lastRoutePoint: TrackingV2Point? = null
    private var lastRouteTimeMillis: Long? = null
    private var pendingRouteDistanceMeters = 0.0
    private var stationaryCandidateSinceMillis: Long? = null
    private var strideLengthMeters = DEFAULT_WALK_STRIDE_METERS
    private var calibrationStepCount: Long? = null
    private var calibrationGpsDistanceMeters: Double? = null
    private var calibrationAccuracyMeters: Float? = null
    private val calibrationCandidates = ArrayDeque<Float>()
    private var calibrationAttemptCount = 0
    private var calibrationAcceptedCount = 0
    private var calibrationRejectedCount = 0
    private var lastSnapshot = TrackingV2Snapshot()

    fun reset(persona: RidePersona = RidePersona.AUTO) {
        window.clear()
        routeSegments.clear()
        hybridCommittedDistanceMeters = 0.0
        hybridBridgeStepCount = 0L
        coordinateDistanceMeters = 0.0
        detectedStepCount = 0L
        discardedImplausibleStepCount = 0L
        sampleCount = 0
        missingSpeedCount = 0
        degradedSampleCount = 0
        rejectedOutlierCount = 0
        lastSample = null
        lastStepCount = null
        lastCoordinatePoint = null
        lastCoordinateTimeMillis = null
        lastRoutePoint = null
        lastRouteTimeMillis = null
        pendingRouteDistanceMeters = 0.0
        stationaryCandidateSinceMillis = null
        strideLengthMeters = defaultStride(persona)
        calibrationStepCount = null
        calibrationGpsDistanceMeters = null
        calibrationAccuracyMeters = null
        calibrationCandidates.clear()
        calibrationAttemptCount = 0
        calibrationAcceptedCount = 0
        calibrationRejectedCount = 0
        lastSnapshot = TrackingV2Snapshot(strideLengthMeters = strideLengthMeters)
    }

    /** Manual pause/resume and an unobserved long gap must start a new route segment. */
    fun markDiscontinuity() {
        freezeOpenStepBridge()
        window.clear()
        lastSample = null
        lastCoordinatePoint = null
        lastCoordinateTimeMillis = null
        lastRoutePoint = null
        lastRouteTimeMillis = null
        pendingRouteDistanceMeters = 0.0
        stationaryCandidateSinceMillis = null
        calibrationStepCount = null
        calibrationGpsDistanceMeters = null
        calibrationAccuracyMeters = null
    }

    fun add(sample: TrackingV2Sample): TrackingV2Snapshot {
        val previous = lastSample
        if (previous != null && sample.elapsedRealtimeMillis <= previous.elapsedRealtimeMillis) {
            rejectedOutlierCount++
            return publish(sample, TrackingV2MovementState.GPS_DEGRADED, 0f)
        }

        sampleCount++
        if (sample.gpsSpeedMetersPerSecond == null) missingSpeedCount++
        val degraded = isDegraded(sample)
        if (degraded) degradedSampleCount++

        if (previous == null) {
            window.addLast(sample)
            lastSample = sample
            lastStepCount = sample.cumulativeStepCount
            calibrationStepCount = sample.cumulativeStepCount
            calibrationGpsDistanceMeters = coordinateDistanceMeters
            calibrationAccuracyMeters = sample.horizontalAccuracyMeters
            return publish(
                sample,
                if (degraded) TrackingV2MovementState.GPS_DEGRADED else TrackingV2MovementState.UNKNOWN,
                sample.gpsSpeedMetersPerSecond ?: 0f,
            )
        }

        val deltaMillis = sample.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        val maxGapMillis = MAX_OBSERVED_GAP_MILLIS
        if (deltaMillis > maxGapMillis) {
            markDiscontinuity()
            window.addLast(sample)
            lastSample = sample
            lastStepCount = sample.cumulativeStepCount
            calibrationStepCount = sample.cumulativeStepCount
            calibrationGpsDistanceMeters = coordinateDistanceMeters
            calibrationAccuracyMeters = sample.horizontalAccuracyMeters
            return publish(sample, TrackingV2MovementState.GPS_DEGRADED, sample.gpsSpeedMetersPerSecond ?: 0f)
        }

        window.addLast(sample)
        pruneWindow(sample)
        val evidence = movementEvidence(sample)
        val stepDelta = stepDelta(previous, sample)
        val movementState = classify(sample, evidence, stepDelta)
        val speed = fusedSpeed(sample, evidence, stepDelta)

        if (movementState == TrackingV2MovementState.MOVING) {
            val smoothedPoint = smoothCurrentPoint(sample, evidence.turnDetected)
            val pedestrian = isPedestrian(sample.persona) ||
                (sample.persona == RidePersona.AUTO && (stepDelta > 0L || evidence.stepsRecent))
            val coordinateReady = window.size >= minimumCoordinateWindowSize(sample.powerMode) &&
                (evidence.coherentDisplacement || evidence.gpsSaysMoving)
            val admittedCoordinateMeters = if (coordinateReady) {
                admitCoordinateDistance(
                    distancePointFor(sample, smoothedPoint),
                    sample,
                    evidence.turnDetected,
                )
            } else {
                0.0
            }

            if (pedestrian && sample.cumulativeStepCount != null) {
                if (stepDelta > 0L) {
                    hybridBridgeStepCount += stepDelta
                }
                if (admittedCoordinateMeters > 0.0) {
                    // GPS confirms the whole interval since its previous anchor. Replace the open
                    // step bridge instead of adding both estimates and double-counting the walk.
                    hybridCommittedDistanceMeters += admittedCoordinateMeters
                    hybridBridgeStepCount = 0L
                }
                calibrateStride(sample)
                appendRoutePoint(smoothedPoint, sample, evidence.turnDetected)
            } else if (admittedCoordinateMeters > 0.0) {
                // Motion can prove that the phone is moving, but it cannot prove how far a noisy
                // GPS cloud travelled. Coordinate distance waits for either a coherent window or
                // reliable Doppler speed; this is the boundary that keeps degraded fixes from
                // becoming random kilometres.
                hybridCommittedDistanceMeters += admittedCoordinateMeters
                appendRoutePoint(smoothedPoint, sample, evidence.turnDetected)
            }
        } else if (movementState == TrackingV2MovementState.STATIONARY) {
            // Once stillness has survived the persona/power-mode dwell, make the latest robust
            // position the next distance anchor. This prevents a cloud of stationary GPS fixes
            // from being charged when movement resumes. The route anchor deliberately stays at
            // the last moving point so auto-pauses do not create artificial dotted gaps.
            freezeOpenStepBridge()
            lastCoordinatePoint = smoothCurrentPoint(sample, turnDetected = false)
            lastCoordinateTimeMillis = sample.elapsedRealtimeMillis
        }

        lastSample = sample
        lastStepCount = sample.cumulativeStepCount ?: lastStepCount
        return publish(sample, movementState, speed)
    }

    /** Route post-processing changes geometry density only; canonical V2 stats stay unchanged. */
    fun finish(): TrackingV2Snapshot {
        val epsilonMeters = if (lastSnapshot.powerMode == TrackingV2PowerMode.NORMAL) 1.5 else 3.0
        val compressed = routeSegments.mapNotNull { segment ->
            val copy = segment.toList()
            when {
                copy.isEmpty() -> null
                copy.size <= 2 -> copy
                else -> simplify(copy, epsilonMeters)
            }
        }
        lastSnapshot = lastSnapshot.copy(routeSegments = compressed, isPostProcessed = true)
        return lastSnapshot
    }

    fun snapshot(): TrackingV2Snapshot = lastSnapshot

    private data class Evidence(
        val coordinateSpeedMetersPerSecond: Float,
        val coherentDisplacement: Boolean,
        val reliableGpsSpeed: Boolean,
        val gpsSaysMoving: Boolean,
        val motionFresh: Boolean,
        val motionSaysMoving: Boolean,
        val stepsRecent: Boolean,
        val turnDetected: Boolean,
    )

    private fun movementEvidence(sample: TrackingV2Sample): Evidence {
        val first = window.first()
        val elapsedSeconds = ((sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis) / 1000.0)
            .coerceAtLeast(0.001)
        val coordinateDistance = haversineMeters(first.point(), sample.point())
        val windowPath = window.toList().zipWithNext().sumOf { (a, b) ->
            haversineMeters(a.point(), b.point())
        }
        val pathStraightness = if (windowPath <= 0.001) 0f else {
            (coordinateDistance / windowPath).toFloat().coerceIn(0f, 1f)
        }
        val combinedAccuracy = hypot(
            first.horizontalAccuracyMeters.coerceAtLeast(1f),
            sample.horizontalAccuracyMeters.coerceAtLeast(1f),
        )
        val uncertaintyScale = if (sample.powerMode == TrackingV2PowerMode.NORMAL) 0.72f else 0.95f
        val significantDistance = max(MIN_COHERENT_DISPLACEMENT_METERS, combinedAccuracy * uncertaintyScale)
        val coordinateSpeed = (coordinateDistance / elapsedSeconds).toFloat()
        // Net displacement alone is not movement: stationary multipath commonly alternates
        // 10–20 m left/right. A real path progresses through its window; the alternating cloud
        // has high travelled chord length but low end-to-end straightness.
        val coordinateEvidenceMature = window.size >= MIN_COORDINATE_EVIDENCE_SAMPLES &&
            sample.elapsedRealtimeMillis - first.elapsedRealtimeMillis >= MIN_COORDINATE_EVIDENCE_MILLIS
        val coherent = coordinateEvidenceMature && coordinateDistance >= significantDistance &&
            pathStraightness >= MIN_PATH_STRAIGHTNESS

        val speed = sample.gpsSpeedMetersPerSecond
        val speedAccuracy = sample.gpsSpeedAccuracyMetersPerSecond
        val reliableGpsSpeed = speed != null && speed.isFinite() && speed >= 0f && when {
            speedAccuracy != null -> speedAccuracy <= max(0.8f, speed * 0.6f)
            else -> sample.horizontalAccuracyMeters <= 15f
        }
        val threshold = movementSpeedThreshold(sample.persona)
        val gpsSaysMoving = reliableGpsSpeed && speed >= threshold
        val freshnessLimit = if (sample.powerMode == TrackingV2PowerMode.NORMAL) 1_500L else 3_000L
        val motionFresh = sample.motionSampleAgeMillis?.let { it in 0..freshnessLimit } == true
        val motionSaysMoving = motionFresh &&
            (sample.motionEnergyMetersPerSecondSquared ?: 0f) >= MOTION_MOVING_ENERGY
        val stepsRecent = sample.stepAgeMillis?.let { it in 0..STEP_RECENCY_MILLIS } == true

        return Evidence(
            coordinateSpeedMetersPerSecond = coordinateSpeed,
            coherentDisplacement = coherent,
            reliableGpsSpeed = reliableGpsSpeed,
            gpsSaysMoving = gpsSaysMoving,
            motionFresh = motionFresh,
            motionSaysMoving = motionSaysMoving,
            stepsRecent = stepsRecent,
            turnDetected = detectsTurn(),
        )
    }

    private fun classify(
        sample: TrackingV2Sample,
        evidence: Evidence,
        stepDelta: Long,
    ): TrackingV2MovementState {
        val pedestrianEvidence = stepDelta > 0L || evidence.stepsRecent
        val coherentMovement = evidence.coherentDisplacement &&
            evidence.coordinateSpeedMetersPerSecond >= movementSpeedThreshold(sample.persona)
        val gpsMovementProved = evidence.gpsSaysMoving &&
            (!isPedestrian(sample.persona) || pedestrianEvidence || evidence.motionSaysMoving ||
                coherentMovement)
        val movementProved = pedestrianEvidence || gpsMovementProved || coherentMovement ||
            (evidence.motionSaysMoving && evidence.coordinateSpeedMetersPerSecond > 0.1f)

        if (movementProved) {
            stationaryCandidateSinceMillis = null
            return TrackingV2MovementState.MOVING
        }

        val lowFreshMotion = evidence.motionFresh &&
            (sample.motionEnergyMetersPerSecondSquared ?: Float.MAX_VALUE) <= STATIONARY_ENERGY
        val stationaryCandidate = lowFreshMotion && !pedestrianEvidence &&
            !evidence.gpsSaysMoving && !evidence.coherentDisplacement

        if (stationaryCandidate) {
            val since = stationaryCandidateSinceMillis ?: sample.elapsedRealtimeMillis.also {
                stationaryCandidateSinceMillis = it
            }
            val dwell = stationaryDwellMillis(sample.persona, sample.powerMode)
            return if (sample.elapsedRealtimeMillis - since >= dwell) {
                TrackingV2MovementState.STATIONARY
            } else {
                TrackingV2MovementState.POSSIBLY_MOVING
            }
        }

        stationaryCandidateSinceMillis = null
        return if (isDegraded(sample)) {
            TrackingV2MovementState.GPS_DEGRADED
        } else {
            TrackingV2MovementState.UNKNOWN
        }
    }

    private fun fusedSpeed(sample: TrackingV2Sample, evidence: Evidence, stepDelta: Long): Float {
        val candidates = mutableListOf<Pair<Float, Float>>()
        if (evidence.reliableGpsSpeed) {
            val speed = sample.gpsSpeedMetersPerSecond ?: 0f
            val accuracy = sample.gpsSpeedAccuracyMetersPerSecond ?: 1f
            candidates += speed to (1f / max(0.25f, accuracy * accuracy))
        }
        if (evidence.coherentDisplacement) {
            val weight = 1f / max(4f, sample.horizontalAccuracyMeters * sample.horizontalAccuracyMeters)
            candidates += evidence.coordinateSpeedMetersPerSecond to weight
        }
        if ((stepDelta > 0L || evidence.stepsRecent) && sample.stepCadenceHz != null) {
            candidates += (sample.stepCadenceHz * strideLengthMeters) to 1.5f
        }
        if (candidates.isEmpty()) return 0f
        val totalWeight = candidates.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)
        return candidates.sumOf { (value, weight) -> (value * weight).toDouble() }.toFloat() / totalWeight
    }

    private fun admitCoordinateDistance(
        point: TrackingV2Point,
        sample: TrackingV2Sample,
        turnDetected: Boolean,
    ): Double {
        val previousPoint = lastCoordinatePoint
        val previousTime = lastCoordinateTimeMillis
        if (previousPoint == null || previousTime == null) {
            val origin = window.firstOrNull()?.point() ?: point
            val initialDistance = haversineMeters(origin, point)
            if (isPlausibleSegment(initialDistance, sample, window.firstOrNull()?.elapsedRealtimeMillis)) {
                val admitted = if (turnDetected) initialDistance else alongWindowAxisMeters(origin, point)
                coordinateDistanceMeters += admitted
                lastCoordinatePoint = point
                lastCoordinateTimeMillis = sample.elapsedRealtimeMillis
                return admitted
            }
            lastCoordinatePoint = point
            lastCoordinateTimeMillis = sample.elapsedRealtimeMillis
            return 0.0
        }

        val segmentDistance = haversineMeters(previousPoint, point)
        if (!isPlausibleSegment(segmentDistance, sample, previousTime)) {
            rejectedOutlierCount++
            return 0.0
        }
        val admitted = if (turnDetected) segmentDistance else alongWindowAxisMeters(previousPoint, point)
        coordinateDistanceMeters += admitted
        lastCoordinatePoint = point
        lastCoordinateTimeMillis = sample.elapsedRealtimeMillis
        return admitted
    }

    private fun appendRoutePoint(
        point: TrackingV2Point,
        sample: TrackingV2Sample,
        turnDetected: Boolean,
    ) {
        val previousPoint = lastRoutePoint
        val previousTime = lastRouteTimeMillis
        if (previousPoint == null || previousTime == null) {
            val origin = window.firstOrNull()?.point() ?: point
            val segment = mutableListOf(origin)
            if (haversineMeters(origin, point) >= 0.5) segment += point
            routeSegments += segment
            lastRoutePoint = point
            lastRouteTimeMillis = sample.elapsedRealtimeMillis
            return
        }

        val segmentDistance = haversineMeters(previousPoint, point)
        if (!isPlausibleSegment(segmentDistance, sample, previousTime)) {
            rejectedOutlierCount++
            return
        }

        pendingRouteDistanceMeters += segmentDistance
        val routeThreshold = if (isPedestrian(sample.persona)) 1.0 else 2.5
        if (pendingRouteDistanceMeters >= routeThreshold || turnDetected) {
            if (routeSegments.isEmpty()) routeSegments += mutableListOf(previousPoint)
            routeSegments.last() += point
            pendingRouteDistanceMeters = 0.0
        }
        lastRoutePoint = point
        lastRouteTimeMillis = sample.elapsedRealtimeMillis
    }

    private fun isPlausibleSegment(
        segmentDistance: Double,
        sample: TrackingV2Sample,
        previousTimeMillis: Long?,
    ): Boolean {
        val previousTime = previousTimeMillis ?: return true
        val deltaSeconds = ((sample.elapsedRealtimeMillis - previousTime) / 1000.0).coerceAtLeast(0.001)
        val personaCeiling = when (sample.persona) {
            RidePersona.WALK -> 4f
            RidePersona.RUN -> 8f
            RidePersona.CYCLING -> 25f
            RidePersona.BIKE_DRIVE -> 70f
            RidePersona.CAR_DRIVE, RidePersona.AUTO -> 80f
        }
        val observedCeiling = sample.gpsSpeedMetersPerSecond
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { max(personaCeiling, it * 2f) }
            ?: personaCeiling
        val plausibleDistance = max(
            20.0,
            observedCeiling * deltaSeconds * 1.5 +
                sample.horizontalAccuracyMeters.coerceAtLeast(1f) * 1.5,
        )
        return segmentDistance <= plausibleDistance
    }

    /** Suppress left/right GPS oscillation from stats while retaining progress along the route. */
    private fun alongWindowAxisMeters(start: TrackingV2Point, end: TrackingV2Point): Double {
        val axisStart = window.firstOrNull()?.point() ?: return haversineMeters(start, end)
        val axisEnd = window.lastOrNull()?.point() ?: return haversineMeters(start, end)
        val referenceLatitude = Math.toRadians((axisStart.latitude + axisEnd.latitude) / 2.0)
        fun deltaMeters(a: TrackingV2Point, b: TrackingV2Point): Pair<Double, Double> {
            val east = Math.toRadians(b.longitude - a.longitude) *
                EARTH_RADIUS_METERS * cos(referenceLatitude)
            val north = Math.toRadians(b.latitude - a.latitude) * EARTH_RADIUS_METERS
            return east to north
        }
        val (axisEast, axisNorth) = deltaMeters(axisStart, axisEnd)
        val axisLength = hypot(axisEast, axisNorth)
        if (axisLength < 0.5) return 0.0
        val (segmentEast, segmentNorth) = deltaMeters(start, end)
        return abs(segmentEast * axisEast + segmentNorth * axisNorth) / axisLength
    }

    private fun minimumCoordinateWindowSize(powerMode: TrackingV2PowerMode): Int =
        if (powerMode == TrackingV2PowerMode.NORMAL) 5 else 3

    private fun calibrateStride(sample: TrackingV2Sample) {
        val steps = sample.cumulativeStepCount ?: return
        val anchorSteps = calibrationStepCount
        val anchorGpsDistance = calibrationGpsDistanceMeters
        val anchorAccuracy = calibrationAccuracyMeters
        if (anchorSteps == null || anchorGpsDistance == null || anchorAccuracy == null) {
            calibrationStepCount = steps
            calibrationGpsDistanceMeters = coordinateDistanceMeters
            calibrationAccuracyMeters = sample.horizontalAccuracyMeters
            return
        }
        val deltaSteps = steps - anchorSteps
        if (deltaSteps < MIN_CALIBRATION_STEPS) return
        val gpsDistance = (coordinateDistanceMeters - anchorGpsDistance).coerceAtLeast(0.0)
        val combinedAccuracy = hypot(anchorAccuracy.coerceAtLeast(1f), sample.horizontalAccuracyMeters.coerceAtLeast(1f))
        val minimumReliableBaseline = max(
            MIN_CALIBRATION_DISTANCE_METERS,
            (combinedAccuracy * CALIBRATION_UNCERTAINTY_MULTIPLIER).toDouble(),
        )
        val accuracyGoodEnough = anchorAccuracy <= MAX_CALIBRATION_ACCURACY_METERS &&
            sample.horizontalAccuracyMeters <= MAX_CALIBRATION_ACCURACY_METERS
        val baselineGoodEnough = gpsDistance >= minimumReliableBaseline
        val candidate = (gpsDistance / deltaSteps).toFloat()
        val hasDefinitiveAttempt = accuracyGoodEnough && baselineGoodEnough
        if (!hasDefinitiveAttempt && deltaSteps < MAX_CALIBRATION_STEPS) return

        calibrationAttemptCount++
        if (hasDefinitiveAttempt && candidate in MIN_STRIDE_METERS..MAX_STRIDE_METERS) {
            calibrationAcceptedCount++
            calibrationCandidates.addLast(candidate)
            while (calibrationCandidates.size > MAX_CALIBRATION_CANDIDATES) {
                calibrationCandidates.removeFirst()
            }
            val robustCandidate = calibrationCandidates.sorted()[calibrationCandidates.size / 2]
            strideLengthMeters = if (calibrationCandidates.size < 3) {
                strideLengthMeters * 0.8f + robustCandidate * 0.2f
            } else {
                robustCandidate
            }
        } else {
            calibrationRejectedCount++
        }
        calibrationStepCount = steps
        calibrationGpsDistanceMeters = coordinateDistanceMeters
        calibrationAccuracyMeters = sample.horizontalAccuracyMeters
    }

    private fun stepDelta(previousSample: TrackingV2Sample, sample: TrackingV2Sample): Long {
        val current = sample.cumulativeStepCount ?: return 0L
        val previous = lastStepCount ?: return 0L
        val rawDelta = current - previous
        if (rawDelta <= 0L) return 0L
        val elapsedSeconds = ((sample.elapsedRealtimeMillis - previousSample.elapsedRealtimeMillis) / 1_000.0)
            .coerceAtLeast(0.001)
        val plausibleMaximum = ceil(elapsedSeconds * MAX_PLAUSIBLE_STEP_HZ).toLong() + STEP_DELTA_JITTER_ALLOWANCE
        val admitted = rawDelta.coerceAtMost(plausibleMaximum)
        detectedStepCount += admitted
        discardedImplausibleStepCount += rawDelta - admitted
        return admitted
    }

    private fun freezeOpenStepBridge() {
        if (hybridBridgeStepCount <= 0L) return
        hybridCommittedDistanceMeters += hybridBridgeStepCount * strideLengthMeters.toDouble()
        hybridBridgeStepCount = 0L
    }

    private fun smoothCurrentPoint(sample: TrackingV2Sample, turnDetected: Boolean): TrackingV2Point {
        val points = window.toList()
        val maxPoints = when {
            turnDetected -> 2
            sample.powerMode == TrackingV2PowerMode.NORMAL -> 5
            else -> 8
        }
        val selected = points.takeLast(maxPoints)
        if (selected.size < 2) return sample.point()
        return TrackingV2Point(
            latitude = predictLatest(selected) { it.latitude },
            longitude = predictLatest(selected) { it.longitude },
        )
    }

    /** Weighted least-squares trajectory evaluated at "now": smoothing without endpoint lag. */
    private fun predictLatest(
        samples: List<TrackingV2Sample>,
        value: (TrackingV2Sample) -> Double,
    ): Double {
        val originMillis = samples.first().elapsedRealtimeMillis
        var sumWeight = 0.0
        var sumTime = 0.0
        var sumValue = 0.0
        var sumTimeSquared = 0.0
        var sumTimeValue = 0.0
        samples.forEach { candidate ->
            val time = (candidate.elapsedRealtimeMillis - originMillis) / 1_000.0
            val accuracy = candidate.horizontalAccuracyMeters.coerceAtLeast(2f).toDouble()
            val weight = 1.0 / (accuracy * accuracy)
            val coordinate = value(candidate)
            sumWeight += weight
            sumTime += weight * time
            sumValue += weight * coordinate
            sumTimeSquared += weight * time * time
            sumTimeValue += weight * time * coordinate
        }
        val denominator = sumWeight * sumTimeSquared - sumTime * sumTime
        if (sumWeight <= 0.0 || abs(denominator) < 1e-15) return value(samples.last())
        val slope = (sumWeight * sumTimeValue - sumTime * sumValue) / denominator
        val intercept = (sumValue - slope * sumTime) / sumWeight
        val latestTime = (samples.last().elapsedRealtimeMillis - originMillis) / 1_000.0
        return intercept + slope * latestTime
    }

    /**
     * Stats follow the latest fix more closely than the display line. Movement admission already
     * rejected stationary clouds; this small blend removes residual lateral noise without the
     * endpoint lag a five-point visual average would introduce into distance.
     */
    private fun distancePointFor(
        sample: TrackingV2Sample,
        smoothed: TrackingV2Point,
    ): TrackingV2Point {
        val rawWeight = if (sample.powerMode == TrackingV2PowerMode.NORMAL) 0.12 else 0.18
        return TrackingV2Point(
            latitude = sample.latitude * rawWeight + smoothed.latitude * (1.0 - rawWeight),
            longitude = sample.longitude * rawWeight + smoothed.longitude * (1.0 - rawWeight),
        )
    }

    private fun detectsTurn(): Boolean {
        val points = window.toList()
        if (points.size < 5) return false
        val middleIndex = points.lastIndex / 2
        val first = points.first()
        val middle = points[middleIndex]
        val last = points.last()
        val firstLeg = haversineMeters(first.point(), middle.point())
        val secondLeg = haversineMeters(middle.point(), last.point())
        val accuracyFloor = max(
            5.0,
            (first.horizontalAccuracyMeters + middle.horizontalAccuracyMeters + last.horizontalAccuracyMeters) / 2.0,
        )
        if (firstLeg < accuracyFloor || secondLeg < accuracyFloor) return false
        val firstLegPath = points.subList(0, middleIndex + 1).zipWithNext().sumOf { (a, b) ->
            haversineMeters(a.point(), b.point())
        }
        val secondLegPath = points.subList(middleIndex, points.size).zipWithNext().sumOf { (a, b) ->
            haversineMeters(a.point(), b.point())
        }
        val firstLegStraightness = firstLeg / firstLegPath.coerceAtLeast(0.001)
        val secondLegStraightness = secondLeg / secondLegPath.coerceAtLeast(0.001)
        if (firstLegStraightness < MIN_TURN_LEG_STRAIGHTNESS ||
            secondLegStraightness < MIN_TURN_LEG_STRAIGHTNESS
        ) return false
        val change = bearingDeltaDegrees(
            bearingDegrees(first.point(), middle.point()),
            bearingDegrees(middle.point(), last.point()),
        )
        return change >= TURN_DEGREES
    }

    private fun pruneWindow(sample: TrackingV2Sample) {
        val duration = if (sample.powerMode == TrackingV2PowerMode.NORMAL) {
            NORMAL_WINDOW_MILLIS
        } else {
            DEGRADED_WINDOW_MILLIS
        }
        while (window.size > 2 && sample.elapsedRealtimeMillis - window.first().elapsedRealtimeMillis > duration) {
            window.removeFirst()
        }
        while (window.size > MAX_WINDOW_SAMPLES) window.removeFirst()
    }

    private fun publish(
        sample: TrackingV2Sample,
        state: TrackingV2MovementState,
        speed: Float,
    ): TrackingV2Snapshot {
        val rawStepDistance = detectedStepCount * defaultStride(sample.persona).toDouble()
        val calibratedStepDistance = detectedStepCount * strideLengthMeters.toDouble()
        val pedestrianWithSteps = isPedestrian(sample.persona) && sample.cumulativeStepCount != null
        val hybridDistance = if (pedestrianWithSteps) {
            hybridCommittedDistanceMeters + hybridBridgeStepCount * strideLengthMeters.toDouble()
        } else {
            coordinateDistanceMeters
        }
        val sortedCandidates = calibrationCandidates.sorted()
        lastSnapshot = TrackingV2Snapshot(
            distanceMeters = hybridDistance,
            currentSpeedMetersPerSecond = speed.coerceAtLeast(0f),
            movementState = state,
            routeSegments = routeSegments.map { it.toList() },
            sampleCount = sampleCount,
            missingSpeedCount = missingSpeedCount,
            degradedSampleCount = degradedSampleCount,
            rejectedOutlierCount = rejectedOutlierCount,
            stepDistanceMeters = calibratedStepDistance,
            coordinateDistanceMeters = coordinateDistanceMeters,
            rawStepDistanceMeters = rawStepDistance,
            calibratedStepDistanceMeters = calibratedStepDistance,
            detectedStepCount = detectedStepCount,
            discardedImplausibleStepCount = discardedImplausibleStepCount,
            strideLengthMeters = strideLengthMeters,
            calibrationAttemptCount = calibrationAttemptCount,
            calibrationAcceptedCount = calibrationAcceptedCount,
            calibrationRejectedCount = calibrationRejectedCount,
            calibrationCandidateMinMeters = sortedCandidates.firstOrNull(),
            calibrationCandidateMedianMeters = sortedCandidates.takeIf { it.isNotEmpty() }
                ?.get(sortedCandidates.size / 2),
            calibrationCandidateMaxMeters = sortedCandidates.lastOrNull(),
            pedometerAvailable = sample.cumulativeStepCount != null,
            powerMode = sample.powerMode,
            isPostProcessed = false,
        )
        return lastSnapshot
    }

    private fun isDegraded(sample: TrackingV2Sample): Boolean =
        sample.powerMode != TrackingV2PowerMode.NORMAL || sample.horizontalAccuracyMeters > 25f

    private fun stationaryDwellMillis(persona: RidePersona, powerMode: TrackingV2PowerMode): Long {
        val base = when (persona) {
            RidePersona.WALK -> 6_000L
            RidePersona.RUN -> 5_000L
            RidePersona.CYCLING -> 5_000L
            RidePersona.BIKE_DRIVE, RidePersona.CAR_DRIVE -> 5_000L
            RidePersona.AUTO -> 6_000L
        }
        return if (powerMode == TrackingV2PowerMode.NORMAL) base else max(base, 10_000L)
    }

    private fun movementSpeedThreshold(persona: RidePersona): Float = when (persona) {
        RidePersona.WALK -> 0.2f
        RidePersona.RUN -> 0.5f
        RidePersona.CYCLING -> 0.8f
        RidePersona.BIKE_DRIVE -> 1.0f
        RidePersona.CAR_DRIVE -> 1.2f
        RidePersona.AUTO -> 0.6f
    }

    private fun defaultStride(persona: RidePersona): Float = when (persona) {
        RidePersona.RUN -> DEFAULT_RUN_STRIDE_METERS
        else -> DEFAULT_WALK_STRIDE_METERS
    }

    private fun isPedestrian(persona: RidePersona): Boolean =
        persona == RidePersona.WALK || persona == RidePersona.RUN

    private fun TrackingV2Sample.point() = TrackingV2Point(latitude, longitude)

    private fun simplify(points: List<TrackingV2Point>, epsilonMeters: Double): List<TrackingV2Point> {
        if (points.size <= 2) return points
        var maxDistance = 0.0
        var splitIndex = 0
        for (index in 1 until points.lastIndex) {
            val distance = perpendicularDistanceMeters(points[index], points.first(), points.last())
            if (distance > maxDistance) {
                maxDistance = distance
                splitIndex = index
            }
        }
        if (maxDistance <= epsilonMeters || splitIndex == 0) return listOf(points.first(), points.last())
        val left = simplify(points.subList(0, splitIndex + 1), epsilonMeters)
        val right = simplify(points.subList(splitIndex, points.size), epsilonMeters)
        return left.dropLast(1) + right
    }

    private fun perpendicularDistanceMeters(
        point: TrackingV2Point,
        start: TrackingV2Point,
        end: TrackingV2Point,
    ): Double {
        val referenceLatitude = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        fun local(p: TrackingV2Point): Pair<Double, Double> {
            val x = Math.toRadians(p.longitude - start.longitude) * EARTH_RADIUS_METERS * cos(referenceLatitude)
            val y = Math.toRadians(p.latitude - start.latitude) * EARTH_RADIUS_METERS
            return x to y
        }
        val (px, py) = local(point)
        val (ex, ey) = local(end)
        val denominator = ex * ex + ey * ey
        if (denominator <= 0.0) return hypot(px, py)
        val t = ((px * ex + py * ey) / denominator).coerceIn(0.0, 1.0)
        return hypot(px - t * ex, py - t * ey)
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val NORMAL_WINDOW_MILLIS = 12_000L
        private const val DEGRADED_WINDOW_MILLIS = 24_000L
        private const val MAX_WINDOW_SAMPLES = 16
        private const val MAX_OBSERVED_GAP_MILLIS = 15_000L
        private const val MIN_COHERENT_DISPLACEMENT_METERS = 4f
        private const val MIN_PATH_STRAIGHTNESS = 0.55f
        private const val MIN_COORDINATE_EVIDENCE_SAMPLES = 3
        private const val MIN_COORDINATE_EVIDENCE_MILLIS = 4_000L
        private const val STEP_RECENCY_MILLIS = 3_000L
        private const val MOTION_MOVING_ENERGY = 0.18f
        private const val STATIONARY_ENERGY = 0.10f
        private const val TURN_DEGREES = 25f
        private const val MIN_TURN_LEG_STRAIGHTNESS = 0.70
        private const val DEFAULT_WALK_STRIDE_METERS = 0.72f
        private const val DEFAULT_RUN_STRIDE_METERS = 1.05f
        private const val MIN_STRIDE_METERS = 0.35f
        private const val MAX_STRIDE_METERS = 1.50f
        private const val MIN_CALIBRATION_STEPS = 50L
        private const val MAX_CALIBRATION_STEPS = 200L
        private const val MIN_CALIBRATION_DISTANCE_METERS = 30.0
        private const val MAX_CALIBRATION_ACCURACY_METERS = 15f
        private const val CALIBRATION_UNCERTAINTY_MULTIPLIER = 4f
        private const val MAX_CALIBRATION_CANDIDATES = 7
        private const val MAX_PLAUSIBLE_STEP_HZ = 4.0
        private const val STEP_DELTA_JITTER_ALLOWANCE = 2L

        fun haversineMeters(a: TrackingV2Point, b: TrackingV2Point): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun bearingDegrees(a: TrackingV2Point, b: TrackingV2Point): Float {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val deltaLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(deltaLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }

        private fun bearingDeltaDegrees(first: Float, second: Float): Float {
            val delta = abs(first - second) % 360f
            return min(delta, 360f - delta)
        }
    }
}
