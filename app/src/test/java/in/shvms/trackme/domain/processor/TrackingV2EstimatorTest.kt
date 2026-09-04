package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.service.TrackingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TrackingV2EstimatorTest {

    @Test
    fun `hour-long walk below point three mps still counts step distance`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.WALK)
        var steps = 0L

        for (index in 0..1_800) {
            if (index > 0 && index % 2 == 0) steps++
            val eastMeters = steps * 0.72
            estimator.add(
                sample(
                    eastMeters = eastMeters,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.WALK,
                    gpsSpeed = 0.18f,
                    motionEnergy = 0.24f,
                    cumulativeSteps = steps,
                    stepAgeMillis = if (index % 2 == 0) 0L else 2_000L,
                    cadenceHz = 0.25f,
                )
            )
        }

        val result = estimator.finish()
        // GPS-calibrated stride may adapt slightly, but the one-hour low-speed walk must remain
        // inside the explicit 99% deterministic accuracy envelope.
        assertEquals(648.0, result.distanceMeters, 6.48)
        assertEquals(result.distanceMeters, result.stepDistanceMeters, 0.01)
        assertTrue(result.currentSpeedMetersPerSecond < 0.3f)
        assertTrue(result.pedometerAvailable)
    }

    @Test
    fun `stationary alternating ten metre drift is not movement`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CAR_DRIVE)

        repeat(30) { index ->
            val east = when (index % 3) {
                0 -> 0.0
                1 -> 10.0
                else -> -10.0
            }
            estimator.add(
                sample(
                    eastMeters = east,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CAR_DRIVE,
                    accuracyMeters = 8f,
                    gpsSpeed = null,
                    motionEnergy = 0.02f,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.STATIONARY, result.movementState)
        assertEquals(0.0, result.distanceMeters, 0.01)
        assertTrue(result.routeSegments.flatten().size <= 1)
    }

    @Test
    fun `slow coordinate wander without steps does not invent walking distance`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.WALK)

        repeat(60) { index ->
            estimator.add(
                sample(
                    eastMeters = (index % 5 - 2) * 0.8,
                    northMeters = (index % 3 - 1) * 0.7,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.WALK,
                    accuracyMeters = 8f,
                    gpsSpeed = null,
                    motionEnergy = 0.02f,
                    cumulativeSteps = null,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.STATIONARY, result.movementState)
        assertEquals(0.0, result.distanceMeters, 0.01)
    }

    @Test
    fun `missing gps speed still admits coherent straight vehicle movement`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CAR_DRIVE)

        for (index in 0..10) {
            estimator.add(
                sample(
                    eastMeters = index * 10.0,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CAR_DRIVE,
                    accuracyMeters = 5f,
                    gpsSpeed = null,
                    motionEnergy = 0.03f,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.MOVING, result.movementState)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters in 95.0..103.0)
        assertTrue(result.missingSpeedCount == result.sampleCount)
    }

    @Test
    fun `battery saver sparse fixes are classified instead of blindly rejected`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.BIKE_DRIVE)

        for (index in 0..10) {
            estimator.add(
                sample(
                    eastMeters = index * 30.0,
                    elapsedMillis = index * 10_000L,
                    persona = RidePersona.BIKE_DRIVE,
                    accuracyMeters = 32f,
                    gpsSpeed = null,
                    motionEnergy = 0.28f,
                    powerMode = TrackingV2PowerMode.BATTERY_SAVER,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.MOVING, result.movementState)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters in 285.0..315.0)
        assertEquals(result.sampleCount, result.degradedSampleCount)
        assertEquals(result.sampleCount, result.powerRestrictedSampleCount)
        assertEquals(result.sampleCount, result.poorAccuracySampleCount)
        assertEquals(0, result.unobservedGapCount)
        assertEquals(10_000L, result.maximumSampleIntervalMillis)
    }

    @Test
    fun `screen off power mode separates power restriction from poor accuracy`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.BIKE_DRIVE)

        for (index in 0..10) {
            estimator.add(
                sample(
                    eastMeters = index * 30.0,
                    elapsedMillis = index * 10_000L,
                    persona = RidePersona.BIKE_DRIVE,
                    accuracyMeters = if (index % 3 == 0) 32f else 18f,
                    gpsSpeed = null,
                    motionEnergy = 0.28f,
                    powerMode = TrackingV2PowerMode.GPS_DISABLED_WHEN_SCREEN_OFF,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.MOVING, result.movementState)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters in 285.0..315.0)
        assertEquals(11, result.powerRestrictedSampleCount)
        assertEquals(4, result.poorAccuracySampleCount)
        assertEquals(11, result.degradedSampleCount)
        assertEquals(0, result.unobservedGapCount)
        assertEquals(10_000L, result.maximumSampleIntervalMillis)
    }

    @Test
    fun `degraded random jumps with motion do not become distance`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.BIKE_DRIVE)
        val randomCloud = listOf(0.0, 40.0, -40.0, 30.0, -30.0, 45.0, -35.0)
        randomCloud.forEachIndexed { index, east ->
            estimator.add(
                sample(
                    eastMeters = east,
                    elapsedMillis = index * 10_000L,
                    persona = RidePersona.BIKE_DRIVE,
                    accuracyMeters = 35f,
                    gpsSpeed = null,
                    motionEnergy = 0.3f,
                    powerMode = TrackingV2PowerMode.BATTERY_SAVER,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(0.0, result.distanceMeters, 0.01)
        assertTrue(result.routeSegments.flatten().size <= 1)
    }

    @Test
    fun `constant speed motorcycle remains moving with low acceleration`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.BIKE_DRIVE)

        for (index in 0..20) {
            estimator.add(
                sample(
                    eastMeters = index * 10.0,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.BIKE_DRIVE,
                    accuracyMeters = 5f,
                    gpsSpeed = 5f,
                    motionEnergy = 0.03f,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(TrackingV2MovementState.MOVING, result.movementState)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters in 198.0..202.0)
    }

    @Test
    fun `rolling route preserves a right angle and final endpoint`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CYCLING)
        val path = buildList {
            for (east in 0..50 step 10) add(east.toDouble() to 0.0)
            for (north in 10..50 step 10) add(50.0 to north.toDouble())
        }

        path.forEachIndexed { index, (east, north) ->
            estimator.add(
                sample(
                    eastMeters = east,
                    northMeters = north,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CYCLING,
                    accuracyMeters = 5f,
                    gpsSpeed = null,
                    motionEnergy = 0.3f,
                )
            )
        }

        val liveDistance = estimator.snapshot().distanceMeters
        val result = estimator.finish()
        val route = result.routeSegments.flatten()
        val corner = point(eastMeters = 50.0, northMeters = 0.0)
        val end = point(eastMeters = 50.0, northMeters = 50.0)
        assertTrue(route.minOf { TrackingV2Estimator.haversineMeters(it, corner) } < 9.0)
        assertTrue(TrackingV2Estimator.haversineMeters(route.last(), end) < 1.0)
        assertEquals(liveDistance, result.distanceMeters, 0.0001)
    }

    @Test
    fun `alternating lateral gps error becomes a smooth straight route`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CAR_DRIVE)

        for (index in 0..20) {
            val lateralError = when {
                index == 0 -> 0.0
                index % 2 == 0 -> 10.0
                else -> -10.0
            }
            estimator.add(
                sample(
                    eastMeters = index * 10.0,
                    northMeters = lateralError,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CAR_DRIVE,
                    accuracyMeters = 8f,
                    gpsSpeed = 5f,
                    motionEnergy = 0.25f,
                )
            )
        }

        val result = estimator.finish()
        val maximumLateralError = result.routeSegments.flatten().maxOf { point ->
            abs(point.latitude - BASE_LATITUDE) * METERS_PER_DEGREE
        }
        assertTrue("lateral error=$maximumLateralError", maximumLateralError <= 6.0)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters in 196.0..204.0)
    }

    @Test
    fun `curved route is not simplified into its endpoint chord`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CYCLING)
        val curve = (0..9).map { index ->
            val angle = Math.toRadians(-90.0 + index * 10.0)
            (50.0 * cos(angle)) to (50.0 + 50.0 * sin(angle))
        }
        curve.forEachIndexed { index, (east, north) ->
            estimator.add(
                sample(
                    eastMeters = east,
                    northMeters = north,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CYCLING,
                    accuracyMeters = 4f,
                    gpsSpeed = 4f,
                    motionEnergy = 0.25f,
                )
            )
        }

        val route = estimator.finish().routeSegments.flatten()
        val middleOfCurve = point(35.36, 14.64)
        assertTrue(route.size > 2)
        assertTrue(route.minOf { TrackingV2Estimator.haversineMeters(it, middleOfCurve) } < 9.0)
    }

    @Test
    fun `u turn retains reversal instead of becoming one straight chord`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CYCLING)
        val uTurn = buildList {
            for (east in 0..50 step 10) add(east.toDouble() to 0.0)
            add(55.0 to 5.0)
            add(50.0 to 10.0)
            for (east in 40 downTo 0 step 10) add(east.toDouble() to 15.0)
        }
        uTurn.forEachIndexed { index, (east, north) ->
            estimator.add(
                sample(
                    eastMeters = east,
                    northMeters = north,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.CYCLING,
                    accuracyMeters = 4f,
                    gpsSpeed = 4f,
                    motionEnergy = 0.3f,
                )
            )
        }

        val route = estimator.finish().routeSegments.flatten()
        assertTrue(route.any { TrackingV2Estimator.haversineMeters(it, point(55.0, 5.0)) < 9.0 })
        assertTrue(TrackingV2Estimator.haversineMeters(route.last(), point(0.0, 15.0)) < 2.0)
    }

    @Test
    fun `manual discontinuity never bridges distance or route`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CYCLING)
        for (index in 0..4) {
            estimator.add(movingCyclingSample(index * 10.0, index * 2_000L))
        }
        val beforePause = estimator.snapshot().distanceMeters
        estimator.markDiscontinuity()
        for (index in 0..4) {
            estimator.add(movingCyclingSample(500.0 + index * 10.0, 20_000L + index * 2_000L))
        }

        val result = estimator.finish()
        assertTrue(result.routeSegments.size >= 2)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters < beforePause + 50.0)
    }

    @Test
    fun `long unobserved gap starts a new segment without charging the bridge`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.CYCLING)
        for (index in 0..4) {
            estimator.add(movingCyclingSample(index * 10.0, index * 2_000L))
        }
        estimator.add(movingCyclingSample(500.0, 30_000L))
        for (index in 1..4) {
            estimator.add(movingCyclingSample(500.0 + index * 10.0, 30_000L + index * 2_000L))
        }

        val result = estimator.finish()
        assertTrue(result.routeSegments.size >= 2)
        assertTrue("distance=${result.distanceMeters}", result.distanceMeters < 90.0)
        assertEquals(1, result.unobservedGapCount)
        assertEquals(22_000L, result.maximumSampleIntervalMillis)
    }

    @Test
    fun `walking publishes independent gps raw step calibrated step and gps anchored hybrid totals`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.WALK)
        var steps = 0L

        for (index in 0..40) {
            if (index > 0) steps++
            estimator.add(
                sample(
                    eastMeters = index * 5.0,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.WALK,
                    accuracyMeters = 4f,
                    gpsSpeed = 2.5f,
                    motionEnergy = 0.2f,
                    cumulativeSteps = steps,
                    stepAgeMillis = 0L,
                    cadenceHz = 0.5f,
                )
            )
        }

        val result = estimator.finish()
        assertTrue("gps=${result.coordinateDistanceMeters}", result.coordinateDistanceMeters in 190.0..205.0)
        assertEquals(40L, result.detectedStepCount)
        assertEquals(28.8, result.rawStepDistanceMeters, 0.01)
        assertEquals(result.coordinateDistanceMeters, result.distanceMeters, 5.0)
        assertTrue(result.calibratedStepDistanceMeters < result.distanceMeters / 3.0)
        assertEquals(0, result.calibrationAttemptCount)
    }

    @Test
    fun `stride calibration waits for a long accuracy bounded gps baseline`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.WALK)
        var steps = 0L

        for (index in 0..100) {
            if (index > 0) steps += 2
            estimator.add(
                sample(
                    eastMeters = steps * 0.72,
                    elapsedMillis = index * 2_000L,
                    persona = RidePersona.WALK,
                    accuracyMeters = 3f,
                    gpsSpeed = 0.72f,
                    motionEnergy = 0.2f,
                    cumulativeSteps = steps,
                    stepAgeMillis = 0L,
                    cadenceHz = 1f,
                )
            )
        }

        val result = estimator.finish()
        assertEquals(200L, result.detectedStepCount)
        assertTrue("accepted=${result.calibrationAcceptedCount}", result.calibrationAcceptedCount >= 2)
        assertEquals(result.calibrationAcceptedCount, result.calibrationAttemptCount)
        assertEquals(0, result.calibrationRejectedCount)
        assertEquals(0.72f, result.strideLengthMeters, 0.08f)
        assertEquals(result.coordinateDistanceMeters, result.distanceMeters, 3.0)
    }

    @Test
    fun `sparse callback does not drop legitimate accumulated step detector events`() {
        val estimator = TrackingV2Estimator()
        estimator.reset(RidePersona.WALK)
        estimator.add(
            sample(
                eastMeters = 0.0,
                elapsedMillis = 0L,
                persona = RidePersona.WALK,
                cumulativeSteps = 0L,
                stepAgeMillis = null,
            )
        )
        estimator.add(
            sample(
                eastMeters = 14.4,
                elapsedMillis = 10_000L,
                persona = RidePersona.WALK,
                gpsSpeed = 1.44f,
                motionEnergy = 0.2f,
                cumulativeSteps = 20L,
                stepAgeMillis = 0L,
                cadenceHz = 2f,
            )
        )

        val result = estimator.finish()
        assertEquals(20L, result.detectedStepCount)
        assertEquals(0L, result.discardedImplausibleStepCount)
        assertEquals(14.4, result.rawStepDistanceMeters, 0.01)
        assertEquals(14.4, result.distanceMeters, 0.01)
    }

    @Test
    fun `v2 manager updates cannot mutate v1 canonical distance`() {
        val manager = TrackingManager()
        manager.addDistance(123f)
        manager.updateTrackingV2(TrackingV2Snapshot(distanceMeters = 999.0))

        assertEquals(123f, manager.totalDistance.value, 0f)
        assertEquals(999.0, manager.trackingV2Snapshot.value?.distanceMeters ?: 0.0, 0.0)
    }

    private fun movingCyclingSample(eastMeters: Double, elapsedMillis: Long) = sample(
        eastMeters = eastMeters,
        elapsedMillis = elapsedMillis,
        persona = RidePersona.CYCLING,
        accuracyMeters = 5f,
        gpsSpeed = 5f,
        motionEnergy = 0.3f,
    )

    private fun sample(
        eastMeters: Double,
        northMeters: Double = 0.0,
        elapsedMillis: Long,
        persona: RidePersona,
        accuracyMeters: Float = 6f,
        gpsSpeed: Float? = null,
        motionEnergy: Float? = 0.02f,
        cumulativeSteps: Long? = null,
        stepAgeMillis: Long? = null,
        cadenceHz: Float? = null,
        powerMode: TrackingV2PowerMode = TrackingV2PowerMode.NORMAL,
    ): TrackingV2Sample {
        val point = point(eastMeters, northMeters)
        return TrackingV2Sample(
            latitude = point.latitude,
            longitude = point.longitude,
            horizontalAccuracyMeters = accuracyMeters,
            elapsedRealtimeMillis = elapsedMillis,
            gpsSpeedMetersPerSecond = gpsSpeed,
            gpsSpeedAccuracyMetersPerSecond = gpsSpeed?.let { 0.4f },
            motionEnergyMetersPerSecondSquared = motionEnergy,
            motionSampleAgeMillis = motionEnergy?.let { 0L },
            cumulativeStepCount = cumulativeSteps,
            stepAgeMillis = stepAgeMillis,
            stepCadenceHz = cadenceHz,
            persona = persona,
            powerMode = powerMode,
        )
    }

    private fun point(eastMeters: Double, northMeters: Double): TrackingV2Point {
        val latitude = BASE_LATITUDE + northMeters / METERS_PER_DEGREE
        val longitude = BASE_LONGITUDE +
            eastMeters / (METERS_PER_DEGREE * cos(Math.toRadians(BASE_LATITUDE)))
        return TrackingV2Point(latitude, longitude)
    }

    companion object {
        private const val BASE_LATITUDE = 12.9716
        private const val BASE_LONGITUDE = 77.5946
        private const val METERS_PER_DEGREE = 111_320.0
    }
}
