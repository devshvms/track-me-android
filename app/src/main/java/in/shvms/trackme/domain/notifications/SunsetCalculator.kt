package `in`.shvms.trackme.domain.notifications

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * SCOPE_1.8.7 §6.1.6 scenario 28 — when the sun sets, computed on this device.
 *
 * §6.1.6 calls this the best cost/value ratio in the release, and the reason is entirely about what
 * it does *not* need: sunset follows from a date and a coarse position, both of which the app
 * already has. **No network, no third-party API, no new permission, and no new party receiving
 * location** — which is what separates it from scenarios 29 and 30 (weather and AQI), both deferred
 * precisely because they need all four.
 *
 * It is also genuinely useful to every persona. A runner or cyclist caught out after dark is a real
 * safety issue, and "sunset in 40 minutes" is a fact someone can act on in the moment they are
 * deciding whether to set off.
 *
 * ### The algorithm
 *
 * NOAA's sunset equations, which are accurate to well under a minute at the latitudes anyone rides
 * at. That is far more precision than the use needs — the surface says "in about 40 minutes" — but
 * the alternatives are a lookup table or an approximation that drifts across the year, and neither
 * is smaller than this.
 *
 * Pure, with no Android types and no clock of its own, so it can be tested against known sunsets
 * for real places on real dates rather than against itself.
 */
object SunsetCalculator {

    /** The sun's centre this far below the horizon, allowing for refraction — the standard value. */
    private const val ZENITH_DEGREES = 90.833

    /**
     * Minutes after local midnight at which the sun sets, or null when it does not set that day.
     *
     * Null is a real answer, not a failure: above the Arctic and below the Antarctic circle there
     * are days with no sunset at all, and a caller that treats null as "unknown" and shows nothing
     * is behaving correctly for those users.
     *
     * @param latitude degrees, north positive.
     * @param longitude degrees, east positive.
     * @param dayOfYear 1–366.
     * @param utcOffsetMinutes the device's offset from UTC, including any DST in force.
     */
    fun sunsetMinutesAfterMidnight(
        latitude: Double,
        longitude: Double,
        dayOfYear: Int,
        utcOffsetMinutes: Int,
    ): Int? {
        if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null
        if (dayOfYear !in 1..366) return null

        val zenithRad = Math.toRadians(ZENITH_DEGREES)
        val latRad = Math.toRadians(latitude)

        // Approximate time, expressed as a fraction of the day, for the *setting* event.
        val longitudeHour = longitude / 15.0
        val approximateTime = dayOfYear + ((18 - longitudeHour) / 24.0)

        // Sun's mean anomaly, then its true longitude.
        val meanAnomaly = (0.9856 * approximateTime) - 3.289
        var trueLongitude = meanAnomaly +
            (1.916 * sin(Math.toRadians(meanAnomaly))) +
            (0.020 * sin(Math.toRadians(2 * meanAnomaly))) +
            282.634
        trueLongitude = normalizeDegrees(trueLongitude)

        // Right ascension, forced into the same quadrant as the true longitude — the step that is
        // easiest to omit and produces an answer that is wrong by hours rather than minutes.
        var rightAscension = Math.toDegrees(atanDegrees(0.91764 * tan(Math.toRadians(trueLongitude))))
        rightAscension = normalizeDegrees(rightAscension)
        val longitudeQuadrant = kotlin.math.floor(trueLongitude / 90.0) * 90.0
        val rightAscensionQuadrant = kotlin.math.floor(rightAscension / 90.0) * 90.0
        rightAscension = (rightAscension + (longitudeQuadrant - rightAscensionQuadrant)) / 15.0

        val sinDeclination = 0.39782 * sin(Math.toRadians(trueLongitude))
        val cosDeclination = cos(asin(sinDeclination))

        val cosHourAngle =
            (cos(zenithRad) - (sinDeclination * sin(latRad))) / (cosDeclination * cos(latRad))
        // Out of range means the sun never reaches the horizon that day: midnight sun, or a polar
        // night. Both are "there is no sunset to report".
        if (cosHourAngle !in -1.0..1.0) return null

        val hourAngle = Math.toDegrees(acos(cosHourAngle)) / 15.0
        val localMeanTime = hourAngle + rightAscension - (0.06571 * approximateTime) - 6.622
        val utcHours = normalizeHours(localMeanTime - longitudeHour)

        val localMinutes = (utcHours * 60.0) + utcOffsetMinutes
        // Wrap rather than clamp: a sunset can land on the next or previous calendar day in local
        // time near a date line or a large offset, and clamping would report midnight.
        val wrapped = ((localMinutes % 1440.0) + 1440.0) % 1440.0
        return wrapped.toInt()
    }

    /**
     * Minutes from now until sunset, or null when there is nothing useful to say.
     *
     * Null when the sun does not set, when it has already set, or when it is further away than
     * [MAX_MINUTES_WORTH_MENTIONING]. Someone setting off at ten in the morning does not need to be
     * told about sunset — the fact is true, and saying it is the app filling silence.
     */
    fun minutesUntilSunset(
        latitude: Double,
        longitude: Double,
        dayOfYear: Int,
        minutesAfterLocalMidnightNow: Int,
        utcOffsetMinutes: Int,
    ): Int? {
        val sunset = sunsetMinutesAfterMidnight(latitude, longitude, dayOfYear, utcOffsetMinutes)
            ?: return null
        val remaining = sunset - minutesAfterLocalMidnightNow
        if (remaining <= 0) return null
        if (remaining > MAX_MINUTES_WORTH_MENTIONING) return null
        return remaining
    }

    /**
     * Three hours. Long enough to cover the ride someone is about to start, short enough that the
     * line only appears when it is genuinely a consideration.
     */
    const val MAX_MINUTES_WORTH_MENTIONING = 180

    /** The device's current UTC offset in minutes, DST included. */
    fun utcOffsetMinutes(timeZone: TimeZone, atMillis: Long): Int =
        timeZone.getOffset(atMillis) / 60_000

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeHours(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
    private fun atanDegrees(value: Double): Double = kotlin.math.atan(value)
}
