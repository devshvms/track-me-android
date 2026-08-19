package `in`.shvms.trackme.ui.localization

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsNotificationTest {
    private val supportedLanguages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun notificationContentIsPresentAndFormatSafeForEveryLanguage() {
        supportedLanguages.forEach { language ->
            val strings = getAppStrings(language)

            listOf(
                strings.notifTrackingTitle,
                strings.notifTrackingText,
                strings.notifTrackingMetrics,
                strings.notifTrackingPaused,
                strings.notifTrackingGpsSearching,
                // notifAutoSplit*/notifLongRide* were deleted with the auto-split itself
                // (SCOPE_1.7.3 §2(a)): chunked upload removed the 1 MiB ceiling the split existed
                // to defend, so the notifications had nothing left to announce.
                strings.notifStorageLowTitle,
                strings.notifStorageLowText
            ).forEach { value ->
                assertTrue("$language has a blank notification string", value.isNotBlank())
            }

            val metrics = String.format(Locale.US, strings.notifTrackingMetrics, "00:01:00", "1.0 km", "3.6 km/h")
            val paused = String.format(Locale.US, strings.notifTrackingPaused, "00:01:00")
            assertTrue("$language metrics placeholder drifted", metrics.contains("00:01:00"))
            assertTrue("$language metrics distance placeholder drifted", metrics.contains("1.0 km"))
            assertTrue("$language metrics speed placeholder drifted", metrics.contains("3.6 km/h"))
            assertTrue("$language paused placeholder drifted", paused.contains("00:01:00"))
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
