package `in`.shvms.trackme.domain.export.template

import `in`.shvms.trackme.domain.group.DestinationProgress

/**
 * SCOPE_1.8.9 Part 2 — what a *selection* of rides is, before anything is drawn.
 *
 * ### Why this exists at all
 *
 * Part 1 could assume its subject: one ride, one trace, one set of figures. A selection has no such
 * guarantee. Three rides that continue one another — Bengaluru → Hampi → Badami → Goa — are a
 * journey, and the thing worth showing is the sequence. Twelve Tuesday commutes are the same two
 * places twelve times, and rendering them as "A → B → C" would be nonsense. A year of weekend rides
 * around one city is neither.
 *
 * So the template offered has to follow what the selection *is*, which is the same rule §9.3 used
 * when it found that three of the five single-ride templates cannot generalise: **forced by the
 * data, not by taste.**
 *
 * Every decision here is made from coordinates and timestamps the device already holds. No
 * geocoding, no network, nothing that fails offline.
 */
internal enum class SelectionShape {
    /** Legs that continue one another. The sequence is the story. */
    TOUR,

    /** Spread across a region with no chain. Coverage is the story. */
    TERRITORY,

    /** Repeated or clustered. Accumulation is the only honest story. */
    COLLECTION,
}

/**
 * Whether a place was *stopped in* or merely reached — shvm's rule, 2026-09-11.
 *
 * A region where one of the selected rides **starts** is [VISITED]: ending one recording there and
 * beginning another is evidence the rider was stationary, which is the thing "visited" should mean.
 * A region that only ever appears as a finish is [CROSSED] — reached, but with nothing to show the
 * rider stayed.
 *
 * The honesty of the distinction is the point. It is derived entirely from the trimmed ends the app
 * already geocodes, so it never claims a region the data cannot support. Regions a leg passes
 * *through* without stopping are invisible to it, and deliberately not guessed at: closing that gap
 * needs offline boundary polygons, which is a separate decision.
 */
internal enum class RegionRole { VISITED, CROSSED }

/** Which administrative level a coverage count is about. */
internal enum class AdminLevel {
    DISTRICT,
    STATE;

