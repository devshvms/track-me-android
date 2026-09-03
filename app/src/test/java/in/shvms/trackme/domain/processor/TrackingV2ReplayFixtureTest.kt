package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.domain.model.RidePersona
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.cos

class TrackingV2ReplayFixtureTest {
    @Test
    fun `shared synthetic replay vectors satisfy v2 invariants`() {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_NAME)) {
            "Missing $FIXTURE_NAME"
        }.use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, bytes.sha256())

        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("\"latitude\""))
        assertFalse(text.contains("\"longitude\""))
        assertFalse(text.contains("\"routeTitle\""))

        val root = JSONObject(text)
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("synthetic_local_metres", root.getString("coordinateSpace"))

        val scenarios = root.getJSONArray("scenarios")
        assertTrue(scenarios.length() >= 6)
        repeat(scenarios.length()) { index ->
            val encoded = scenarios.getJSONObject(index)
            val scenario = encoded.toScenario()
            val result = TrackingV2ReplayHarness.run(scenario)
            val expected = encoded.getJSONObject("expected")

            assertTrue("${scenario.id} distance=${result.distanceMeters}", result.distanceMeters >= expected.getDouble("distanceMinMeters"))
            assertTrue("${scenario.id} distance=${result.distanceMeters}", result.distanceMeters <= expected.getDouble("distanceMaxMeters"))
            assertEquals(scenario.id, TrackingV2MovementState.valueOf(expected.getString("finalState")), result.movementState)
            assertTrue("${scenario.id} segments=${result.routeSegments.size}", result.routeSegments.size >= expected.getInt("routeSegmentsMin"))
            assertTrue("${scenario.id} segments=${result.routeSegments.size}", result.routeSegments.size <= expected.getInt("routeSegmentsMax"))
            assertEquals(scenario.id, expected.getInt("sampleCount"), result.sampleCount)
            assertEquals(scenario.id, expected.getInt("missingSpeedCount"), result.missingSpeedCount)
            assertEquals(scenario.id, expected.getInt("degradedSampleCount"), result.degradedSampleCount)
            assertEquals(scenario.id, expected.getInt("rejectedOutlierCount"), result.rejectedOutlierCount)
            assertEquals(scenario.id, expected.getLong("detectedStepCount"), result.detectedStepCount)
            assertTrue("${scenario.id} must finish", result.isPostProcessed)
        }
    }

    private fun JSONObject.toScenario(): TrackingV2ReplayScenario {
        val persona = RidePersona.valueOf(getString("persona"))
        val encodedEvents = getJSONArray("events")
        val events = buildList {
            repeat(encodedEvents.length()) { index ->
                val event = encodedEvents.getJSONObject(index)
                when (event.getString("kind")) {
                    "discontinuity" -> add(TrackingV2ReplayEvent.Discontinuity)
                    "sample" -> add(TrackingV2ReplayEvent.Sample(event.toSample(persona)))
                    else -> error("Unknown replay event kind in ${getString("id")}")
                }
            }
        }
        return TrackingV2ReplayScenario(getString("id"), persona, events)
    }

    private fun JSONObject.toSample(persona: RidePersona): TrackingV2Sample {
        val eastMeters = getDouble("eastMeters")
        val northMeters = getDouble("northMeters")
        val latitude = BASE_LATITUDE + northMeters / METERS_PER_DEGREE
        val longitude = BASE_LONGITUDE + eastMeters /
            (METERS_PER_DEGREE * cos(Math.toRadians(BASE_LATITUDE)))
        return TrackingV2Sample(
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = getDouble("accuracyMeters").toFloat(),
            elapsedRealtimeMillis = getLong("elapsedMillis"),
            gpsSpeedMetersPerSecond = nullableDouble("gpsSpeedMps")?.toFloat(),
            gpsSpeedAccuracyMetersPerSecond = nullableDouble("gpsSpeedAccuracyMps")?.toFloat(),
            motionEnergyMetersPerSecondSquared = nullableDouble("motionEnergy")?.toFloat(),
            motionSampleAgeMillis = nullableLong("motionAgeMillis"),
            cumulativeStepCount = nullableLong("steps"),
            stepAgeMillis = nullableLong("stepAgeMillis"),
            stepCadenceHz = nullableDouble("cadenceHz")?.toFloat(),
            persona = persona,
            powerMode = TrackingV2PowerMode.valueOf(getString("powerMode")),
        )
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (isNull(key)) null else getDouble(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FIXTURE_NAME = "tracking-v2-replay-v1.json"
        private const val FIXTURE_SHA256 = "bf135313375b5e499faa0be543d6181ac13216a5d375cd7dc021c86f5ea2b082"
        private const val BASE_LATITUDE = 0.0
        private const val BASE_LONGITUDE = 0.0
        private const val METERS_PER_DEGREE = 111_320.0
    }
}
