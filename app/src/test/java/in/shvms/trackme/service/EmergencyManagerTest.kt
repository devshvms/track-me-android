package `in`.shvms.trackme.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyManagerTest {

    @Test
    fun triggerThenResolve_isConsumedAsRideSuppression() {
        val manager = EmergencyManager()

        manager.beginRideSession()
        manager.triggerEmergency()
        manager.stopEmergency()

        assertTrue(manager.consumeRideSuppression())
        assertFalse(manager.consumeRideSuppression())
    }

    @Test
    fun beginRideSession_doesNotClearAnActiveEmergency() {
        val manager = EmergencyManager()
        manager.triggerEmergency()

        manager.beginRideSession()

        assertTrue(manager.consumeRideSuppression())
    }

    @Test
    fun beginRideSession_afterResolvedEmergency_startsClean() {
        val manager = EmergencyManager()
        manager.triggerEmergency()
        manager.stopEmergency()

        manager.beginRideSession()

        assertFalse(manager.consumeRideSuppression())
    }
}
