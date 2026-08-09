package `in`.shvms.trackme.ui.onboarding

/**
 * Where the analytics toggle starts on the last walkthrough screen.
 *
 * The product decision is "default on". The constraint is that PostHog is hosted at
 * `eu.i.posthog.com` and the app ships German, French and Spanish, so a real share of installs sit
 * under GDPR and the ePrivacy Directive — where analytics needs prior, active consent and a
 * pre-ticked box is specifically the pattern *Planet49* (C-673/17) invalidated.
 *
 * So the toggle is pre-set on everywhere except the EEA, the UK and Switzerland, where it starts
 * off. Same screen, same copy, same single tap either way — only the initial position differs.
 *
 * To pre-set on everywhere regardless, set [ON_EVERYWHERE] to true. It is one line on purpose:
 * this is a business decision, not an engineering one, and it should be trivial to reverse.
 */
object AnalyticsDefault {

    const val ON_EVERYWHERE: Boolean = false

    /**
     * EEA + UK + Switzerland. Switzerland is not in the EEA but its revised FADP tracks GDPR
     * closely enough that treating it differently buys nothing.
     */
    private val CONSENT_REQUIRED: Set<String> = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE",
        "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO",
        "GB", "CH",
    )

    /**
     * @param simCountry ISO country from the SIM, when there is one
     * @param localeCountry ISO country from the device locale
     *
     * The SIM wins when present because it is the harder signal to set casually — a device locale
     * says what language someone reads, which is not the same as where they are. When neither
     * resolves, the safer assumption is that consent is required.
     */
    fun startsOn(simCountry: String?, localeCountry: String?): Boolean {
        if (ON_EVERYWHERE) return true
        val country = simCountry?.takeIf { it.isNotBlank() } ?: localeCountry
        // The blank check has to survive the fallback too: an empty locale country is unknown, not
        // "a country that happens to be outside the EEA", and treating it as the latter pre-ticks
        // consent for anyone whose region simply failed to resolve.
        val normalised = country?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
        return normalised !in CONSENT_REQUIRED
    }
}
