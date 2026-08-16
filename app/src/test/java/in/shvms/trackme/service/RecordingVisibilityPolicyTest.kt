package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.3 §0 contract 2 and §2(b) — **"A ride recording is always visible."**
 *
 * The reported failure: Part 1 ended with a completion banner, Part 2 began recording in the
 * background, the UI showed nothing, and pressing start revealed a ride already ten minutes and
 * 300 m along.
 *
 * These tests pin the rule *and its consequence*. 1.7.2 shipped a bug where the policy was right
 * and the wiring was not, and 537 passing tests missed it — so the second half of this file asserts
 * that [TrackingService] actually routes through the policy and that the specific call which caused
 * §2(b) is gone. A green [RecordingVisibilityPolicy] with `splitRide` still calling
 * `trackingManager.reset()` would be exactly that class of bug again.
 */
class RecordingVisibilityPolicyTest {

    private val liveRideStates = listOf(
        TrackingState.TRACKING,
        TrackingState.PAUSED,
        TrackingState.GPS_LOST,
        TrackingState.GPS_DISABLED,
        TrackingState.STORAGE_LOW,
    )

    // --- The rule ------------------------------------------------------------------------------

    @Test
    fun `a ride being recorded is never observed as idle`() {
        // The whole contract, in one assertion. IDLE means "nothing is being recorded"; while a
        // ride id is held, points can land in the database, so IDLE would be a lie.
        assertEquals(
            TrackingState.TRACKING,
            RecordingVisibilityPolicy.observedStateFor(TrackingState.IDLE, hasActiveRide = true),
        )
    }

    @Test
    fun `idle with no ride is left exactly as it is`() {
        assertEquals(
            TrackingState.IDLE,
            RecordingVisibilityPolicy.observedStateFor(TrackingState.IDLE, hasActiveRide = false),
        )
    }

    @Test
    fun `every honest description of a live ride passes through untouched`() {
        // The policy corrects one thing only. Paused at a junction, hunting for GPS in a valley, or
        // out of storage are all visible, truthful states — rewriting them to TRACKING would invent
        // activity, which is the opposite failure and just as dishonest.
        for (state in liveRideStates) {
            assertEquals(
                "$state was rewritten",
                state,
                RecordingVisibilityPolicy.observedStateFor(state, hasActiveRide = true),
            )
        }
    }

    @Test
    fun `states are untouched when no ride is open either`() {
        for (state in liveRideStates) {
            assertEquals(
                "$state was rewritten with no ride open",
                state,
                RecordingVisibilityPolicy.observedStateFor(state, hasActiveRide = false),
            )
        }
    }

    @Test
    fun `the violation is detectable on its own`() {
        assertTrue(RecordingVisibilityPolicy.isInvisibleRecording(TrackingState.IDLE, hasActiveRide = true))
        assertFalse(RecordingVisibilityPolicy.isInvisibleRecording(TrackingState.IDLE, hasActiveRide = false))
        for (state in liveRideStates) {
            assertFalse(
                "$state was reported as an invisible recording",
                RecordingVisibilityPolicy.isInvisibleRecording(state, hasActiveRide = true),
            )
        }
    }

    @Test
    fun `the corrected state is always a state the policy itself accepts`() {
        // Idempotence: feeding the output back in must be a no-op, or two callers in sequence could
        // disagree. Cheap to state, and it is the property that makes the guard safe to apply at
        // every publication point rather than at one chosen one.
        for (state in TrackingState.values()) {
            for (hasRide in listOf(true, false)) {
                val once = RecordingVisibilityPolicy.observedStateFor(state, hasRide)
                assertEquals(
                    "$state / hasRide=$hasRide was not stable",
                    once,
                    RecordingVisibilityPolicy.observedStateFor(once, hasRide),
                )
                assertFalse(RecordingVisibilityPolicy.isInvisibleRecording(once, hasRide))
            }
        }
    }

    // --- The wiring ----------------------------------------------------------------------------

    @Test
    fun `the split does not reset the tracking state`() {
        // THE §2(b) DEFECT ITSELF. `trackingManager.reset()` sets trackingState to IDLE, and
        // splitRide called it while the recorder carried straight on into Part 2. The distinction
        // between reset() and resetForContinuation() is the entire fix, so it is asserted rather
        // than trusted.
        val body = bodyOf(serviceSource(), "private fun splitRide()")
        assertFalse(
            "splitRide calls trackingManager.reset(), which publishes IDLE while Part 2 records " +
                "— this is SCOPE_1.7.3 §2(b)",
            body.contains("trackingManager.reset()"),
        )
        assertTrue(
            "splitRide must clear metrics via resetForContinuation() so the ride stays visible",
            body.contains("trackingManager.resetForContinuation()"),
        )
    }

