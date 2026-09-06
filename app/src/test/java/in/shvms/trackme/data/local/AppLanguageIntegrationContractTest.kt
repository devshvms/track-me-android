package `in`.shvms.trackme.data.local

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageIntegrationContractTest {
    @Test fun `manifest exposes locale config and AppCompat auto storage`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
        assertTrue(manifest.contains("androidx.appcompat.app.AppLocalesMetadataHolderService"))
        assertTrue(manifest.contains("android:name=\"autoStoreLocales\""))
        assertTrue(manifest.contains("android:value=\"true\""))
    }

    @Test fun `system locale config exactly matches the shipping catalog`() {
        val file = sourceFile("app/src/main/res/xml/locales_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val localeNodes = document.getElementsByTagName("locale")
        val declared = (0 until localeNodes.length).map { index ->
            localeNodes.item(index).attributes.getNamedItem("android:name").nodeValue
        }
        assertEquals(AppLanguageCatalog.supportedCodes, declared)
    }

    @Test fun `Compose host and picker use the AppCompat locale authority`() {
        val activity = source("app/src/main/java/in/shvms/trackme/MainActivity.kt")
        val preferences = source("app/src/main/java/in/shvms/trackme/data/local/AppPreferencesManager.kt")
        val settings = source("app/src/main/java/in/shvms/trackme/ui/settings/SettingsScreen.kt")

        assertTrue(activity.contains("class MainActivity : AppCompatActivity()"))
        assertTrue(activity.contains("prepareApplicationLocale()"))
        assertTrue(activity.contains("reconcileApplicationLocale()"))
        assertTrue(preferences.contains("AppCompatDelegate.setApplicationLocales"))
        assertTrue(settings.contains("preferencesManager.setAppLanguage(code)"))
        assertFalse(settings.contains("putString(\"app_language\", code)"))
    }

    private fun source(relative: String): String = sourceFile(relative).readText()

    private fun sourceFile(relative: String): File {
        var directory = File("").absoluteFile
        repeat(6) {
            File(directory, relative).takeIf(File::exists)?.let { return it }
            File(directory, relative.removePrefix("app/")).takeIf(File::exists)?.let { return it }
            directory = directory.parentFile ?: directory
        }
        throw AssertionError("$relative not found")
    }
}
