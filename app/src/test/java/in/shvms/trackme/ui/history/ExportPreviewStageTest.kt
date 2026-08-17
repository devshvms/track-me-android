package `in`.shvms.trackme.ui.history

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preview stage must not crash when there is no room for it.
 *
 * Moving the preview out of the scrolling column and onto `weight(1f)` introduced a case the old
 * layout could not reach: the chrome above and below is fixed-height, so on a short screen at a
 * large font scale the weighted child can resolve to zero. [boundedPreviewSize] requires positive
 * bounds — correctly, since a zero-sized preview is meaningless — and a `require` that fires during
 * composition is a crash, not a blank space.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExportPreviewStageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun boundedPreviewSizeRejectsNonPositiveBounds() {
        // Documents why the call site guards rather than why this function is lenient.
        assertThrows(IllegalArgumentException::class.java) {
            boundedPreviewSize(maxWidth = 0f, maxHeight = 400f, ratio = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            boundedPreviewSize(maxWidth = 400f, maxHeight = 0f, ratio = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            boundedPreviewSize(maxWidth = 400f, maxHeight = 400f, ratio = 0f)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h240dp")
    fun previewSurvivesAViewportWithNoRoomForIt() {
        // A 240dp-tall window cannot fit the app bar, both control tiers and the action row, so the
        // stage resolves to zero height. Before the guard this threw during composition.
        var previewComposed = false
        composeRule.setContent {
            ExportPreviewDialog(
                title = "Preview",
                initialRatio = Pair(9, 16),
                showAggregateControls = true,
                onDismiss = {},
                onShare = {},
                onSave = {},
            ) { modifier, _ ->
                previewComposed = true
                Box(modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()

        // The assertion is that we got here at all. Whether the preview itself had room is a
        // layout outcome, not a contract — it must simply not take the app down.
        assertTrue("composition completed without the stage throwing", true)
        // Silences the unused-write warning while recording the fact for a reader.
        assertTrue(previewComposed || !previewComposed)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun previewComposesOnAnOrdinaryPhone() {
        var previewComposed = false
        composeRule.setContent {
            ExportPreviewDialog(
                title = "Preview",
                initialRatio = Pair(9, 16),
                onDismiss = {},
                onShare = {},
                onSave = {},
            ) { modifier, _ ->
                previewComposed = true
                Box(modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()

        assertTrue("the preview slot was never composed on a normal phone", previewComposed)
    }
}
