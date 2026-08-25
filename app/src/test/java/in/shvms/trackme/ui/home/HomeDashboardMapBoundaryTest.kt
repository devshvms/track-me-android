package `in`.shvms.trackme.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardMapBoundaryTest {
    @Test fun `dashboard source has no map SDK or permission request`() {
        val dashboard = read("ui/home/HomeDashboardScreen.kt")
        assertFalse(dashboard.contains("GoogleMap("))
        assertFalse(dashboard.contains("com.google.android.gms.maps"))
        assertFalse(dashboard.contains("ACCESS_FINE_LOCATION"))
    }

    @Test fun `Home chooses dashboard before the only GoogleMap call`() {
        val home = read("ui/home/HomeScreen.kt")
        val dashboardBranch = home.indexOf("if (presentationMode == HomePresentationMode.IDLE_DASHBOARD)")
        val map = home.indexOf("GoogleMap(")
        assertTrue(dashboardBranch >= 0)
        assertTrue(map > dashboardBranch)
        assertTrue(home.contains("isMyLocationEnabled = hasLocationPermission"))
        assertTrue(home.contains("enabled = hasLocationPermission"))
        assertFalse("the removed radial launcher must not remain callable", home.contains("RadialStartRideButton("))
    }

    @Test fun `idle location lookup is gated on map presentation`() {
        val home = read("ui/home/HomeScreen.kt")
        assertTrue(home.contains("LaunchedEffect(hasLocationPermission, shouldConstructMap)"))
        assertTrue(home.contains("shouldConstructMap && hasLocationPermission"))
    }

    private fun read(relative: String): String {
        var directory: File? = File("").absoluteFile
        val path = "app/src/main/java/in/shvms/trackme/$relative"
        while (directory != null) {
            File(directory, path).takeIf(File::exists)?.let { return it.readText() }
            File(directory, path.removePrefix("app/")).takeIf(File::exists)?.let { return it.readText() }
            directory = directory.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
