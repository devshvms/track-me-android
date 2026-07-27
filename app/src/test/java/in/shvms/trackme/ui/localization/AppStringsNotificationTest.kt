package `in`.shvms.trackme.ui.localization

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsNotificationTest {
    private val supportedLanguages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun notificationAndSosContentIsPresentAndFormatSafeForEveryLanguage() {
        supportedLanguages.forEach { language ->
            val strings = getAppStrings(language)

            listOf(
                strings.notifTrackingTitle,
                strings.notifTrackingText,
                strings.notifAutoSplitTitle,
                strings.notifAutoSplitText,
                strings.notifLongRideTitle,
                strings.notifLongRideText,
                strings.notifStorageLowTitle,
                strings.notifStorageLowText,
                strings.sosNotifTitle,
                strings.sosNotifSetupFailure,
                strings.sosNotifFailed,
                strings.sosNotifSubmitted,
                strings.sosNotifPartial
            ).forEach { value ->
                assertTrue("$language has a blank notification string", value.isNotBlank())
            }

            val submitted = String.format(Locale.US, strings.sosNotifSubmitted, 3)
            val partial = String.format(Locale.US, strings.sosNotifPartial, 3, 1)
            assertTrue("$language submitted placeholder drifted", submitted.contains("3"))
            assertTrue("$language partial placeholder drifted", partial.contains("3"))
            assertTrue("$language partial failure placeholder drifted", partial.contains("1"))
        }
    }

    @Test
    fun aggregatePreviewCopyIsPresentForEveryLanguage() {
        supportedLanguages.forEach { language ->
            val strings = getAppStrings(language)
            listOf(
                strings.compareRidesTitle,
                strings.compareRidesShare,
                strings.aggregatePreviewTitle,
                strings.aggregatePreviewShare,
                strings.aggregatePreviewLegend,
                strings.aggregatePreviewSequence,
                strings.hidePlaces,
                strings.showMarkers,
                strings.darkTheme,
                strings.distanceShortLabel,
                strings.durationShortLabel,
                strings.dateShortLabel,
                strings.exportPreviewTitle,
                strings.mapStart,
                strings.mapFinish,
                strings.scrub
            ).forEach { value ->
                assertTrue("$language has blank aggregate preview copy", value.isNotBlank())
            }
        }
    }
}
