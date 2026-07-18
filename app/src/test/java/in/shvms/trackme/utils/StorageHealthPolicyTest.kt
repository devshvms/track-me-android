package `in`.shvms.trackme.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthPolicyTest {
    @Test
    fun availableSpaceAtThresholdIsAccepted() {
        assertFalse(StorageHealthMonitor.isLowStorage(50L * 1024L * 1024L))
    }

    @Test
    fun spaceBelowThresholdIsRejected() {
        assertTrue(StorageHealthMonitor.isLowStorage((50L * 1024L * 1024L) - 1L))
    }
}
