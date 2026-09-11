package `in`.shvms.trackme.ui.history

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.R
import `in`.shvms.trackme.domain.export.template.AwardFacts
import `in`.shvms.trackme.domain.export.template.AwardText
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.ExportTemplates
import `in`.shvms.trackme.domain.export.template.FigureRole
import `in`.shvms.trackme.domain.export.template.LightPhase
import `in`.shvms.trackme.domain.export.template.TemplateContent
import `in`.shvms.trackme.domain.export.template.TemplateFigure
import `in`.shvms.trackme.domain.export.template.TemplateRenderer
import `in`.shvms.trackme.domain.processor.RouteCoordinate
import `in`.shvms.trackme.domain.stats.RevealKind
import `in`.shvms.trackme.theme.TrackMeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SCOPE_1.8.9 §9 — the Templates tab, composed for real and captured as an image, on the JVM.
 *
 * This stands in for the device check the host cannot run: the Android emulator needs 5 GB of free
 * RAM and this machine has about 2. The Templates stage is a rendered bitmap, not a live map, so
 * the whole tab composes without Google Play services. The route is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportTemplatesTabScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val typeface: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(ApplicationProvider.getApplicationContext(), R.font.inter_variable) }
            .getOrNull() ?: Typeface.DEFAULT
    }

    private fun content(template: ExportTemplateId): TemplateContent {
        val run = List(240) { index ->
            val t = (0.06 + index / 239.0 * 0.88) * 2 * PI
            RouteCoordinate(12.97 + 0.0125 * sin(t) + 0.0045 * sin(3 * t), 77.59 + 0.017 * cos(t) - 0.004 * cos(2 * t))
        }
        return TemplateContent(
            runs = listOf(run), joins = emptyList(), runIntensities = null,
            heroValue = "12.4", heroUnit = "km", heroUnitLong = "KILOMETRES",
            figures = listOf(
                TemplateFigure(FigureRole.DURATION, "DURATION", "48min"),
                TemplateFigure(FigureRole.ELEVATION, "ELEVATION", "312 m"),
                TemplateFigure(FigureRole.EFFORT, "AVG SPEED", "15.5 km/h"),
            ),
            dateLine = "SAT 6 SEP · 06:14 · CYCLING", placeLine = null, link = "https://trackme.shvms.in/r/abc123def456",
            elevation = null, elevationLabel = null, splits = emptyList(), splitsLabel = null,
            fastestSegment = null, fastestLabel = null,
            award = if (template == ExportTemplateId.AWARD) {
                AwardText(AwardFacts(RevealKind.FIRST_RIDE, null, null, 1f), "1st", "RIDE", "First ride", null)
            } else null,
            light = LightPhase.DAWN, lightLine = "FIRST LIGHT · 06:14",
        )
    }

    private fun support(onRender: () -> Unit = {}) = ExportTemplatesSupport(
        available = ExportTemplates.all.map { it.id },
        render = { choice, canvas, _, widthPx ->
            onRender()
            TemplateRenderer.render(choice.id, canvas, content(choice.id), typeface, widthPx)
        },
    )

    /** Lets the background renders land and the tree settle; the stage renders off the main thread. */
    private fun settle() = repeat(24) {
        Thread.sleep(150)
        composeRule.waitForIdle()
    }

    @Test
    fun `the Templates tab opens first and composes without ever asking for the live map`() {
        var renders = 0
        var liveMapRequested = false
        composeRule.setContent {
            TrackMeTheme(themeMode = 2) {
                ExportPreviewDialog(
                    title = "Share ride",
                    initialRatio = Pair(1080, 1920),
                    templates = support { renders++ },
                    onDismiss = {},
                    onShare = {},
                    onSave = {},
                ) { _, _ -> liveMapRequested = true }
            }
        }
        settle()

        composeRule.onNodeWithText("Templates").assertIsSelected()
        composeRule.onNodeWithText("Custom").assertIsNotSelected()
        composeRule.onNodeWithText("Trace").assertExists()
        composeRule.onNodeWithText("Instrument").assertExists()
        assertFalse("the Templates stage must not compose the host's live map", liveMapRequested)
        assertTrue("the stage and the strip's thumbnails are all rendered, not placeholders", renders >= 6)

        // Drawn straight from the dialog's decor view: `captureToImage` waits for a window frame that
        // Robolectric never delivers to dialog windows. Native graphics makes these real pixels.
        val decor = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val capture = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(capture))
        val sampled = (0 until capture.width step 24).flatMap { x -> (0 until capture.height step 24).map { y -> capture.getPixel(x, y) } }
        assertTrue("the capture is a picture, not a blank window", sampled.toSet().size > 20)
        File("build/template-renders").mkdirs()
        FileOutputStream(File("build/template-renders", "android_templates_tab.png")).use {
            capture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `switching to Custom brings back today's preview`() {
        var liveMapRequested = false
        composeRule.setContent {
            TrackMeTheme(themeMode = 2) {
                ExportPreviewDialog(
                    title = "Share ride",
                    initialRatio = Pair(1080, 1920),
                    templates = support(),
                    onDismiss = {},
                    onShare = {},
                ) { modifier, _ ->
                    liveMapRequested = true
                    Box(modifier)
                }
            }
        }
        settle()
        composeRule.onAllNodesWithText("Custom").onLast().performClick()
        composeRule.waitForIdle()
        assertTrue(liveMapRequested)
        composeRule.onNodeWithText("Custom").assertIsSelected()
    }

    @Test
    fun `the ratio chips keep one order whichever template is chosen`() {
        composeRule.setContent {
            TrackMeTheme(themeMode = 2) {
                ExportPreviewDialog(
                    title = "Share ride",
                    initialRatio = Pair(1080, 1920),
                    templates = support(),
                    onDismiss = {},
                    onShare = {},
                ) { modifier, _ -> Box(modifier) }
            }
        }
        settle()
        // The Instrument's spec lists 4:5 first; the row must not follow it and reshuffle.
        composeRule.onNodeWithText("Instrument").performClick()
        composeRule.waitForIdle()
        val lefts = listOf("9:16", "4:5", "1:1").map { composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
    }

    @Test
    fun `a host that passes no templates keeps the dialog exactly as it was`() {
        composeRule.setContent {
            ExportPreviewDialog(title = "Preview", initialRatio = Pair(1, 1), onDismiss = {}, onShare = {}) { modifier, _ -> Box(modifier) }
        }
        composeRule.onNodeWithText("Templates").assertDoesNotExist()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()
    }
}
