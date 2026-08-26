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

    @Test fun `Home keeps one map host beneath the dashboard`() {
        val home = read("ui/home/HomeScreen.kt")
        val map = home.indexOf("GoogleMap(")
        val dashboard = home.indexOf("HomeDashboardScreen(")
        assertTrue(map >= 0)
        assertTrue(dashboard > map)
        assertTrue(home.contains("isMyLocationEnabled = isInteractiveMap && hasLocationPermission"))
        assertTrue(home.contains("scrollGesturesEnabled = isInteractiveMap"))
        assertTrue(home.contains("zoomGesturesEnabled = isInteractiveMap"))
        assertTrue(home.contains("enabled = hasLocationPermission"))
        assertTrue("the retained radial launcher must remain callable", home.contains("RadialStartRideButton("))
    }

    @Test fun `idle location lookup is gated on interactive map presentation`() {
        val home = read("ui/home/HomeScreen.kt")
        assertTrue(home.contains("LaunchedEffect(hasLocationPermission, isInteractiveMap)"))
        assertTrue(home.contains("isInteractiveMap && hasLocationPermission"))
    }

    @Test fun `unknown dashboard cannot render first-run state`() {
        val dashboard = read("ui/home/HomeDashboardScreen.kt")
        assertTrue(dashboard.contains("val deckResolved = isSummaryResolved"))
        assertTrue(dashboard.contains("!isReconciling || summary.lifetimeActivityCount > 0"))
        assertTrue(dashboard.contains("if (deckResolved)"))
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
