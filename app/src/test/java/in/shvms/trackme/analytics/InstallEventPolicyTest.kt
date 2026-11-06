package `in`.shvms.trackme.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-355. Install-event ownership: fresh installs count once, updaters never, consent gates all. */
class InstallEventPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `a fresh install with no stored state is pending`() {
        assertEquals(
            InstallEventState.PENDING,
            InstallEventPolicy.resolveState(stored = null, firstInstallTimeMs = t0, lastUpdateTimeMs = t0),
        )
    }

    @Test
    fun `a few seconds of drift between install and update stamps is still a fresh install`() {
        assertEquals(
            InstallEventState.PENDING,
            InstallEventPolicy.resolveState(null, t0, t0 + 5_000L),
        )
        assertEquals(
            InstallEventState.PENDING,
            InstallEventPolicy.resolveState(null, t0, t0 + InstallEventPolicy.FRESH_INSTALL_TOLERANCE_MS),
        )
    }

    @Test
    fun `an app updated into this build is not an install`() {
        // e.g. a 1.8.11 rider upgrading to 1.9.1 weeks later must not inflate acquisition.
        assertEquals(
            InstallEventState.NOT_APPLICABLE,
            InstallEventPolicy.resolveState(null, t0, t0 + 7L * 24 * 60 * 60 * 1000),
        )
    }

    @Test
    fun `a stored state always wins over the install timestamps`() {
        assertEquals(
            InstallEventState.SENT,
            InstallEventPolicy.resolveState(InstallEventState.SENT, t0, t0),
        )
        assertEquals(
            InstallEventState.PENDING,
            InstallEventPolicy.resolveState(InstallEventState.PENDING, t0, t0 + 30L * 24 * 60 * 60 * 1000),
        )
        assertEquals(
            InstallEventState.NOT_APPLICABLE,
            InstallEventPolicy.resolveState(InstallEventState.NOT_APPLICABLE, t0, t0),
        )
    }

    @Test
    fun `only a pending install with telemetry enabled emits`() {
        assertTrue(InstallEventPolicy.shouldEmit(InstallEventState.PENDING, telemetryEnabled = true))
        assertFalse(InstallEventPolicy.shouldEmit(InstallEventState.PENDING, telemetryEnabled = false))
        assertFalse(InstallEventPolicy.shouldEmit(InstallEventState.SENT, telemetryEnabled = true))
        assertFalse(InstallEventPolicy.shouldEmit(InstallEventState.NOT_APPLICABLE, telemetryEnabled = true))
    }

    @Test
    fun `stored values round trip and unknown values are ignored`() {
        InstallEventState.entries.forEach {
            assertEquals(it, InstallEventState.fromStored(it.name))
        }
        assertNull(InstallEventState.fromStored(null))
        assertNull(InstallEventState.fromStored("garbage"))
    }
}
