package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppStringsHelpFeedbackTest {
    private val languages = listOf("es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun helpFeedbackCopyHasTranslationOverrides() {
        val english = getAppStrings("en")
        val values = listOf(
            english.helpFeedbackTitle, english.helpFeedbackDescription, english.contactSupport,
            english.helpFaqRecordingQuestion, english.helpFaqRecordingAnswer,
            english.helpFaqBatteryQuestion, english.helpFaqBatteryAnswer,
            english.helpFaqDistanceQuestion, english.helpFaqDistanceAnswer,
            english.helpFaqOfflineQuestion, english.helpFaqOfflineAnswer,
            english.helpFaqShareQuestion, english.helpFaqShareAnswer,
            english.helpFaqDataQuestion, english.helpFaqDataAnswer
        )
        languages.forEach { language ->
            val translated = getAppStrings(language)
            val localized = listOf(
                translated.helpFeedbackTitle, translated.helpFeedbackDescription, translated.contactSupport,
                translated.helpFaqRecordingQuestion, translated.helpFaqRecordingAnswer,
                translated.helpFaqBatteryQuestion, translated.helpFaqBatteryAnswer,
                translated.helpFaqDistanceQuestion, translated.helpFaqDistanceAnswer,
                translated.helpFaqOfflineQuestion, translated.helpFaqOfflineAnswer,
                translated.helpFaqShareQuestion, translated.helpFaqShareAnswer,
                translated.helpFaqDataQuestion, translated.helpFaqDataAnswer
            )
            values.zip(localized).forEach { (default, value) -> assertNotEquals("$language fell back to English", default, value) }
        }
    }
}
