package `in`.shvms.trackme.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.0 §6.1 **B1** — the blocker that breaks the core promise.
 *
 * *"Location only flows while a ride is recording… A joined member who hasn't started riding is
 * invisible."* Everything here is that rule and its consequences, pure and testable without a
 * service, a device, or a GPS fix.
 */
class PresenceStreamPolicyTest {

    private val rideStates = listOf(
        TrackingState.TRACKING,
        TrackingState.PAUSED,
        TrackingState.GPS_LOST,
        TrackingState.GPS_DISABLED,
        TrackingState.STORAGE_LOW,
    )

    // --- Which stream is open ------------------------------------------------------------------

    @Test
    fun `no ride and no group means no location stream at all`() {
        assertEquals(
            LocationStreamMode.NONE,
            PresenceStreamPolicy.streamFor(TrackingState.IDLE, presenceMode = false),
        )
    }

    @Test
    fun `a group with no ride opens the balanced presence stream`() {
        // This is the case B1 says does not exist today, and the whole reason presence mode exists.
        assertEquals(
            LocationStreamMode.PRESENCE_BALANCED,
            PresenceStreamPolicy.streamFor(TrackingState.IDLE, presenceMode = true),
        )
    }

    @Test
    fun `a ride always uses the high-accuracy stream, group or not`() {
        for (state in rideStates) {
            for (presence in listOf(true, false)) {
                assertEquals(
                    "$state / presence=$presence",
                    LocationStreamMode.RIDE_HIGH_ACCURACY,
                    PresenceStreamPolicy.streamFor(state, presence),
                )
            }
        }
    }

    @Test
    fun `presence never opens a second subscription while a ride is running`() {
        // §4.6: "No second location subscription, no doubled GPS cost — this is the reason to
        // extend the service rather than add one." §7.4 budgets presence-with-a-ride at under
        // 1.5pp/hour precisely because GPS is already on and the marginal cost is network only.
        // Two streams would blow that outright.
        for (state in rideStates) {
            assertTrue(
                "$state opened a presence stream alongside the ride",
                PresenceStreamPolicy.streamFor(state, presenceMode = true) != LocationStreamMode.PRESENCE_BALANCED,
            )
        }
    }

    @Test
    fun `turning presence off mid-ride does not disturb the ride's stream`() {
        // §8's governing invariant: a group failure must never affect the user's own ride.
        for (state in rideStates) {
            assertEquals(
                LocationStreamMode.RIDE_HIGH_ACCURACY,
                PresenceStreamPolicy.streamFor(state, presenceMode = false),
            )
        }
    }

    // --- When a fix is pushed to the group -------------------------------------------------------

    @Test
    fun `presence pushes in every ride state, not only while TRACKING`() {
        // The B1 gate itself. Each of these is a member still in the group who still expects to be
        // seen: paused at a café, searching for GPS in a valley, out of storage, or not yet riding.
        // §2.6: "the person who got a flat tyre is exactly the person the group most needs to see."
        assertTrue(PresenceStreamPolicy.shouldPushPresence(TrackingState.IDLE, true))
        for (state in rideStates) {
            assertTrue("$state stopped pushing presence", PresenceStreamPolicy.shouldPushPresence(state, true))
        }
    }

    @Test
    fun `nothing is pushed when the user is not in a group`() {
        assertFalse(PresenceStreamPolicy.shouldPushPresence(TrackingState.IDLE, false))
        for (state in rideStates) {
            assertFalse(PresenceStreamPolicy.shouldPushPresence(state, false))
        }
    }

    // --- Accuracy ---------------------------------------------------------------------------------

    @Test
    fun `presence accepts fixes the ride recorder would discard`() {
        // The trap this exists to avoid: BALANCED_POWER_ACCURACY routinely returns 20-100m, so
        // reusing the recorder's 22m filter would silently discard nearly every presence fix and
        // make the feature look broken rather than fail.
        assertTrue("a typical balanced-accuracy fix was rejected", PresenceStreamPolicy.isAccurateEnoughForPresence(80f))
        assertTrue(PresenceStreamPolicy.isAccurateEnoughForPresence(30f))
        assertTrue(PresenceStreamPolicy.isAccurateEnoughForPresence(149f))
        assertTrue(
            "presence threshold must be looser than the recorder's",
            PresenceStreamPolicy.PRESENCE_MAX_ACCURACY_METERS > PresenceStreamPolicy.RIDE_MAX_ACCURACY_METERS,
        )
    }

