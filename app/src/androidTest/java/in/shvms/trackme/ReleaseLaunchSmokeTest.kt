package `in`.shvms.trackme

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Minimal release smoke test: catches startup failures after R8/resource shrinking. */
@RunWith(AndroidJUnit4::class)
class ReleaseLaunchSmokeTest {

    @Test
    fun mainActivityReachesResumedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
