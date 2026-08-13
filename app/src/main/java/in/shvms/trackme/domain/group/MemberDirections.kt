package `in`.shvms.trackme.domain.group

import java.util.Locale

/**
 * Routing to where a member's phone last was — SCOPE_1.7.2 §2.3, §4.6, amendment **A30**.
 *
 * `GroupDestinationLinks` next door produces *search* links (`geo:` and `maps/search`), and neither
 * can express directions, so this is a sibling rather than an extension of it.
 *
 * **This never starts turn-by-turn navigation, and that is structural, not a preference.** At
 * 80 km/h a two-minute-old fix is 2.7 km wrong and the rider you are chasing is still moving; a
 * rider following turn-by-turn to that phantom is looking at a screen instead of a road, arriving
 * where nobody is. Android's `google.navigation:` scheme force-starts navigation immediately, which
 * is exactly that failure, so it is deliberately not used. A route *preview* leaves the decision
 * with the rider, who can see the age the route was built from.
 *
 * The caller is responsible for only offering this when the fix is `FRESH` (§2.3) — the action is
 * absent, not disabled, for a stale member, because a directions button routing to a nine-minute-old
 * point is not a degraded feature, it is a wrong answer.
 *
 * Pure. These strings end up in another app's hands, and a malformed one fails somewhere we will
 * never see.
 */
object MemberDirections {

    /** Six decimals is ~0.1 m — past the point any consumer GPS can justify. */
    private const val COORD_FORMAT = "%.6f"

    /**
     * A route preview to a coordinate.
     *
     * Google's `?api=1` form is the portable one: Android opens it in whichever app claims maps
     * links, and a device with none falls back to a browser rather than to nothing.
     *
     * Always `Locale.ROOT`. The comma decimal separator used across half of Europe would render
     * `12.9716` as `12,9716`, producing a link that silently opens the wrong place — the same trap
     * `GroupDestinationLinks` documents, and it bites just as hard here.
     */
    fun routePreviewUrl(lat: Double, lng: Double): String = String.format(
        Locale.ROOT,
        "https://www.google.com/maps/dir/?api=1&destination=$COORD_FORMAT,$COORD_FORMAT",
        lat,
        lng,
    )
}