    @Test
    fun `a fix too vague to be a position is still rejected`() {
        // A marker 80m out answers "roughly where is everyone". One 500m out is a guess, and a
        // wrong marker is worse than an absent one.
        assertFalse(PresenceStreamPolicy.isAccurateEnoughForPresence(500f))
        assertFalse(PresenceStreamPolicy.isAccurateEnoughForPresence(151f))
    }

    @Test
    fun `a fix with no accuracy reading is allowed through`() {
        // Some providers omit it. Dropping every such fix would make presence unusable on those
        // devices; the relay's own staleness handling covers a bad one.
        assertTrue(PresenceStreamPolicy.isAccurateEnoughForPresence(null))
    }

    // --- Service lifetime --------------------------------------------------------------------------

    @Test
    fun `the service may stop only when there is neither a ride nor a group`() {
        assertTrue(PresenceStreamPolicy.canStopService(TrackingState.IDLE, presenceMode = false))
    }

    @Test
    fun `ending a ride does not stop the service while still in a group`() {
        // §2.6: "Stopping a ride does not leave the group — the member keeps seeing others and
        // keeps sharing presence until they explicitly leave or the group ends."
        assertFalse(PresenceStreamPolicy.canStopService(TrackingState.IDLE, presenceMode = true))
    }

    @Test
    fun `leaving a group does not stop the service while a ride is running`() {
        for (state in rideStates) {
            assertFalse(
                "$state would have been torn down by leaving the group",
                PresenceStreamPolicy.canStopService(state, presenceMode = false),
            )
        }
    }

    // --- Play policy constraints (§16) --------------------------------------------------------

    @Test
    fun `background location stays undeclared`() {
        // §16.4 is a hard constraint, not a preference: "Do not add ACCESS_BACKGROUND_LOCATION for
        // this feature; the foreground service covers the need and adding it would trigger a whole
        // additional declaration and review track. This should be written down as a constraint on
        // the implementation, not discovered by a reviewer."
        //
        // Presence makes this tempting — it keeps location flowing with no ride in progress — so
        // the constraint gets a test rather than a comment. This team has already lost features to
        // Play review twice (1.6.4 and 1.6.5).
        val manifest = manifestText()
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION was declared — see SCOPE_1.7.0 §16.4",
            manifest.contains("ACCESS_BACKGROUND_LOCATION"),
        )
    }

    @Test
    fun `the location foreground service type is declared`() {
        // The other half of §16.2: presence runs the location foreground service without an active
        // ride, so the declaration has to cover it or the service cannot start on modern Android.
        assertTrue(
            "foregroundServiceType=location is missing",
            manifestText().contains("android:foregroundServiceType=\"location\""),
        )
    }

    private fun manifestText(): String {
        // Unit tests run with the module directory as the working dir; fall back by walking up so
        // this does not become a flake if that ever changes.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/AndroidManifest.xml")
                .takeIf { it.exists() }
                ?: File(dir, "src/main/AndroidManifest.xml").takeIf { it.exists() }
            if (candidate != null) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("AndroidManifest.xml not found from ${File("").absolutePath}")
    }

    // --- Cadence ------------------------------------------------------------------------------------

    @Test
    fun `presence sampling is slower than the ride's 2s stream but fast enough for the group`() {
        // The relay decides the upload cadence (§7.1); this is only how often the device is asked
        // for a fix. Sampling slower than the fastest group interval would leave the sync loop
        // sending stale positions; faster would wake GPS more often than the group can use.
        assertTrue(PresenceStreamPolicy.PRESENCE_INTERVAL_MS >= 2_000L)
        assertEquals(10_000L, PresenceStreamPolicy.PRESENCE_INTERVAL_MS)
        assertTrue(PresenceStreamPolicy.PRESENCE_MIN_INTERVAL_MS <= PresenceStreamPolicy.PRESENCE_INTERVAL_MS)
    }
}
