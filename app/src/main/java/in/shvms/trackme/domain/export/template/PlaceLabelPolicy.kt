package `in`.shvms.trackme.domain.export.template

/** SCOPE_1.8.9 §7 — the export's place reference. Off by default: it is a new disclosure. */
enum class PlaceReference { OFF, START_AND_FINISH, FINISH_ONLY }

/**
 * The parts of a reverse-geocoded address the policy is allowed to look at. Platform geocoders
 * (`android.location.Address`, `CLPlacemark`) are mapped into this so the rule is testable.
 *
 * [thoroughfare] is only ever used to **reject** a candidate — it is never rendered.
 */
internal data class PlaceParts(
    val subLocality: String? = null,
    val locality: String? = null,
    val subAdminArea: String? = null,
    val adminArea: String? = null,
    val thoroughfare: String? = null,
    /**
     * Read for Part 2's coverage counts only. Never part of [PlaceLabelPolicy.label] — a country
     * name beside a route tells a reader nothing they cannot see, and the label rule is about
     * naming one place, not locating it on Earth.
     */
    val countryName: String? = null,
)

/**
 * Granularity rule, binding (SCOPE_1.8.9 §7): neighbourhood or coarser, **never a street**. The
 * route's ends are trimmed 200 m for privacy; a street name printed beside that route would be a
 * finer disclosure than the geometry and quietly undo the trim.
 *
 * Postal codes, house numbers and feature names are never read at all, so they cannot leak.
 */
internal object PlaceLabelPolicy {

    const val MAX_LENGTH = 32

    fun label(parts: PlaceParts): String? {
        val street = parts.thoroughfare?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        return listOf(parts.subLocality, parts.locality, parts.subAdminArea, parts.adminArea)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            // Some geocoders put the street in the sub-locality slot. Equal to the street is a street.
            .filterNot { street != null && it.lowercase() == street }
            .firstOrNull()
            ?.let { if (it.length > MAX_LENGTH) it.take(MAX_LENGTH - 1).trimEnd() + "…" else it }
    }

    /** The one line a template prints, or null for nothing to print. A loop names its place once. */
    fun line(reference: PlaceReference, start: String?, finish: String?): String? = when (reference) {
        PlaceReference.OFF -> null
        PlaceReference.FINISH_ONLY -> finish
        PlaceReference.START_AND_FINISH -> when {
            start == null -> finish
            finish == null -> start
            start.equals(finish, ignoreCase = true) -> start
            else -> "$start → $finish"
        }
    }
}
