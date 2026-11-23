package `in`.shvms.trackme.ui.live

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import `in`.shvms.trackme.data.remote.LiveShareState
import `in`.shvms.trackme.domain.home.HomeDashboardSummary
import `in`.shvms.trackme.domain.live.LiveShareFrequencySliderPolicy
import `in`.shvms.trackme.testing.createRobolectricComposeRule
import `in`.shvms.trackme.ui.home.HomeDashboardScreen
import `in`.shvms.trackme.ui.home.components.LiveShareSetupSheetContent
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.settings.LiveShareFrequencySliderSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = "w411dp-h891dp",
)
class Task377And378ComposeTest {

    @get:Rule
    val composeRule = createRobolectricComposeRule()

    @Test
    fun sliderCoversAllSixStepsPersistsToExistingPrefKeyAndSurvivesReopen() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(LiveShareFrequencySliderPolicy.PREF_KEY, 15).commit()

        val strings = AppStrings()
        val committedValues = mutableListOf<Int>()
        var committedSeconds by mutableIntStateOf(
            prefs.getInt(LiveShareFrequencySliderPolicy.PREF_KEY, LiveShareFrequencySliderPolicy.DEFAULT_SECONDS),
        )

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    LiveShareFrequencySliderSection(
                        committedSeconds = committedSeconds,
                        onCommitSeconds = { seconds ->
                            committedSeconds = seconds
                            committedValues.add(seconds)
                            prefs.edit().putInt(LiveShareFrequencySliderPolicy.PREF_KEY, seconds).apply()
                        },
                        strings = strings,
                    )
                }
            }
        }

        // Seeded 15s default displays cleanly on first render.
        composeRule.onNodeWithTag("live_share_frequency_value").assertTextEquals("15 sec")
        composeRule.onNodeWithText("Less battery use").assertIsDisplayed()
        composeRule.onNodeWithText("More frequent updates").assertIsDisplayed()
        composeRule.onNodeWithText("Shorter intervals can use more battery.").assertIsDisplayed()

        // Screen-reader SetProgress across every position left-to-right (300s -> 5s).
        val expectedLabels = listOf("5 min", "1 min", "30 sec", "15 sec", "10 sec", "5 sec")
        LiveShareFrequencySliderPolicy.STEPS_SECONDS.forEachIndexed { index, expectedSeconds ->
            composeRule.onNodeWithTag("live_share_frequency_slider")
                .performSemanticsAction(SemanticsActions.SetProgress) { action -> action(index.toFloat()) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("live_share_frequency_value").assertTextEquals(expectedLabels[index])
            assertEquals(expectedSeconds, prefs.getInt(LiveShareFrequencySliderPolicy.PREF_KEY, -1))
        }

        assertEquals(LiveShareFrequencySliderPolicy.STEPS_SECONDS, committedValues)
    }

    @Test
    fun sliderRendersCleanlyOnNarrowPhoneWithLargeFontScale() {
        val strings = AppStrings()
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppStrings provides strings,
                    LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
                ) {
                    Box(Modifier.width(320.dp)) {
                        LiveShareFrequencySliderSection(
                            committedSeconds = 300,
                            onCommitSeconds = {},
                            strings = strings,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("live_share_frequency_value").assertTextEquals("5 min")
        composeRule.onNodeWithTag("live_share_frequency_slider").assertIsDisplayed()
    }

    @Test
    fun homeSharingCardsMatchWidthHeightAndPlaceTrustedWatchersBelow() {
        val strings = AppStrings()
        var openedCommunity = 0
        var openedLiveSharing = 0
        var openedWatchers = 0

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    HomeDashboardScreen(
                        summary = HomeDashboardSummary.empty(0L),
                        routePoints = emptyList(),
                        isSummaryResolved = true,
                        isReconciling = false,
                        groupActive = false,
                        groupMemberCount = 0,
                        syncNeedsAction = false,
                        isOffline = false,
                        locationPermissionRevoked = false,
                        imperial = false,
                        onOpenRecent = { _, _ -> },
                        onOpenHistory = {},
                        onOpenCommunity = { openedCommunity++ },
                        onOpenProgress = {},
                        onOpenGroupMap = {},
                        onCreateGroup = {},
                        onJoinGroup = {},
                        onOpenSettings = {},
                        onDismissPermissionNotice = {},
                        onOpenLiveSharing = { openedLiveSharing++ },
                        trustedWatcherCount = 3,
                        onManageWatchers = { openedWatchers++ },
                    )
                }
            }
        }

        val groupBounds = composeRule.onNodeWithTag("home_group_ride_card").getUnclippedBoundsInRoot()
        val liveBounds = composeRule.onNodeWithTag("home_live_sharing_card").getUnclippedBoundsInRoot()
        val watchersBounds = composeRule.onNodeWithTag("home_trusted_watchers_shortcut").getUnclippedBoundsInRoot()
        val groupCtaBounds = composeRule.onNodeWithTag("home_group_ride_cta").getUnclippedBoundsInRoot()
        val liveCtaBounds = composeRule.onNodeWithTag("home_live_sharing_cta").getUnclippedBoundsInRoot()

        // Side-by-side equal width and equal height.
        assertTrue(
            "Group (${groupBounds.width}) and Live (${liveBounds.width}) cards should have equal width",
            abs(groupBounds.width.value - liveBounds.width.value) <= 1.5f,
        )
        assertTrue(
            "Group (${groupBounds.height}) and Live (${liveBounds.height}) cards should have equal height",
            abs(groupBounds.height.value - liveBounds.height.value) <= 1.5f,
        )
        assertTrue(
            "Primary buttons should meet 48dp minimum target height",
            groupCtaBounds.height >= 48.dp && liveCtaBounds.height >= 48.dp,
        )
        assertTrue(
            "Trusted Watchers shortcut should sit directly below the sharing pair",
            watchersBounds.top >= groupBounds.bottom && watchersBounds.top >= liveBounds.bottom,
        )
        assertTrue(
            "Trusted Watchers shortcut should meet 48dp minimum target height",
            watchersBounds.height >= 48.dp,
        )

        composeRule.onNodeWithText("Trusted watchers (3)").assertIsDisplayed()
        composeRule.onNodeWithTag("home_group_ride_cta").performClick()
        composeRule.onNodeWithTag("home_live_sharing_cta").performClick()
        composeRule.onNodeWithTag("home_trusted_watchers_shortcut").performClick()
        composeRule.runOnIdle {
            assertEquals(1, openedCommunity)
            assertEquals(1, openedLiveSharing)
            assertEquals(1, openedWatchers)
        }
    }

    @Test
    fun liveShareSetupSheetDefaultsCollapsedTo30MinAndAllowsChangingLimit() {
        val strings = AppStrings()
        var startedDurationMinutes: Int? = null

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    LiveShareSetupSheetContent(
                        liveShareState = LiveShareState(),
                        isAuthenticated = true,
                        groupActive = false,
                        trustedWatchers = emptyList(),
                        stopsWithRide = true,
                        onDismiss = {},
                        onStartDirectShare = { startedDurationMinutes = it },
                        onStopShare = {},
                        onShareLink = {},
                        onCopyLink = {},
                        onInviteWatcher = {},
                        onToggleWatcherAutoNotify = { _, _ -> },
                        onRemoveWatcher = {},
                    )
                }
            }
        }

        // Starts collapsed on 30 min with choices hidden.
        composeRule.onNodeWithTag("live_share_time_limit_summary")
            .assertTextEquals("Sharing time limit · 30 min")
        composeRule.onNodeWithTag("live_share_duration_120").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Sharing stops automatically at this limit. You can stop it sooner. Ending your ride also stops sharing.",
        ).assertIsDisplayed()

        // Tap Change -> options appear -> pick 2 hr -> summary updates visibly -> Start passes 120.
        composeRule.onNodeWithTag("live_share_time_limit_change").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_share_duration_120").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_share_time_limit_summary")
            .assertTextEquals("Sharing time limit · 2 hr")

        composeRule.onNodeWithText(strings.homeShareLiveLocation).performClick()
        composeRule.runOnIdle {
            assertEquals(120, startedDurationMinutes)
        }
    }

    @Test
    fun liveShareSetupSheetOmitsRideEndCopyWhenNotRideLinked() {
        val strings = AppStrings()
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppStrings provides strings) {
                    LiveShareSetupSheetContent(
                        liveShareState = LiveShareState(),
                        isAuthenticated = true,
                        groupActive = false,
                        trustedWatchers = emptyList(),
                        stopsWithRide = false,
                        onDismiss = {},
                        onStartDirectShare = {},
                        onStopShare = {},
                        onShareLink = {},
                        onCopyLink = {},
                        onInviteWatcher = {},
                        onToggleWatcherAutoNotify = { _, _ -> },
                        onRemoveWatcher = {},
                    )
                }
            }
        }

        val expected = "Sharing stops automatically at this limit. You can stop it sooner."
        composeRule.onNodeWithTag("live_share_expiry_explanation").assertTextEquals(expected)
        assertFalse(expected.lowercase().contains("pause"))
    }
}
