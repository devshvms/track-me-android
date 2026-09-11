package `in`.shvms.trackme.domain.export.template

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light a ride happened in — what The Hour colours its frame by (SCOPE_1.8.9 §6.5).
 *
 * Boundaries are the sun's elevation, not clock times, because the same 06:14 is dark in a Delhi
 * winter and broad daylight in a Delhi summer:
 * - below −6° (past civil twilight) is [NIGHT];
 * - −6° to 0° is [DAWN] before solar noon and [DUSK] after it;
 * - 0° to 10° is the golden hour, morning or evening;
 * - above 10° is [DAY].
 */
enum class LightPhase { DAWN, GOLDEN_MORNING, DAY, GOLDEN_EVENING, DUSK, NIGHT }

/**
 * Solar elevation from NOAA's general solar-position equations — the same source as the
 * `SunsetCalculator` that ships for the daylight warning, and for the same reasons: a date and a
 * coarse position are all it needs. **No network, no new permission, no third party receiving a
 * location.** Accurate to well under a degree, which is far finer than a six-bucket palette needs.
 *
 * Pure, with no clock of its own, so it is tested against the sun's known behaviour on real dates.
 */
internal object SolarPhase {

    fun phase(latitude: Double, longitude: Double, epochMillis: Long): LightPhase {
        val position = position(latitude, longitude, epochMillis)
        return when {
            position.elevationDegrees < -6.0 -> LightPhase.NIGHT
            position.elevationDegrees < 0.0 -> if (position.morning) LightPhase.DAWN else LightPhase.DUSK
            position.elevationDegrees < 10.0 ->
                if (position.morning) LightPhase.GOLDEN_MORNING else LightPhase.GOLDEN_EVENING
            else -> LightPhase.DAY
        }
    }

    fun elevationDegrees(latitude: Double, longitude: Double, epochMillis: Long): Double =
        position(latitude, longitude, epochMillis).elevationDegrees

    private class Position(val elevationDegrees: Double, val morning: Boolean)

    private fun position(latitude: Double, longitude: Double, epochMillis: Long): Position {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMillis }
        val dayOfYear = utc.get(Calendar.DAY_OF_YEAR)
        val daysInYear = utc.getActualMaximum(Calendar.DAY_OF_YEAR)
        val hour = utc.get(Calendar.HOUR_OF_DAY)
        val minutes = hour * 60.0 + utc.get(Calendar.MINUTE) + utc.get(Calendar.SECOND) / 60.0

        // Fractional year, in radians.
        val gamma = 2 * PI / daysInYear * (dayOfYear - 1 + (hour - 12) / 24.0)
        val equationOfTimeMinutes = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        // True solar time in minutes, then the hour angle normalised into [-180, 180): negative
        // before solar noon, which is what separates dawn from dusk at the same elevation.
        val trueSolarMinutes = minutes + equationOfTimeMinutes + 4 * longitude
        val hourAngleDegrees = ((trueSolarMinutes / 4 - 180) % 360 + 540) % 360 - 180

        val latitudeRadians = Math.toRadians(latitude)
        val cosZenith = (
            sin(latitudeRadians) * sin(declination) +
                cos(latitudeRadians) * cos(declination) * cos(Math.toRadians(hourAngleDegrees))
            ).coerceIn(-1.0, 1.0)
        return Position(
            elevationDegrees = 90.0 - Math.toDegrees(acos(cosZenith)),
            morning = hourAngleDegrees < 0,
        )
    }
}
