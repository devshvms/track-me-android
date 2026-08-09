package `in`.shvms.trackme.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every camera move goes through the guarded helpers — v1.7.1 Crashlytics fix.
 *
 * The production crash was `NullPointerException: CameraUpdateFactory is not initialized`, fatal,
 * reported against `HomeScreen`. `CameraUpdateFactory` is a façade over a delegate the Maps SDK
 * installs when it loads, so any factory call that wins the race against map initialisation kills
 * the process.
 *
 * A type cannot express this — `CameraUpdateFactory.newLatLngZoom(...)` is a perfectly ordinary
 * static call, and the four map screens each have a plausible reason to write one. So the
 * invariant is enforced by reading the source, which is also the only thing that catches the
 * *next* map screen someone adds.
 *
 * Deliberately not a Robolectric test of [animateSafely] itself: reaching the failure would mean
 * driving a real `GoogleMap` into a half-initialised state, and a test that mocks the delegate
 * away proves nothing about the crash. What can actually regress is a bare call site, and that is
 * exactly what this reads.
 */
class MapCameraGuardTest {

    private val mapScreens = listOf(
        "ui/home/HomeScreen.kt",
        "ui/history/RideDetailScreen.kt",
        "ui/history/MultiRideCompareScreen.kt",
    )

    /** Source with comments stripped — they name the forbidden calls while explaining them. */
    private fun sourceOf(relative: String): String =
        sourceFile(relative).readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")

    @Test
    fun `no screen calls animate or move with a camera update directly`() {
        // The bare forms. `animate(CameraUpdateFactory…)` evaluates the factory as an argument, so
        // the exception is thrown before animate() is ever entered — which is why wrapping the
        // *call* rather than the *factory* would not have fixed anything.
        val bare = Regex("""\.(animate|move)\s*\(\s*CameraUpdateFactory""")
        for (screen in mapScreens) {
            val hits = bare.findAll(sourceOf(screen)).count()
            assertEquals(
                "$screen calls CameraUpdateFactory directly — use animateSafely/moveSafely, or " +
                    "a slow device racing map init will crash the process",
                0,
                hits,
            )
        }
    }

    @Test
    fun `every screen that touches the factory imports a guarded helper`() {
        for (screen in mapScreens) {
            val source = sourceOf(screen)
            if (!source.contains("CameraUpdateFactory")) continue
            assertTrue(
                "$screen uses CameraUpdateFactory but imports neither animateSafely nor moveSafely",
                source.contains("ui.components.animateSafely") ||
                    source.contains("ui.components.moveSafely"),
            )
        }
    }

    @Test
    fun `the helpers build the update inside the guard`() {
        // The whole fix. A helper taking an already-constructed CameraUpdate would be handed the
        // exception instead of catching it, and would look correct at every call site.
        val helpers = sourceOf("ui/components/MapCamera.kt")
        assertTrue(
            "animateSafely must take a () -> CameraUpdate lambda, not a CameraUpdate",
            helpers.contains("fun CameraPositionState.animateSafely") &&
                helpers.contains("update: () -> CameraUpdate"),
        )
        assertTrue(
            "moveSafely must take a () -> CameraUpdate lambda, not a CameraUpdate",
            Regex("""fun CameraPositionState\.moveSafely\(update: \(\) -> CameraUpdate\)""")
                .containsMatchIn(helpers),
        )
    }

    @Test
    fun `animateSafely rethrows cancellation`() {
        // Swallowing CancellationException in a suspend function breaks structured concurrency:
        // leaving composition mid-animation is normal, and the caller's scope has to see its own
        // cancellation. A blanket `catch (e: Exception)` here would silently hold scopes open.
        val helpers = sourceOf("ui/components/MapCamera.kt")
        assertTrue(
            "animateSafely must rethrow CancellationException rather than absorb it",
            helpers.contains("catch (cancellation: CancellationException)") &&
                helpers.contains("throw cancellation"),
        )
    }

    @Test
    fun `the Maps SDK is initialised at startup`() {
        // The root-cause half. Without it the guards turn every early camera move into a silent
        // no-op, which is not a crash but is still a map that ignores the first tap.
        val app = sourceOf("TrackMeApp.kt")
        assertTrue(
            "TrackMeApp must call MapsInitializer.initialize() so the factory exists before any " +
                "screen reaches for it",
            app.contains("MapsInitializer.initialize("),
        )
    }

    private fun sourceFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
