package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.*
import org.junit.Test

/** Synthetic metres around (0,0); no device trace or identifying location. */
class TrackingV2StationaryTest {
    @Test fun `two hour correlated optimistic GPS cloud cannot resume a confirmed stop`() {
        for (power in listOf(TrackingV2PowerMode.NORMAL, TrackingV2PowerMode.BATTERY_SAVER)) {
            val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
            // Twelve real minutes, then a stop, then multipath that looks straight in a short window.
            for (i in 0..360) session.add(fix(i * 2L, east = i * 2.0, speed = 1f,
                steps = i * 2L, stepAge = 0L, energy = .3f, power = power))
            for (i in 1..30) session.add(fix(720 + i * 2L, east = 720.0, steps = 720L, power = power))
            val distance = session.distanceMeters
            val duration = session.movingDurationMillis
            val route = session.snapshot.routeSegments
            for (i in 1..3600) {
                val angle = i * 2.0 * Math.PI / 90.0
                session.add(fix(780 + i * 2L, east = 720.0 + 9 * kotlin.math.sin(angle),
                    north = 9 * (1 - kotlin.math.cos(angle)), accuracy = 4f,
                    speed = .8f, speedAccuracy = .1f, steps = 720L,
                    energy = if (i % 7 == 0) .3f else .04f,
                    motionAge = if (i % 20 < 10) 9000L else 0L, power = power))
            }
            assertEquals("$power duration", duration, session.movingDurationMillis)
            assertEquals(distance, session.distanceMeters, 0.0)
            assertEquals(route, session.snapshot.routeSegments)
            assertEquals(1, session.snapshot.stationaryEntryCount)
            assertTrue(session.isAutoPaused)
        }
    }