    fun of(parts: PlaceParts?): String? {
        val raw = when (this) {
            DISTRICT -> parts?.subAdminArea
            STATE -> parts?.adminArea
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** One selected ride, reduced to what an aggregate export needs. */
internal data class SelectionLeg(
    val startLatitude: Double,
    val startLongitude: Double,
    val finishLatitude: Double,
    val finishLongitude: Double,
    val startPlace: PlaceParts? = null,
    val finishPlace: PlaceParts? = null,
    val distanceMeters: Double = 0.0,
    val movingMillis: Long = 0L,
)

/** A named stop on a tour, with the district it sits in. */
internal data class ItineraryStop(val name: String?, val region: String?)

/** The distance and moving time between two consecutive stops. */
internal data class ItineraryHop(val distanceMeters: Double, val movingMillis: Long)

/**
 * A tour as the Itinerary template draws it: `stops.size == hops.size + 1`, always.
 */
internal data class Itinerary(
    val stops: List<ItineraryStop>,
    val hops: List<ItineraryHop>,
) {
    val totalMeters: Double get() = hops.sumOf { it.distanceMeters }
    val totalMillis: Long get() = hops.sumOf { it.movingMillis }
}

internal object AggregateSelection {

    /**
     * How close a finish must be to the next start for the two to read as one journey.
     *
     * Generous on purpose. A rider who stops recording at a hotel and starts again at a petrol
     * station the next morning has not broken the trip, and a tolerance tight enough to call that
     * two separate journeys would almost never fire on real riding.
     */
    const val CHAIN_JOIN_METERS = 25_000.0

    /**
     * How far apart the starts must be before a selection is coverage rather than repetition.
     *
     * Below this, the rides are the same neighbourhood seen many times — a commute, a local loop —
     * and a map of them is one line drawn twelve times.
     */
    const val TERRITORY_SPREAD_METERS = 50_000.0

    /**
     * Which of the three shapes a selection is.
     *
     * Legs are expected in the order the surface shows them, which `prepareComparisonRoutes`
     * already guarantees is oldest-first.
     */
    fun shape(legs: List<SelectionLeg>): SelectionShape {
        // One ride is not an aggregate, and an empty selection has nothing to be.
        if (legs.size < 2) return SelectionShape.COLLECTION

        // A tour is strict: *every* link must hold. One broken link and the set is not a single
        // journey, whatever the rest looks like — and drawing it as one would invent a leg the
        // rider never rode.
        val chained = legs.zipWithNext().all { (previous, next) ->
            metres(previous.finishLatitude, previous.finishLongitude, next.startLatitude, next.startLongitude) <=
                CHAIN_JOIN_METERS
        }
        // Chaining alone is not enough, and the case that proves it is the ordinary one: a commute
        // chains perfectly. You finish at the office, start again from the office, finish at home,
        // start again from home — every link holds, and it is not a journey, it is two places
        // twelve times.
        //
        // What separates them is whether the selection *goes* anywhere: a tour reaches roughly one
        // new place per leg. Net displacement cannot be the test, because a round trip ends where it
        // started and is still unmistakably a tour.
        if (chained && distinctStops(legs) >= legs.size) return SelectionShape.TOUR

        // Spread is measured across the starts rather than every point: it asks "does this person
        // set off from many places", which is what separates coverage from repetition. The widest
        // pair decides it.
        val widest = legs.indices.flatMap { i ->
            (i + 1 until legs.size).map { j ->
                metres(legs[i].startLatitude, legs[i].startLongitude, legs[j].startLatitude, legs[j].startLongitude)
            }
        }.maxOrNull() ?: 0.0

        return if (widest > TERRITORY_SPREAD_METERS) SelectionShape.TERRITORY else SelectionShape.COLLECTION
    }

    /**
     * Regions the selection touched, each marked [RegionRole.VISITED] or [RegionRole.CROSSED].
     *
     * Visited wins wherever a region is both: a place you set off from at least once was somewhere
     * you stayed, whatever else happened there.
     */
    fun regions(legs: List<SelectionLeg>, level: AdminLevel): Map<String, RegionRole> {
        val visited = legs.mapNotNull { level.of(it.startPlace) }.toSet()
        val reached = legs.mapNotNull { level.of(it.finishPlace) }.toSet()

        val result = LinkedHashMap<String, RegionRole>()
        // Insertion order follows the ride order, so a tour's regions read in the order ridden.
        legs.forEach { leg ->
            level.of(leg.startPlace)?.let { result[it] = RegionRole.VISITED }
            level.of(leg.finishPlace)?.let { name ->
                if (name !in visited) result.putIfAbsent(name, RegionRole.CROSSED)
            }
        }
        // `reached` is read above only through `visited`; referenced here so the intent stays
        // visible to a reader: everything reached is either visited or crossed, never dropped.
        check(result.keys.containsAll(reached)) { "a reached region went unclassified" }
        return result
    }

    /**
     * The stops and hops of a tour.
     *
     * Returns null for anything that is not a [SelectionShape.TOUR] — an itinerary of a selection
     * that is not a journey is a list of unrelated places pretending to be one.
     */
    fun itinerary(legs: List<SelectionLeg>): Itinerary? {
        if (shape(legs) != SelectionShape.TOUR) return null

        val stops = buildList {
            add(stopOf(legs.first().startPlace))
            legs.forEach { add(stopOf(it.finishPlace)) }
        }
        val hops = legs.map { ItineraryHop(it.distanceMeters, it.movingMillis) }
        return Itinerary(stops, hops)
    }

    private fun stopOf(parts: PlaceParts?): ItineraryStop = ItineraryStop(
        name = parts?.let(PlaceLabelPolicy::label),
        // The district sits under the name, which is where the administrative data earns its place:
        // "Vijayanagara" is how a reader who has never been there understands where Hampi is.
        region = AdminLevel.DISTRICT.of(parts),
    )

    /**
     * How many genuinely different places the selection stops at.
     *
     * Points within [CHAIN_JOIN_METERS] of one another are the same stop — the same tolerance that
     * decides whether two legs connect, so "the same place" means one thing in this file.
     */
    private fun distinctStops(legs: List<SelectionLeg>): Int {
        val stops = buildList {
            add(legs.first().startLatitude to legs.first().startLongitude)
            legs.forEach { add(it.finishLatitude to it.finishLongitude) }
        }
        val clusters = mutableListOf<Pair<Double, Double>>()
        stops.forEach { stop ->
            val seen = clusters.any { metres(it.first, it.second, stop.first, stop.second) <= CHAIN_JOIN_METERS }
            if (!seen) clusters.add(stop)
        }
        return clusters.size
    }

    private fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        DestinationProgress.haversineMeters(lat1, lng1, lat2, lng2)
}
