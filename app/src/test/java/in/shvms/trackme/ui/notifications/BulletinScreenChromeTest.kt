package `in`.shvms.trackme.ui.notifications

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import `in`.shvms.trackme.domain.bulletin.BulletinCopy
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import `in`.shvms.trackme.ui.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCOPE_1.8.9 — the bulletin screen's chrome, reported from a 1.8.8 device build.
 *
 * Three separate defects arrived as one screenshot: the screen had no title, no way back, and —
 * the part that actually made it look broken — no window insets, so the first row rendered
 * underneath the status bar and read as unformatted text floating at the top of the display.
 *
 * ### Why this is a test and not a look
 *
 * It is a visual bug, and the honest way to check a visual bug is to look at it. The Android
 * emulator needs 5,120 MB and this host has had roughly 290 MB free, so that look did not happen
 * here. What a Robolectric render *can* prove is the structure whose absence caused every symptom:
 * that an app bar exists, carries the screen's title, and offers a back affordance. A screen with
 * those three things cannot reproduce the reported state, whatever its pixels look like.
 *
 * The pixel check still belongs on a device, and is recorded as outstanding rather than implied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp")
class BulletinScreenChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val strings = AppStrings()

    @Test
    fun `the screen carries a title, so it is not a bare list under the status bar`() {
        composeRule.setContent {
            BulletinScaffold(
                entries = emptyList(),
                imperial = false,
                strings = strings,
                copyStrings = AppStringsBulletinCopy(strings),
                onClear = {},
            )
        }
        // The title appears in the app bar. Before this fix nothing rendered it at all.
        composeRule.onNodeWithText(strings.bulletinTitle).assertIsDisplayed()
    }

    @Test
    fun `there is a way back out`() {
        var backPresses = 0
        composeRule.setContent {
            BulletinScaffold(
                onBack = { backPresses += 1 },
                entries = emptyList(),
                imperial = false,
                strings = strings,
                copyStrings = AppStringsBulletinCopy(strings),
                onClear = {},
            )
        }

        composeRule.onNodeWithContentDescription(strings.back).performClick()

        assertEquals(
            "the app bar's navigation icon must invoke onBack — a pushed route with no way back " +
                "strands the reader on a dead end",
            1,
            backPresses,
        )
    }

    /**
     * The Settings row previewed `bulletinEmpty` unconditionally, so it claimed there was nothing
     * to report while the feed behind it held rows. This pins the derivation the row now uses:
     * the newest entry has a renderable headline, and it is not the empty-state sentence.
     */
    @Test
    fun `the newest entry yields a headline the settings row can preview`() {
        val entry = BulletinEntry(
            id = "ride-saved:1",
            kind = BulletinKind.RIDE_SAVED,
            createdAtMillis = 1_760_000_000_000,
            facts = mapOf(
                BulletinEntry.FACT_ENDED_AT_MILLIS to "1760000000000",
                BulletinEntry.FACT_DISTANCE_METERS to "2800.0",
            ),
        )

        val row = BulletinCopy.render(
            entry.withFormattedFacts(imperial = false),
            AppStringsBulletinCopy(strings),
        )

        assertNotNull("a saved-ride entry must render, or the row falls back to the empty text", row)
        assertEquals(strings.rideSavedTitle, row!!.title)
        assertEquals(
            "the preview must not be the empty-state sentence when an entry exists",
            false,
            row.title == strings.bulletinEmpty,
        )
    }
}
