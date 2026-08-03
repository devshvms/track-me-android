package `in`.shvms.trackme.service

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TG-A03/A05 (1.6.4): the SOS trigger surface is gone, so this class no longer covers
 * triggering, resolving or broadcast cadence. What remains is the reason EmergencyManager
 * survives at all (HAZARD-2) — per-ride celebration suppression, which TrackingService
 * calls at ride start, ride split and finalize — plus the one-time upgrade clear.
 */
class EmergencyManagerTest {

    @Test
    fun beginRideSession_onACleanInstall_leavesNothingToSuppress() {
        val manager = manager()

        manager.beginRideSession()

        assertFalse(manager.consumeRideSuppression())
    }

    @Test
    fun aStrandedActiveEmergency_stillSuppressesTheRideItStarted() {
        // Belt-and-braces: SosStateCleanup clears this before EmergencyManager is built, but
        // if a stranded bit ever survived, the ride it covers must not earn a celebration.
        val preferences = InMemorySharedPreferences()
        preferences.edit().putBoolean("emergency_active", true).apply()
        val manager = EmergencyManager(preferences)

        manager.beginRideSession()

        assertTrue(manager.consumeRideSuppression())
    }

    @Test
    fun consumeRideSuppression_isExactlyOnce() {
        val preferences = InMemorySharedPreferences()
        preferences.edit().putBoolean("emergency_triggered_for_ride", true).apply()
        val manager = EmergencyManager(preferences)

        assertTrue(manager.consumeRideSuppression())
        assertFalse(manager.consumeRideSuppression())
        assertFalse(EmergencyManager(preferences).consumeRideSuppression())
    }

    @Test
    fun suppressionSurvivesProcessDeath_soFinalizeAfterARestartStillSuppresses() {
        val preferences = InMemorySharedPreferences()
        preferences.edit().putBoolean("emergency_active", true).apply()
        // The ride starts under a stranded active state, then the process dies.
        EmergencyManager(preferences).beginRideSession()

        // A fresh manager (new process) finalizes that ride and must still suppress it.
        assertTrue(EmergencyManager(preferences).consumeRideSuppression())
    }

    // --- TG-A05 / HAZARD-1: the one-time upgrade clear -------------------------------------

    @Test
    fun clearOnce_clearsStrandedSosStateAndRunsExactlyOnce() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putBoolean("emergency_active", true)
            .putLong("emergency_started_at", 1_700_000_000_000L)
            .putBoolean("emergency_triggered_for_ride", true)
            .apply()

        assertTrue("the first run must perform the clear", SosStateCleanup.clearOnce(preferences))

        assertFalse(preferences.contains("emergency_active"))
        assertFalse(preferences.contains("emergency_started_at"))
        assertFalse(preferences.contains("emergency_triggered_for_ride"))
        assertTrue(preferences.getBoolean("sos_state_cleared_v164", false))
        // The whole point of HAZARD-1: an upgrading user is no longer stranded "active".
        assertFalse(EmergencyManager(preferences).isEmergencyActive.value)

        // A second run is a no-op, including after state is legitimately written again —
        // this is what stops the periodic SyncWorker process restart from re-clearing.
        preferences.edit().putBoolean("emergency_triggered_for_ride", true).apply()
        assertFalse("the second run must not clear again", SosStateCleanup.clearOnce(preferences))
        assertTrue(preferences.getBoolean("emergency_triggered_for_ride", false))
    }

    @Test
    fun clearOnce_onACleanInstall_isHarmlessAndStillMarksItself() {
        val preferences = InMemorySharedPreferences()

        assertTrue(SosStateCleanup.clearOnce(preferences))

        assertTrue(preferences.getBoolean("sos_state_cleared_v164", false))
        assertFalse(SosStateCleanup.clearOnce(preferences))
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
