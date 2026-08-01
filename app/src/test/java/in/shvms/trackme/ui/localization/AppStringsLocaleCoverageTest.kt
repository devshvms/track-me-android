package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsLocaleCoverageTest {

    private val localizedLanguages = listOf("es", "fr", "de", "hi", "ja", "zh")

    /**
     * Keys that are intentionally identical in every language and therefore intentionally absent
     * from the locale maps. Adding to this set is a deliberate product decision.
     */
    private val intentionallyEnglishOnly = setOf("appName")

    /** TASK-138 owns these entries; they are supplied when that branch lands. */
    private val dependencyOwnedKeys = setOf("openSettings")

    /** Every `val x: String = s("x", …)` field on the base class, derived from its getters. */
    private fun declaredStringKeys(): Set<String> =
        AppStrings::class.java.methods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
            .toSet()

    @Test
    fun `every localized language supplies every AppStrings key`() {
        val expected = declaredStringKeys() - intentionallyEnglishOnly - dependencyOwnedKeys
        assertTrue(
            "Reflection found no AppStrings keys — the getter convention changed",
            expected.size > 250
        )

        localizedLanguages.forEach { language ->
            val supplied = getAppStrings(language).overrides.keys
            val missing = (expected - supplied).sorted()
            assertEquals("$language is missing ${missing.size} key(s): $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun `no locale map contains a key that no longer exists on AppStrings`() {
        val declared = declaredStringKeys()
        localizedLanguages.forEach { language ->
            val stray = (getAppStrings(language).overrides.keys - declared).sorted()
            assertEquals("$language has stray override key(s) that override nothing: $stray", emptyList<String>(), stray)
        }
    }

    @Test
    fun `the intentionally-English-only allow-list has no stale entries`() {
        val declared = declaredStringKeys()
        intentionallyEnglishOnly.forEach { key ->
            assertTrue("'$key' is allow-listed but is not an AppStrings key", key in declared)
            localizedLanguages.forEach { language ->
                assertTrue(
                    "'$key' is allow-listed as English-only but $language now translates it — remove it from the allow-list",
                    key !in getAppStrings(language).overrides
                )
            }
        }
    }

    @Test
    fun `unsupported language codes fall back to English without crashing`() {
        listOf("en", "pt", "", "zh-Hans").forEach { code ->
            assertEquals(AppStrings().appName, getAppStrings(code).appName)
        }
    }
}