    @Test
    fun `the split leaves the rider's persona alone`() {
        // A quieter half of the same bug: reset() returned selectedPersona to AUTO, and splitRide
        // reads it back immediately to title and profile Part 2. A rider who chose CYCLING got a
        // Part 2 recorded as AUTO.
        val manager = TrackingManager()
        manager.setSelectedPersona(`in`.shvms.trackme.domain.model.RidePersona.CYCLING)
        manager.updateState(TrackingState.TRACKING)

        manager.resetForContinuation()

        assertEquals(
            "the rider's persona did not survive the split",
            `in`.shvms.trackme.domain.model.RidePersona.CYCLING,
            manager.selectedPersona.value,
        )
        assertEquals(
            "the recorder stopped being visible across the split",
            TrackingState.TRACKING,
            manager.trackingState.value,
        )
    }

    @Test
    fun `a continuation reset still clears the metrics Part 2 must start from zero`() {
        val manager = TrackingManager()
        manager.updateState(TrackingState.TRACKING)
        manager.addPathPoint(com.google.android.gms.maps.model.LatLng(12.97, 77.59))
        manager.addDistance(4200f)
        manager.updateDuration(900_000L)
        manager.updateElapsedDuration(960_000L)
        manager.updateSpeed(7.5f)
        manager.setAutoPaused(true)

        manager.resetForContinuation()

        assertTrue(manager.pathPoints.value.isEmpty())
        assertEquals(0f, manager.totalDistance.value, 0f)
        assertEquals(0L, manager.rideDurationInMillis.value)
        assertEquals(0L, manager.elapsedDurationInMillis.value)
        assertEquals(0f, manager.currentSpeed.value, 0f)
        assertFalse(manager.isAutoPaused.value)
    }

    @Test
    fun `a full reset still returns the screen to idle`() {
        // The other caller (stopTracking) must keep working exactly as before.
        val manager = TrackingManager()
        manager.updateState(TrackingState.TRACKING)
        manager.setSelectedPersona(`in`.shvms.trackme.domain.model.RidePersona.CYCLING)
        manager.addDistance(1000f)

        manager.reset()

        assertEquals(TrackingState.IDLE, manager.trackingState.value)
        assertEquals(`in`.shvms.trackme.domain.model.RidePersona.AUTO, manager.selectedPersona.value)
        assertEquals(0f, manager.totalDistance.value, 0f)
    }

    @Test
    fun `state is published only through the visibility policy`() {
        // The guard is worth nothing if a caller can route around it. Exactly one place may call
        // trackingManager.updateState(...), and it must be the one that consults the policy.
        val source = serviceSource()
        val publications = Regex("""trackingManager\.updateState\(""").findAll(source).count()
        assertEquals(
            "trackingManager.updateState() must be called from exactly one place in TrackingService " +
                "(updateState), or the §0.2 guard can be bypassed",
            1,
            publications,
        )
        val guard = bodyOf(source, "private fun updateState(newState: TrackingState)")
        assertTrue(
            "updateState must consult RecordingVisibilityPolicy before publishing",
            guard.contains("RecordingVisibilityPolicy.observedStateFor("),
        )
    }

    @Test
    fun `stopping releases the ride before it claims to be idle`() {
        // Ordering carries the correctness: "observed IDLE" means "nothing is recording", so the
        // ride id has to be gone before that claim is made. Publishing IDLE first left the honest
        // state and the published state disagreeing for the width of the function — the same shape
        // as §2(b), just shorter-lived.
        val body = bodyOf(serviceSource(), "private fun stopTracking(")
        val clearsId = body.indexOf("currentRideId = null")
        val claimsIdle = body.indexOf("updateState(TrackingState.IDLE)")
        assertTrue("stopTracking no longer clears currentRideId", clearsId >= 0)
        assertTrue("stopTracking no longer publishes IDLE", claimsIdle >= 0)
        assertTrue(
            "stopTracking publishes IDLE while currentRideId is still held",
            clearsId < claimsIdle,
        )
    }

    private fun serviceSource(): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/service/TrackingService.kt"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("TrackingService.kt not found")
    }

    /** Brace-matched body of the named declaration. */
    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        require(start >= 0) { "\"$declaration\" not found — did it get renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces in $declaration")
    }
}
