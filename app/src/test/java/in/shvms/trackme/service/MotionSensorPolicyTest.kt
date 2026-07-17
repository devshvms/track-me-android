package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSensorPolicyTest {
    @Test
    fun missingSensorNeverForcesTrackingToPause() {
        assertFalse(shouldTreatDeviceAsStationary(sensorAvailable = false, sampleReceived = false, motionEnergy = 0f))
    }

    @Test
    fun noSampleYetDoesNotClaimStationary() {
        assertFalse(shouldTreatDeviceAsStationary(sensorAvailable = true, sampleReceived = false, motionEnergy = 0f))
    }

    @Test
    fun lowEnergyAfterSampleIsStationary() {
        assertTrue(shouldTreatDeviceAsStationary(sensorAvailable = true, sampleReceived = true, motionEnergy = 0.1f))
        assertFalse(shouldTreatDeviceAsStationary(sensorAvailable = true, sampleReceived = true, motionEnergy = 0.3f))
    }

    @Test
    fun locationProvidersAreUnavailableOnlyWhenBothAreDisabled() {
        assertTrue(areLocationProvidersUnavailable(gpsEnabled = false, networkEnabled = false))
        assertFalse(areLocationProvidersUnavailable(gpsEnabled = true, networkEnabled = false))
        assertFalse(areLocationProvidersUnavailable(gpsEnabled = false, networkEnabled = true))
    }
}
