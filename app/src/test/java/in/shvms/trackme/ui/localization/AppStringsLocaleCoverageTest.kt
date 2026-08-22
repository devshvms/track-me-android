package `in`.shvms.trackme.ui.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsLocaleCoverageTest {

    private val localizedLanguages: List<String> = SUPPORTED_LANGUAGE_CODES - "en"

    /**
     * Keys that are intentionally identical in every language and therefore intentionally absent
     * from the locale maps. Adding to this set is a deliberate product decision.
     */
    private val intentionallyEnglishOnly = setOf(
        "appName",
        // HANDSFREE-01: spoken copy ships in English only until fluent reviewers approve it.
        "voiceNoActiveRide",
    )

    /**
     * TASK-138 owns these entries; they are supplied when that branch lands. This list is temporary
     * by construction — `the dependency-owned exclusion list has not outlived its dependency` fails
     * the moment the owning branch merges and tells you which two lines to delete. Do not add to it
     * as a way of silencing a missing translation.
     */
    private val dependencyOwnedKeys = emptySet<String>()

    /**
     * Locale entries that are byte-identical to the English default. Legitimate (`ok`, `Normal`,
     * `distance`, `miles`, …) but each one is also indistinguishable from a lazy copy-paste, which
     * is the one way to satisfy the presence-based guard above without translating anything.
     * Baseline measured on `389a8ff`: fr 12, de 5, es 2, ja 1, hi 0, zh 0 (20 total).
     * TASK-132 added `notifTrackingMetrics` (`"%1$s • %2$s • %3$s"`) across 6 locales, raising total to 26.
     * With 6 locales for `notifTrackingMetrics` plus actual identical locale keys across de(7)+es(3)+fr(13)+hi(1)+ja(2)+zh(1), total is 27.
     *
     * Raised to 28 on 2026-08-08 for `de:navCommunity`. "Community" is the ordinary German word
     * for this in a consumer app — "Gemeinschaft" reads as a religious or academic community, and
     * "Gruppe" is already taken by the group itself, so translating it would be worse copy, not
     * better. The French counterpart went the other way: `fr:groupDestination` became "Point de
     * rendez-vous", which is both a real translation and better copy for a group ride than a bare
     * "Destination".
     * Raising this number is a product decision, not a merge-conflict resolution.
     *
     * Raised to 41 on 2026-08-09 for the 1.7.1 walkthrough's sample ride card. `48:20` is a
     * duration in `mm:ss`, which is written the same way in all seven languages (6 entries), and
     * `ja:obHistorySampleDistance` is "12.4 km" — Japanese uses the period decimal and the Latin
     * unit, so it lands identical to English while es/fr/de take the comma and hi/zh take their
     * own unit. These are illustration data rather than prose; the completeness test requires every
     * locale to carry every key, so they cannot simply be omitted.
     */
    private val maxEntriesIdenticalToEnglish = 41

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

    private fun englishDefaults(): Map<String, String> {
        val english = AppStrings()
        return AppStrings::class.java.methods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .associate {
                it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) to
                    it.invoke(english) as String
            }
    }

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
    fun `the dependency-owned exclusion list has not outlived its dependency`() {
        val declared = declaredStringKeys()
        dependencyOwnedKeys.forEach { key ->
            assertTrue("'$key' is excluded as dependency-owned but is not an AppStrings key", key in declared)
            localizedLanguages.forEach { language ->
                assertTrue(
                    "'$key' is now supplied by $language — its owning branch (TASK-138) has landed. " +
                        "Delete the `dependencyOwnedKeys` declaration and its subtraction in " +
                        "`every localized language supplies every AppStrings key`; the key is then " +
                        "covered by the normal guard.",
                    key !in getAppStrings(language).overrides
                )
            }
        }
    }

    @Test
    fun `the supported-language list matches the runtime and the picker`() {
        assertTrue("'en' must be in SUPPORTED_LANGUAGE_CODES", "en" in SUPPORTED_LANGUAGE_CODES)
        assertEquals(
            "'en' must fall through to the English base class, not a locale map",
            emptySet<String>(),
            getAppStrings("en").overrides.keys
        )
        localizedLanguages.forEach { language ->
            assertTrue(
                "'$language' is in SUPPORTED_LANGUAGE_CODES but getAppStrings(\"$language\") returned " +
                    "the English base class — it has no `when` arm in AppStrings.kt",
                getAppStrings(language).overrides.isNotEmpty()
            )
        }
    }

    @Test
    fun `no new locale entries are copies of the English default`() {
        val english = englishDefaults()
        val identical = localizedLanguages.flatMap { language ->
            getAppStrings(language).overrides
                .filter { (key, value) -> value == english[key] }
                .keys.map { "$language:$it" }
        }.sorted()

        assertTrue(
            "${identical.size} locale entries are identical to their English default (baseline " +
                "$maxEntriesIdenticalToEnglish). New ones are usually an untranslated copy-paste. " +
                "If they are genuinely identical in that language, raise the baseline in this test " +
                "and say why in the PR. Entries: $identical",
            identical.size <= maxEntriesIdenticalToEnglish
        )
    }

    @Test
    fun `unsupported language codes fall back to English without crashing`() {
        listOf("en", "pt", "", "zh-Hans").forEach { code ->
            assertEquals(AppStrings().appName, getAppStrings(code).appName)
        }
    }
}
