package `in`.shvms.trackme.service

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyManagerTest {

    @Test
    fun triggerThenResolve_isConsumedAsRideSuppression() {
        val manager = manager()

        manager.beginRideSession()
        manager.triggerEmergency()
        manager.stopEmergency()

        assertTrue(manager.consumeRideSuppression())
        assertFalse(manager.consumeRideSuppression())
    }

    @Test
    fun beginRideSession_doesNotClearAnActiveEmergency() {
        val manager = manager()
        manager.triggerEmergency()

        manager.beginRideSession()

        assertTrue(manager.consumeRideSuppression())
    }

    @Test
    fun beginRideSession_afterResolvedEmergency_startsClean() {
        val manager = manager()
        manager.triggerEmergency()
        manager.stopEmergency()

        manager.beginRideSession()

        assertFalse(manager.consumeRideSuppression())
    }

    @Test
    fun freshManager_readsSuppressionFromTrackingPreferences() {
        val preferences = InMemorySharedPreferences()
        val firstManager = EmergencyManager(preferences)

        firstManager.triggerEmergency()
        firstManager.stopEmergency()

        val restoredManager = EmergencyManager(preferences)

        assertTrue(restoredManager.consumeRideSuppression())
        assertFalse(restoredManager.consumeRideSuppression())
    }

    @Test
    fun freshManager_restoresActiveEmergencyAndStopPersistsResolution() {
        val preferences = InMemorySharedPreferences()
        val firstManager = EmergencyManager(preferences)

        firstManager.triggerEmergency()

        val restoredManager = EmergencyManager(preferences)
        assertTrue(restoredManager.isEmergencyActive.value)

        restoredManager.stopEmergency()

        val resolvedManager = EmergencyManager(preferences)
        assertFalse(resolvedManager.isEmergencyActive.value)
        assertNull(resolvedManager.emergencyStartedAtMillis.value)
    }

    @Test
    fun freshManager_restoresEmergencyStartTime() {
        val preferences = InMemorySharedPreferences()
        val firstManager = EmergencyManager(preferences)

        firstManager.triggerEmergency()
        val startedAt = firstManager.emergencyStartedAtMillis.value

        val restoredManager = EmergencyManager(preferences)

        assertNotNull(startedAt)
        assertEquals(startedAt, restoredManager.emergencyStartedAtMillis.value)
    }

    @Test
    fun triggerEmergency_doesNotResetAnActiveStartTime() {
        val manager = manager()

        manager.triggerEmergency()
        val startedAt = manager.emergencyStartedAtMillis.value
        manager.triggerEmergency()

        assertEquals(startedAt, manager.emergencyStartedAtMillis.value)
    }

    @Test
    fun activeEmergencyCreatedBeforeTimestamp_migratesToBoundedStartTime() {
        val preferences = InMemorySharedPreferences()
        preferences.edit().putBoolean("emergency_active", true).apply()
        val manager = EmergencyManager(preferences)

        val startedAt = manager.ensureEmergencyStartedAt()

        assertNotNull(startedAt)
        assertEquals(startedAt, EmergencyManager(preferences).emergencyStartedAtMillis.value)
    }

    @Test
    fun broadcastPolicy_usesElapsedTimeForCadenceAndStopsAtTwentyFourHours() {
        assertEquals(2 * 60 * 1000L, EmergencyBroadcastPolicy.delayMillis(0))
        assertEquals(10 * 60 * 1000L, EmergencyBroadcastPolicy.delayMillis(10))
        assertEquals(60 * 60 * 1000L, EmergencyBroadcastPolicy.delayMillis(60))
        assertNull(EmergencyBroadcastPolicy.delayMillis(EmergencyBroadcastPolicy.MAX_DURATION_MINUTES))
    }

    private fun manager(): EmergencyManager = EmergencyManager(InMemorySharedPreferences())

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        private val editor = Editor()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = editor
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                this@InMemorySharedPreferences.values[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