    @Test fun `stationary GPS speed without coordinate departure never resumes`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..30) session.add(fix(i * 2L))
        for (i in 1..300) session.add(fix(60 + i * 2L, speed = 2f, speedAccuracy = .1f))
        assertEquals(0L, session.movingDurationMillis)
        assertEquals(0.0, session.distanceMeters, 0.0)
        assertTrue(session.isAutoPaused)
    }

    @Test fun `GPS departure backfill never recounts time already counted with debug auto pause off`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..30) session.add(fix(i * 2L))
        for (i in 1..30) session.add(fix(60 + i * 2L, east = i * 1.2, speed = .6f,
            speedAccuracy = .1f), autoPauseEnabled = i > 15)
        assertEquals(60000L, session.movingDurationMillis)
        assertTrue(session.distanceMeters in 30.0..39.0)
    }

    private fun fix(seconds: Long, east: Double = 0.0, north: Double = 0.0,
        speed: Float? = 0f, speedAccuracy: Float? = .4f, accuracy: Float = 6f,
        energy: Float? = .14f, motionAge: Long? = 0L, steps: Long? = null,
        stepAge: Long? = null, persona: RidePersona = RidePersona.WALK,
        power: TrackingV2PowerMode = TrackingV2PowerMode.NORMAL) = TrackingV2Sample(
        latitude = north / 111195.0, longitude = east / 111195.0,
        horizontalAccuracyMeters = accuracy, elapsedRealtimeMillis = seconds * 1000,
        gpsSpeedMetersPerSecond = speed, gpsSpeedAccuracyMetersPerSecond = speedAccuracy,
        motionEnergyMetersPerSecondSquared = energy, motionSampleAgeMillis = motionAge,
        cumulativeStepCount = steps, stepAgeMillis = stepAge,
        stepCadenceHz = if (stepAge == 0L) 1f else null, persona = persona, powerMode = power)

    @Test fun `held phone pauses once and stays still through ten minutes of urban drift`() {
        for (power in listOf(TrackingV2PowerMode.NORMAL, TrackingV2PowerMode.BATTERY_SAVER)) {
            val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
            for (i in 0..300) {
                val state = session.add(fix(i * 2L, east = (i % 7 - 3) * 3.0,
                    north = (i % 5 - 2) * 2.0, accuracy = 60f,
                    speed = if (i % 11 == 0) 1.2f else .35f,
                    speedAccuracy = if (i % 11 == 0) .2f else .8f,
                    energy = if (i % 4 == 0) .28f else .14f,
                    steps = 100L, power = power))
                if (i >= 12) {
                    assertEquals("$power at $i", TrackingV2MovementState.STATIONARY, state.movementState)
                    assertEquals(0f, state.currentSpeedMetersPerSecond, 0f)
                    assertEquals(0L, session.movingDurationMillis)
                    assertTrue(session.isAutoPaused)
                    assertTrue(state.distanceMeters <= 10.0)
                    assertTrue(state.routeSegments.flatten().size <= 1)
                }
            }
            assertEquals(1, session.snapshot.stationaryEntryCount)
        }
    }

    @Test fun `phone motion and raw drift alone never prove travel`() {
        val estimator = TrackingV2Estimator().apply { reset(RidePersona.WALK) }
        for (i in 0..90) {
            val state = estimator.add(fix(i * 2L, east = (i % 7 - 3) * 4.0,
                accuracy = 65f, speed = null, energy = .3f))
            assertNotEquals(TrackingV2MovementState.MOVING, state.movementState)
            assertEquals(0.0, state.distanceMeters, 0.0)
            assertTrue(state.routeSegments.isEmpty())
            assertEquals(0f, state.currentSpeedMetersPerSecond, 0f)
        }
    }

    @Test fun `uncertain GPS with absent or stale motion does not claim stationary`() {
        for (age in listOf(null, 9000L)) {
            val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
            for (i in 0..90) session.add(fix(i * 2L, east = (i % 3).toDouble(),
                accuracy = 60f, speed = null, energy = if (age == null) null else .02f,
                motionAge = age))
            assertNotEquals(TrackingV2MovementState.STATIONARY, session.snapshot.movementState)
            assertEquals(0L, session.movingDurationMillis)
            assertEquals(0.0, session.distanceMeters, 0.0)
        }
    }

    @Test fun `slow steps resume promptly without charging the seated interval`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..90) session.add(fix(i * 2L, steps = 100L))
        assertEquals(TrackingV2MovementState.STATIONARY, session.snapshot.movementState)
        val stoppedDuration = session.movingDurationMillis
        for (i in 1..15) {
            val previousDistance = session.distanceMeters
            val state = session.add(fix(180 + i * 2L, east = i * .72, speed = .18f,
                energy = .24f, steps = 100L + i, stepAge = 0L))
            assertEquals(TrackingV2MovementState.MOVING, state.movementState)
            assertFalse(session.isAutoPaused)
            assertTrue("distance must not run backwards", session.distanceMeters + .001 >= previousDistance)
        }
        assertEquals(30000L, session.movingDurationMillis - stoppedDuration)
        assertTrue(session.distanceMeters in 8.0..13.0)
        assertEquals(1, session.snapshot.stationaryEntryCount)
    }

    @Test fun `GPS only travel resumes while low acceleration vehicle and slow walk remain supported`() {
        for (persona in listOf(RidePersona.WALK, RidePersona.RUN, RidePersona.CYCLING, RidePersona.BIKE_DRIVE)) {
            val session = TrackingV2Session().apply { reset(persona) }
            for (i in 0..30) session.add(fix(i * 2L, energy = .02f, persona = persona))
            val speed = if (persona == RidePersona.WALK) .6f else 3f
            for (i in 1..30) session.add(fix(60 + i * 2L, east = i * 2.0 * speed,
                speed = speed, speedAccuracy = .1f, energy = .03f, persona = persona))
            assertEquals(persona.name, TrackingV2MovementState.MOVING, session.snapshot.movementState)
            assertTrue("$persona ${session.distanceMeters}", session.distanceMeters in 50.0 * speed..65.0 * speed)
            assertTrue("$persona time ${session.movingDurationMillis}", session.movingDurationMillis in 50000L..60000L)
        }
    }

    @Test fun `stationary final speed and route remain frozen after movement`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..15) session.add(fix(i * 2L, east = i * 2.0, speed = 1f,
            steps = i * 2L, stepAge = 0, energy = .3f))
        for (i in 1..30) session.add(fix(30 + i * 2L, east = 30.0 + (i % 3 - 1),
            speed = .3f, accuracy = 40f, speedAccuracy = .8f, steps = 30L))
        val paused = session.snapshot
        val duration = session.movingDurationMillis
        for (i in 1..90) session.add(fix(90 + i * 2L, east = 30.0 + (i % 5 - 2),
            speed = .3f, accuracy = 40f, speedAccuracy = .8f, steps = 30L,
            energy = if (i % 4 == 0) .25f else .14f))
        assertEquals(TrackingV2MovementState.STATIONARY, session.snapshot.movementState)
        assertEquals(paused.distanceMeters, session.distanceMeters, 0.0)
        assertEquals(duration, session.movingDurationMillis)
        assertEquals(paused.routeSegments, session.snapshot.routeSegments)
        assertEquals(0f, session.snapshot.currentSpeedMetersPerSecond, 0f)
    }

    @Test fun `one displaced fix is not a coherent path or a resume`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..90) session.add(fix(i * 2L, accuracy = 20f, speed = null))
        for (i in 1..10) {
            val state = session.add(fix(180 + i * 2L, east = 40.0,
                accuracy = 20f, speed = null, energy = .3f))
            assertEquals(TrackingV2MovementState.STATIONARY, state.movementState)
            assertEquals(0.0, state.distanceMeters, 0.0)
            assertEquals(0L, session.movingDurationMillis)
        }
    }

    @Test fun `ambiguous time is committed only on confirmed movement and not as a speed spike`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        session.add(fix(0, steps = 0L, speed = null))
        session.add(fix(2, east = .72, steps = 0L, speed = null))
        session.add(fix(4, east = 1.44, steps = 0L, speed = null))
        assertEquals(0L, session.movingDurationMillis)
        session.add(fix(6, east = 2.16, steps = 3L, stepAge = 0L, speed = null))
        assertEquals(6000L, session.movingDurationMillis)
        val cycling = TrackingV2Session().apply { reset(RidePersona.CYCLING) }
        for (i in 0..30) cycling.add(fix(i * 2L, east = i * 8.0, speed = 4f,
            energy = .02f, persona = RidePersona.CYCLING))
        assertEquals(4.0, cycling.maxSpeedMps, .05)
    }

    @Test fun `debug auto pause off counts observed time but never stationary drift or manual pause`() {
        val session = TrackingV2Session().apply { reset(RidePersona.WALK) }
        for (i in 0..15) session.add(fix(i * 2L), autoPauseEnabled = false)
        assertEquals(30000L, session.movingDurationMillis)
        assertEquals(TrackingV2MovementState.STATIONARY, session.snapshot.movementState)
        assertFalse(session.isAutoPaused)
        assertEquals(0.0, session.distanceMeters, 0.0)
        assertTrue(session.snapshot.routeSegments.isEmpty())
        session.pause()
        session.add(fix(32, east = 100.0), autoPauseEnabled = false)
        session.resume()
        session.add(fix(34, east = 150.0), autoPauseEnabled = false)
        assertEquals(30000L, session.movingDurationMillis)
        assertEquals(0.0, session.distanceMeters, 0.0)
        for (i in 1..15) session.add(fix(34 + i * 2L, east = 150.0))
        assertEquals(30000L, session.movingDurationMillis)
        assertTrue(session.isAutoPaused)
    }

    @Test fun `GPS outage clears unconfirmed time and does not bridge vehicle distance`() {
        val session = TrackingV2Session().apply { reset(RidePersona.CYCLING) }
        session.add(fix(0, persona = RidePersona.CYCLING))
        session.add(fix(2, persona = RidePersona.CYCLING))
        session.add(fix(60, east = 100.0, persona = RidePersona.CYCLING))
        assertEquals(TrackingV2MovementState.GPS_DEGRADED, session.snapshot.movementState)
        assertEquals(0L, session.movingDurationMillis)
        assertEquals(0.0, session.distanceMeters, 0.0)
        assertFalse(session.isAutoPaused)
    }
}
