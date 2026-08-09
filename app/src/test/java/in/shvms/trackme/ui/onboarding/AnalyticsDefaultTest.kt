package `in`.shvms.trackme.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the analytics toggle starts.
 *
 * The product decision is "default on"; the constraint is that PostHog is hosted in the EU and the
 * app ships European languages, so GDPR/ePrivacy applies to a real share of installs — and there a
 * pre-ticked box is not consent. These pin the one line that keeps both true.
 */
class AnalyticsDefaultTest {

    @Test
    fun `the toggle starts on outside the consent-required region`() {
        for (country in listOf("US", "IN", "BR", "AU", "JP", "CA", "ZA")) {
            assertTrue(country, AnalyticsDefault.startsOn(simCountry = country, localeCountry = null))
        }
    }

    @Test
    fun `the toggle starts off across the EEA, the UK and Switzerland`() {
        for (country in listOf("DE", "FR", "ES", "IT", "NL", "IE", "PL", "SE", "NO", "IS", "LI", "GB", "CH")) {
            assertFalse(country, AnalyticsDefault.startsOn(simCountry = country, localeCountry = null))
        }
    }

    @Test
    fun `the SIM wins over the device locale`() {
        // A German-language phone on a US SIM is a person who reads German, not a person in the
        // EEA. Language is not location, and only one of the two carries the legal obligation.
        assertTrue(AnalyticsDefault.startsOn(simCountry = "US", localeCountry = "DE"))
        assertFalse(AnalyticsDefault.startsOn(simCountry = "DE", localeCountry = "US"))
    }

    @Test
    fun `the locale is used when there is no SIM`() {
        // Tablets and eSIM-less devices still have a locale.
        assertFalse(AnalyticsDefault.startsOn(simCountry = null, localeCountry = "FR"))
        assertFalse(AnalyticsDefault.startsOn(simCountry = "", localeCountry = "FR"))
        assertTrue(AnalyticsDefault.startsOn(simCountry = null, localeCountry = "US"))
    }

    @Test
    fun `an unknown region assumes consent is required`() {
        // Being wrong in this direction costs a data point. Being wrong in the other direction
        // costs a compliance finding, so the unknown case takes the cautious branch.
        assertFalse(AnalyticsDefault.startsOn(simCountry = null, localeCountry = null))
        assertFalse(AnalyticsDefault.startsOn(simCountry = "", localeCountry = ""))
    }

    @Test
    fun `country codes are matched regardless of case or padding`() {
        // TelephonyManager returns lowercase; Locale returns uppercase.
        assertFalse(AnalyticsDefault.startsOn(simCountry = "de", localeCountry = null))
        assertFalse(AnalyticsDefault.startsOn(simCountry = " gb ", localeCountry = null))
        assertTrue(AnalyticsDefault.startsOn(simCountry = "us", localeCountry = null))
    }

    @Test
    fun `the override constant is shipped off`() {
        // ON_EVERYWHERE exists so the business decision is one line to reverse. It must not drift
        // on by accident — flipping it changes the legal posture in 30 countries.
        assertFalse(
            "AnalyticsDefault.ON_EVERYWHERE is true — this pre-ticks analytics consent in the EEA",
            AnalyticsDefault.ON_EVERYWHERE,
        )
    }
}
