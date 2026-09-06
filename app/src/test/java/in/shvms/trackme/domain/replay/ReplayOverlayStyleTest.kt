package `in`.shvms.trackme.domain.replay

import `in`.shvms.trackme.ui.history.OverlayContent
import `in`.shvms.trackme.ui.history.StatsOverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TASK-305 — the video must honour the panel settings the still export honours.
 *
 * The defect: the two artifacts are made from one preview, with one set of settings, one button
 * apart, and the video ignored all of the panel settings. Someone who picked
 * [StatsOverlayStyle.None] — *"No panel. The map alone."* — got a clean image and a video with a
 * stats panel welded on. That is the setting most likely to be chosen for something a person
 * actually intends to post.
 *
 * The recorded diagnosis was different and wrong, which is worth keeping in the test file that
 * replaces it: `ReplayExportAction.kt` carried a comment claiming the *theme and labels* were
 * dropped. They were not — `settings.mapStyle(context)` composes both into one `MapStyleOptions`
 * and the call site already passed it. Only the burned-in chrome was missing.
 */
class ReplayOverlayStyleTest {

    @Test
    fun `the default overlay draws nothing rather than something wrong`() {
        // A call site that forgets to pass the user's choice now produces a clean frame. The old
        // default was the hard-coded panel, so forgetting produced an export that contradicted the
        // preview — silently, and only in the file the user shared.
        val overlay = ReplayOverlay()
        assertFalse(overlay.drawsPanel)
        assertEquals(StatsOverlayStyle.None, overlay.statsStyle)
    }

    @Test
    fun `None means no panel even when there are figures to show`() {
        val overlay = ReplayOverlay(
            statsStyle = StatsOverlayStyle.None,
            figures = listOf("12.3 km", "48min"),
        )
        assertFalse("the map alone means the map alone", overlay.drawsPanel)
        assertNull(overlay.statsStyle.rect(OverlayContent(overlay.figures)))
    }

    @Test
    fun `a placement with no figures draws no empty box`() {
        // The still export guards this as `statsOverlay.isVisible && !content.isEmpty`. An empty
        // panel is a grey rectangle sitting on the route for no reason.
        val overlay = ReplayOverlay(statsStyle = StatsOverlayStyle.BottomBar, figures = emptyList())
        assertFalse(overlay.drawsPanel)
    }

    @Test
    fun `every visible placement with figures draws a panel`() {
        listOf(StatsOverlayStyle.BottomBar, StatsOverlayStyle.TopLeft, StatsOverlayStyle.TopRight)
            .forEach { style ->
                val overlay = ReplayOverlay(statsStyle = style, figures = listOf("12.3 km"))
                assertTrue(style.name, overlay.drawsPanel)
                assertNotNull(style.name, style.rect(OverlayContent(overlay.figures)))
            }
    }

    @Test
    fun `the video uses the same geometry as the still, not its own`() {
        // The whole point of routing through StatsOverlayStyle rather than positioning the panel
        // in the renderer: one rectangle definition, shared by the Compose preview, the image
        // exporter and now the video. Two of those three had already drifted once (§8.1).
        val content = OverlayContent(listOf("Sep 06, 2026", "48min", "12.3 km"))
        StatsOverlayStyle.entries.forEach { style ->
            val overlay = ReplayOverlay(statsStyle = style, figures = content.figures)
            assertEquals(
                "the renderer must not derive its own rectangle for $style",
                style.rect(content),
                overlay.statsStyle.rect(OverlayContent(overlay.figures)),
            )
        }
    }

    @Test
    fun `the theme reaches the burned-in chrome, not only the basemap`() {
        // The basemap already honoured darkTheme through MapStyleOptions. The scrim, the panel and
        // the text did not, so a light export produced a dark video.
        assertTrue(ReplayOverlay().darkTheme)
        assertFalse(ReplayOverlay(darkTheme = false).darkTheme)
    }

    @Test
    fun `the production call site passes the user's choices`() {
        // Read from source, because the failure is silent: every default here is valid Kotlin that
        // compiles, runs, and quietly exports a frame the user did not ask for. Nothing crashes.
        val source = source("ui/history/ReplayExportAction.kt")
        // Terminated on the closing paren at the statement's own indentation, not the first ")"
        // in the block: `personaLabel = strings.personaLabel(persona)` contains one, and stopping
        // there made this assertion pass while reading a single argument.
        val construction = source
            .substringAfter("val overlay = ReplayOverlay(")
            .substringBefore("\n    )")
        assertTrue(
            "could not isolate the ReplayOverlay construction — did the call site move?",
            construction.isNotBlank() && construction.lines().size >= 5,
        )

        listOf("statsStyle", "figures", "darkTheme", "personaLabel", "imperialUnits").forEach {
            assertTrue(
                "ReplayExportAction no longer passes $it — the video will silently ignore it",
                construction.contains(it),
            )
        }
        assertTrue(
            "the video must take its figures from buildOverlayContent, not derive its own",
            source.contains("buildOverlayContent("),
        )
    }

    @Test
    fun `the replay no longer carries its own formatters`() {
        // These were the mechanism by which the MP4 could say something the preview did not, and
        // they had already diverged: the video rendered 02:44:47 where the image rendered
        // 2hr 44min, because only the image went through compactDuration.
        val source = source("domain/replay/ReplayExport.kt")
        assertFalse(source.contains("internal fun formatReplayDistance"))
        assertFalse(source.contains("internal fun formatReplayDuration"))
    }

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
