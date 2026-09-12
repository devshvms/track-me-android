package `in`.shvms.trackme.ui.history

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.export.template.ExportTemplates
import `in`.shvms.trackme.theme.TrackMeTheme
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SCOPE_1.8.9 §12 R3 — the template strip is a fixed-width control, so localizing into it is a layout
 * change (`EXPORT_SHARE_CONTRACTS.md` §3c). Every catalog's names are laid out in the real strip card:
 * at fontScale 1.15 a name may take its second line only at a space, never by splitting a word, and
 * at 1.0 it must leave 10 % of the card spare.
 *
 * The screen is wide so the lazy strip composes all five cards rather than the ones a phone shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1400dp-h2400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplateStripLabelFitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val languages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    private val support = ExportTemplatesSupport(
        available = ExportTemplates.all.map { it.id },
        render = { _, _, _, _ -> null },
    )

    /** Each catalog's names, keyed for the failure message, with the layout the strip gave them. */
    private fun layouts(fontScale: Float): List<Pair<String, TextLayoutResult>> {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TrackMeTheme {
                    Column {
                        languages.forEach { code ->
                            TemplateStrip(support, ExportTemplateId.TRACE, true, ExportTemplateChoice(), getAppStrings(code)) {}
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return languages.flatMap { code ->
            val strings = getAppStrings(code)
            ExportTemplates.all.map { templateName(it.id, strings) }.flatMap { name ->
                val nodes = composeRule.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("$code \"$name\" is in the strip", nodes.isNotEmpty())
                nodes.map { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    "$code \"$name\"" to results.single()
                }
            }
        }
    }

    private fun TextLayoutResult.widestLine(): Float = (0 until lineCount).maxOf { getLineRight(it) - getLineLeft(it) }

    private fun TextLayoutResult.splitsAWord(): Boolean {
        val text = layoutInput.text.text
        return (0 until lineCount - 1).any { line ->
            val end = getLineEnd(line)
            end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
        }
    }

    @Test
    fun `at fontScale 1_15 every name fits its card without splitting a word`() {
        // Measured, not read from `hasVisualOverflow`: a centred label is laid out at the card's width
        // and sized to its text, which reports a width overflow for "Trace".
        layouts(1.15f).forEach { (label, layout) ->
            assertTrue("$label is wider than the card", layout.widestLine() <= layout.layoutInput.constraints.maxWidth)
            assertTrue("$label is cut short", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            assertTrue("$label is split mid-word", !layout.splitsAWord())
        }
    }

    @Test
    fun `at fontScale 1_0 every name leaves ten percent of the card spare`() {
        layouts(1.0f).forEach { (label, layout) ->
            val card = layout.layoutInput.constraints.maxWidth
            val clearance = 1f - layout.widestLine() / card
            assertTrue("$label leaves ${(clearance * 100).toInt()} % of the card; needs 10 %", clearance >= 0.10f)
        }
    }
}
