package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingAlgorithmControlPolicyTest {
    @Test
    fun `locked mode ignores stale disabled overrides`() {
        assertTrue(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                debugModeEnabled = false,
                storedEnabled = false,
            ),
        )
        assertTrue(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                debugModeEnabled = false,
                storedDisabled = true,
            ),
        )
    }

    @Test
    fun `unlocked mode honors explicit disabled overrides`() {
        assertFalse(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                debugModeEnabled = true,
                storedEnabled = false,
            ),
        )
        assertFalse(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                debugModeEnabled = true,
                storedDisabled = true,
            ),
        )
    }

    @Test
    fun `unlocked defaults keep both algorithms enabled`() {
        assertTrue(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                debugModeEnabled = true,
                storedEnabled = true,
            ),
        )
        assertTrue(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                debugModeEnabled = true,
                storedDisabled = false,
            ),
        )
    }
}
