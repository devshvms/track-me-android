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
    }

    @Test
    fun `unlocked mode honors explicit disabled overrides`() {
        assertFalse(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                debugModeEnabled = true,
                storedEnabled = false,
            ),
        )
    }

    @Test
    fun `unlocked default keeps auto pause enabled`() {
        assertTrue(
            TrackingAlgorithmControlPolicy.autoPauseEnabled(
                debugModeEnabled = true,
                storedEnabled = true,
            ),
        )
    }
}
