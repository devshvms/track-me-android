package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsMapLayerAccessibilityTest {
    private val supportedLanguages = listOf("en", "es", "fr", "de", "hi", "ja", "zh")

    @Test
    fun `map layer labels are present and localized for every supported language`() {
        val english = getAppStrings("en")
        val englishLabels = english.labels()

        supportedLanguages.forEach { language ->
            val labels = getAppStrings(language).labels()
            assertTrue("$language has a blank map layer label", labels.all(String::isNotBlank))
            assertTrue("$language map layer labels are not distinct", labels.toSet().size == labels.size)

            if (language != "en") {
                assertFalse("$language fell back for map layer labels", labels == englishLabels)
            }
        }
    }

    private fun AppStrings.labels(): List<String> = listOf(
        mapLayerNormal,
        mapLayerSatellite,
        mapLayerTerrain,
        mapLayerHybrid,
        mapLayerTraffic
    )
}
