package `in`.shvms.trackme.domain

import java.util.Locale

object UnitFormatter {
    private const val METERS_PER_MILE = 1609.344
    fun distance(meters: Double, imperial: Boolean, decimals: Int = 2, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${decimals}f %s", if (imperial) meters / METERS_PER_MILE else meters / 1000.0, if (imperial) "mi" else "km")
    fun speed(mps: Double, imperial: Boolean, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f %s", mps * if (imperial) 2.236936 else 3.6, if (imperial) "mph" else "km/h")
    fun distanceUnitLabel(imperial: Boolean) = if (imperial) "mi" else "km"
    fun speedUnitLabel(imperial: Boolean) = if (imperial) "mph" else "km/h"
}
