package `in`.shvms.trackme.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DebugSettingsTest {
    private val preferences by lazy {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("debug_settings_test", Context.MODE_PRIVATE)
    }

    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `fifth consecutive tap unlocks and resets the sequence`() {
        val unlock = ConsecutiveTapUnlock()

        assertFalse(unlock.registerTap(1_000L))
        assertFalse(unlock.registerTap(1_500L))
        assertFalse(unlock.registerTap(2_000L))
        assertFalse(unlock.registerTap(2_500L))
        assertTrue(unlock.registerTap(3_000L))
        assertFalse(unlock.registerTap(3_500L))
    }

    @Test
    fun `slow or non-monotonic taps restart at one`() {
        val unlock = ConsecutiveTapUnlock()

        unlock.registerTap(1_000L)
        unlock.registerTap(1_500L)
        assertFalse(unlock.registerTap(3_501L))
        assertTrue(unlock.tapCount == 1)
        assertFalse(unlock.registerTap(3_000L))
        assertTrue(unlock.tapCount == 1)
    }

    @Test
    fun `enable persists local debug mode`() {
        DebugSettings.enable(preferences)

        assertTrue(DebugSettings.isEnabled(preferences))
    }

    @Test
    fun `disabling debug mode restores only debug override defaults`() {
        preferences.edit()
            .putBoolean(DebugSettings.MODE_ENABLED_KEY, true)
            .putBoolean(DebugSettings.AUTO_PAUSE_KEY, false)
            .putBoolean(DebugSettings.DISABLE_POST_PROCESSING_KEY, true)
            .putString("app_language", "fr")
            .commit()

        DebugSettings.disableAndReset(preferences)

        assertFalse(DebugSettings.isEnabled(preferences))
        assertTrue(preferences.getBoolean(DebugSettings.AUTO_PAUSE_KEY, false))
        assertFalse(preferences.getBoolean(DebugSettings.DISABLE_POST_PROCESSING_KEY, true))
        assertTrue(preferences.getString("app_language", null) == "fr")
    }
}
