package `in`.shvms.trackme.domain

import java.util.Locale

object UnitFormatter {
    private const val METERS_PER_MILE = 1609.344

    /**
     * Decimal precision for any ride-summary distance a user reads as "the distance of this ride" —
     * History card, ride detail stats, export preview, and every artifact burned out of them.
     *
     * Per `decision_log.md` 2026-07-27 clause 2, the parity target for a shared artifact is the
     * screen the share action lives on, and formatter *arguments* count as much as the formatter:
     * `distance(m, imperial)` and `distance(m, imperial, decimals = 1)` are the same helper and
     * still disagree on screen. Prefer [rideDistance] over passing this constant by hand.
     */
    const val RIDE_DISTANCE_DECIMALS = 1

    fun distance(meters: Double, imperial: Boolean, decimals: Int = 2, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${decimals}f %s", if (imperial) meters / METERS_PER_MILE else meters / 1000.0, if (imperial) "mi" else "km")

    /**
     * Canonical ride-summary distance string. Use this on every surface that shows a ride's total
     * distance to a user, and on every export/share artifact rendered from such a surface, so the
     * screen and the file it produces can never disagree.
     *
     * Not for live/in-progress readouts (the tracking HUD), which deliberately show finer precision.
     */
    fun rideDistance(meters: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): String =
        distance(meters, imperial, decimals = RIDE_DISTANCE_DECIMALS, locale = locale)
    fun speed(mps: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f %s", mps * if (imperial) 2.236936 else 3.6, if (imperial) "mph" else "km/h")
    fun distanceUnitLabel(imperial: Boolean) = if (imperial) "mi" else "km"
    fun speedUnitLabel(imperial: Boolean) = if (imperial) "mph" else "km/h"
}
