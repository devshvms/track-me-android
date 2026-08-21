package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.data.local.entity.RideEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRideSyncPolicyTest {
    private fun ride(isSample: Boolean = false, pendingDelete: Boolean = false) = RideEntity(
        startTime = 1L,
        isSample = isSample,
        pendingDelete = pendingDelete,
    )

    @Test
    fun onlyOrdinaryLiveRowsAreCloudEligible() {
        assertTrue(isRideEligibleForCloudSync(ride()))
        assertFalse(isRideEligibleForCloudSync(ride(isSample = true)))
        assertFalse(isRideEligibleForCloudSync(ride(pendingDelete = true)))
    }
}
