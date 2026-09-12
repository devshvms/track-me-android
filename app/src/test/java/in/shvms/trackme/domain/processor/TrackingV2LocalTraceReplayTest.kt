package `in`.shvms.trackme.domain.processor

import org.json.JSONObject
import org.json.JSONArray
import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in local investigation, not a ground-truth acceptance test. Never uploads a trace. */
class TrackingV2LocalTraceReplayTest {
    @Test fun replayLocalTraceWithExplicitMissingSensorHypotheses() {
        val path = System.getenv("TRACKME_REPLAY_REPORT")
        assumeTrue("Optional local replay requires a privately imported GPX report", path != null)
        val source = File(requireNotNull(path))
        require(source.length() in 1..(30L * 1024 * 1024))
        val report = JSONObject(source.readText())
        require(report.getString("schema") == "trackme-local-gpx-v1")
        val accuracy = requireNotNull(System.getenv("TRACKME_REPLAY_ACCURACY_METERS")) {
            "GPX has no accuracy: specify an explicit sensitivity-test hypothesis"
        }.toFloat().also { require(it.isFinite() && it > 0f) }
        val quiet = System.getenv("TRACKME_REPLAY_STATIONARY_ENERGY")?.toFloat()?.also {
            require(it.isFinite() && it >= 0f)
        }
        val after = report.optJSONObject("stationaryAnnotation")?.getDouble("afterMinutes")?.times(60_000)?.toLong()
        require(quiet == null || after != null) { "An annotated stationary interval is required for the quiet-motion hypothesis" }
        val points = report.getJSONArray("points")
        require(points.length() in 2..100_000)
        val start = points.getJSONObject(0).getLong("timeMillis")
        val persona = RidePersona.valueOf(report.getString("persona"))
        val session = TrackingV2Session().apply { reset(persona) }
        var segment = points.getJSONObject(0).getInt("segment")
        val results = JSONArray()
        repeat(points.length()) { index ->
            val point = points.getJSONObject(index)
            if (segment != point.getInt("segment")) {
                session.reset(persona, session.distanceMeters, session.movingDurationMillis, session.maxSpeedMps)
                segment = point.getInt("segment")
            }
            val elapsed = point.getLong("timeMillis") - start
            val motion = quiet.takeIf { after != null && elapsed >= after }
            val snapshot = session.add(TrackingV2Sample(point.getDouble("lat"), point.getDouble("lon"),
                accuracy, elapsed, null, null, motion, if (motion == null) null else 0L,
                null, null, null, persona, TrackingV2PowerMode.UNKNOWN))
            results.put(JSONObject().apply {
                put("elapsedMillis", elapsed)
                put("state", snapshot.movementState.name)
                put("distanceMeters", session.distanceMeters)
                put("activeMillis", session.movingDurationMillis)
            })
        }
        val output = JSONObject().apply {
            put("warning", "Sensitivity simulation using actual V2 code, NOT a reproduction of missing device sensors. No steps/speed; unknown power; optional assumed quiet motion after user annotation. Segment boundaries reset anchors.")
            put("assumedAccuracyMeters", accuracy)
            put("assumedStationaryEnergy", quiet ?: JSONObject.NULL)
            put("timeline", results)
        }
        File(source.parentFile, "replay-${UUID.randomUUID()}.json").writeText(output.toString())
    }
}
