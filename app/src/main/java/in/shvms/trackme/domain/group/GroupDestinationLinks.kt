package `in`.shvms.trackme.domain.group

import java.util.Locale

/**
 * Turning a destination into things a person can act on — a readable coordinate, a maps link, and
 * a calendar entry.
 *
 * Pure, and deliberately so: these strings end up in another app's hands (Maps, Calendar) or in a
 * message someone sends, and a malformed one fails somewhere we will never see. Locale is the
 * quiet trap — `String.format` with the default locale renders `12.9716` as `12,9716` in half of
 * Europe, which produces a link that silently opens the wrong place or nothing at all.
 */
object GroupDestinationLinks {

    /** Six decimals is ~0.1 m — past the point any consumer GPS can justify. */
    private const val COORD_FORMAT = "%.6f"

    /**
     * Human-readable coordinates.
     *
     * Always `Locale.ROOT`: a comma decimal separator here would be copied into a link and break
     * it, and "12,9716, 77,5946" is unreadable regardless.
     */
    fun formatCoordinates(lat: Double, lng: Double): String =
        String.format(Locale.ROOT, "$COORD_FORMAT, $COORD_FORMAT", lat, lng)

    /**
     * A `geo:` URI, which opens the user's chosen maps app rather than forcing one.
     *
     * On virtually every Android device that is Google Maps, but a user who has deliberately
     * installed something else should not have that overridden by us.
     */
    fun geoUri(lat: Double, lng: Double, label: String?): String {
        val coords = String.format(Locale.ROOT, "$COORD_FORMAT,$COORD_FORMAT", lat, lng)
        val query = if (label.isNullOrBlank()) coords else "$coords(${sanitiseLabel(label)})"
        return "geo:$coords?q=$query"
    }

    /**
     * A universal https maps link, used as the fallback when no `geo:` handler exists and as the
     * location in a calendar event.
     *
     * Google's `?api=1` form is the portable one: Android opens it in Maps, iOS offers Apple Maps
     * or Google Maps, and a desktop browser just shows the place. A platform-specific URL would
     * strand whichever platform it was not written for — and calendar entries travel.
     */
    fun webMapUrl(lat: Double, lng: Double): String =
        String.format(
            Locale.ROOT,
            "https://www.google.com/maps/search/?api=1&query=$COORD_FORMAT,$COORD_FORMAT",
            lat,
            lng,
        )

    /**
     * The description for a calendar event.
     *
     * Carries the maps link when there is one, because a calendar entry that says only "Sunday
     * Riders" is not much use at the moment it fires.
     */
    fun calendarDescription(lat: Double?, lng: Double?): String =
        if (lat != null && lng != null) webMapUrl(lat, lng) else ""

    /**
     * Parentheses close the `geo:` label early and swallow the rest of the URI, so a group called
     * "Ride (Sunday)" would produce a broken link. Cheap to strip, invisible when it goes wrong.
     */
    private fun sanitiseLabel(label: String): String =
        label.replace('(', ' ').replace(')', ' ').trim()
}
