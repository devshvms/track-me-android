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
            val strings = getAppStrings(language)
            val labels = strings.labels()
            assertTrue("$language has a blank map layer label", labels.all(String::isNotBlank))
            assertTrue("$language map layer labels are not distinct", labels.toSet().size == labels.size)
            assertTrue("$language has a blank map-layer trigger label", strings.mapLayers.isNotBlank())
            assertTrue("$language has a blank expanded state label", strings.mapLayersExpanded.isNotBlank())
            assertTrue("$language has a blank collapsed state label", strings.mapLayersCollapsed.isNotBlank())

            if (language != "en") {
                assertFalse("$language fell back for map layer labels", labels == englishLabels)
                assertFalse("$language fell back for map-layer trigger label", strings.mapLayers == english.mapLayers)
                assertFalse("$language fell back for expanded state label", strings.mapLayersExpanded == english.mapLayersExpanded)
                assertFalse("$language fell back for collapsed state label", strings.mapLayersCollapsed == english.mapLayersCollapsed)
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
