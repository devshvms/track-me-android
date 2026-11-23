package `in`.shvms.trackme.domain.live

import `in`.shvms.trackme.ui.localization.getAppStrings
import `in`.shvms.trackme.ui.settings.formatLiveShareIntervalShort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveShareFrequencySliderPolicyTest {

    @Test
    fun stepsMatchSixPositionBatteryToFrequencyContract() {
        assertEquals(listOf(300, 60, 30, 15, 10, 5), LiveShareFrequencySliderPolicy.STEPS_SECONDS)
        assertEquals(6, LiveShareFrequencySliderPolicy.STEP_COUNT)
        assertEquals(5, LiveShareFrequencySliderPolicy.MAX_INDEX)
        assertEquals(4, LiveShareFrequencySliderPolicy.SLIDER_INTERNAL_STEPS)
        assertEquals(15, LiveShareFrequencySliderPolicy.DEFAULT_SECONDS)
        assertEquals(3, LiveShareFrequencySliderPolicy.DEFAULT_INDEX)
    }

    @Test
    fun roundTripsEverySupportedStepAndSeededDefault() {
        LiveShareFrequencySliderPolicy.STEPS_SECONDS.forEachIndexed { index, seconds ->
            assertEquals(index, LiveShareFrequencySliderPolicy.indexForSeconds(seconds))
            assertEquals(seconds, LiveShareFrequencySliderPolicy.secondsForIndex(index))
            assertEquals(seconds, LiveShareFrequencySliderPolicy.secondsForSliderValue(index.toFloat()))
        }
        assertEquals(3, LiveShareFrequencySliderPolicy.indexForSeconds(15))
    }

    @Test
    fun nonPositiveAndNonFiniteValuesFallBackToDefault() {
        assertEquals(LiveShareFrequencySliderPolicy.DEFAULT_INDEX, LiveShareFrequencySliderPolicy.indexForSeconds(0))
        assertEquals(LiveShareFrequencySliderPolicy.DEFAULT_INDEX, LiveShareFrequencySliderPolicy.indexForSeconds(-15))
        assertEquals(LiveShareFrequencySliderPolicy.DEFAULT_SECONDS, LiveShareFrequencySliderPolicy.secondsForSliderValue(Float.NaN))
        assertEquals(LiveShareFrequencySliderPolicy.DEFAULT_SECONDS, LiveShareFrequencySliderPolicy.secondsForSliderValue(Float.POSITIVE_INFINITY))
    }

    @Test
    fun legacyNonStandardPositiveValuesSnapToNearestStep() {
        assertEquals(5, LiveShareFrequencySliderPolicy.indexForSeconds(4))
        assertEquals(4, LiveShareFrequencySliderPolicy.indexForSeconds(11))
        assertEquals(3, LiveShareFrequencySliderPolicy.indexForSeconds(16))
        assertEquals(2, LiveShareFrequencySliderPolicy.indexForSeconds(25))
        assertEquals(1, LiveShareFrequencySliderPolicy.indexForSeconds(90))
        assertEquals(0, LiveShareFrequencySliderPolicy.indexForSeconds(600))
    }

    @Test
    fun outOfRangeSliderValuesClampSafely() {
        assertEquals(300, LiveShareFrequencySliderPolicy.secondsForSliderValue(-2f))
        assertEquals(5, LiveShareFrequencySliderPolicy.secondsForSliderValue(99f))
        assertEquals(300, LiveShareFrequencySliderPolicy.secondsForIndex(-1))
        assertEquals(5, LiveShareFrequencySliderPolicy.secondsForIndex(10))
    }

    @Test
    fun allSevenLocalesProvideTask377And378CopyWithoutPercentOrPauseClaims() {
        val locales = listOf("en", "es", "fr", "de", "hi", "ja", "zh")
        for (locale in locales) {
            val strings = getAppStrings(locale)
            val fields = listOf(
                strings.liveLocationUpdatesTitle,
                strings.liveShareSliderLessBattery,
                strings.liveShareSliderMoreFrequent,
                strings.liveShareSliderBatteryNote,
                strings.liveShareIntervalSecondsShort,
                strings.liveShareIntervalMinutesShort,
                strings.liveShareDurationHoursShort,
                strings.liveShareTimeLimitSummary,
                strings.changeAction,
                strings.liveShareExpiryExplanation,
                strings.liveShareRideEndExplanation,
                strings.liveSharePrivacySummary,
            )
            fields.forEach { value ->
                assertTrue("Blank string in locale $locale", value.isNotBlank())
            }
            // Copy guard: no battery percentages or pause-ends-sharing claims.
            assertFalse(
                "Battery note in $locale must not state percentages",
                strings.liveShareSliderBatteryNote.contains("%"),
            )
            assertFalse(
                "Expiry explanation in $locale must not mention pause",
                strings.liveShareExpiryExplanation.lowercase().contains("pause"),
            )
            LiveShareFrequencySliderPolicy.STEPS_SECONDS.forEach { seconds ->
                val formatted = formatLiveShareIntervalShort(seconds, strings)
                assertTrue("Formatted interval blank in $locale for $seconds", formatted.isNotBlank())
                assertFalse("Unformatted placeholder in $locale: $formatted", formatted.contains("%1"))
            }
        }
    }
}
