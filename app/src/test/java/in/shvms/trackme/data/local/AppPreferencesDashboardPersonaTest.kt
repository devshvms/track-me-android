package `in`.shvms.trackme.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppPreferencesDashboardPersonaTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `onboarding persona seeds Home before first committed start`() {
        val preferences = AppPreferencesManager(context)
        preferences.setOnboardingPersona(RidePersona.WALK)
        assertEquals(RidePersona.WALK, preferences.lastStartedPersona.value)
    }

    @Test fun `committed start survives process recreation and onboarding cannot overwrite it`() {
        val preferences = AppPreferencesManager(context)
        preferences.setLastStartedPersona(RidePersona.CYCLING)
        preferences.setOnboardingPersona(RidePersona.WALK)
        assertEquals(RidePersona.CYCLING, AppPreferencesManager(context).lastStartedPersona.value)
    }
}
