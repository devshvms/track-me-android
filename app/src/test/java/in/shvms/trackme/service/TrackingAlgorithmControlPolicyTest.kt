package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingAlgorithmControlPolicyTest {
    @Test
    fun `release ignores stale disabled overrides`() {
        assertTrue(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                isDebugBuild = false,
                storedEnabled = false,
            ),
        )
        assertTrue(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                isDebugBuild = false,
                storedDisabled = true,
            ),
        )
    }

    @Test
    fun `debug build honors explicit disabled overrides`() {
        assertFalse(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                isDebugBuild = true,
                storedEnabled = false,
            ),
        )
        assertFalse(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                isDebugBuild = true,
                storedDisabled = true,
            ),
        )
    }

    @Test
    fun `debug defaults keep both algorithms enabled`() {
        assertTrue(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                isDebugBuild = true,
                storedEnabled = true,
            ),
        )
        assertTrue(
            TrackingAlgorithmControlPolicy.postProcessingEnabled(
                isDebugBuild = true,
                storedDisabled = false,
            ),
        )
    }
}
