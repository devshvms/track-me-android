package `in`.shvms.trackme.domain.export.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.8.9 Part 2 — the shape of a selection, and what it is honest to say about it.
 *
 * The coordinates are real: Bengaluru, Hampi, Badami and Goa, which is the touring chain shvm gave
 * as the example this work exists for. Using real geography rather than round numbers is what makes
 * the 25 km chain tolerance and the 50 km spread threshold testable against distances a rider would
 * actually cover — a fabricated 0.1° grid would pass any threshold and prove nothing.
 */
class AggregateSelectionTest {

    private val bengaluru = 12.9716 to 77.5946
    private val hampi = 15.3350 to 76.4600
    private val badami = 15.9149 to 75.6768
    private val goa = 15.2993 to 74.1240

    private fun leg(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        startPlace: PlaceParts? = null,
        finishPlace: PlaceParts? = null,
        distanceMeters: Double = 0.0,
        movingMillis: Long = 0L,
    ) = SelectionLeg(
        startLatitude = from.first, startLongitude = from.second,
        finishLatitude = to.first, finishLongitude = to.second,
        startPlace = startPlace, finishPlace = finishPlace,
        distanceMeters = distanceMeters, movingMillis = movingMillis,
    )

    private val tour = listOf(
        leg(bengaluru, hampi, PlaceParts(locality = "Bengaluru", subAdminArea = "Bengaluru Urban", adminArea = "Karnataka"),
            PlaceParts(locality = "Hampi", subAdminArea = "Vijayanagara", adminArea = "Karnataka"), 340_000.0, 31_200_000),
        leg(hampi, badami, PlaceParts(locality = "Hampi", subAdminArea = "Vijayanagara", adminArea = "Karnataka"),
            PlaceParts(locality = "Badami", subAdminArea = "Bagalkot", adminArea = "Karnataka"), 140_000.0, 12_000_000),
        leg(badami, goa, PlaceParts(locality = "Badami", subAdminArea = "Bagalkot", adminArea = "Karnataka"),
            PlaceParts(locality = "Panaji", subAdminArea = "North Goa", adminArea = "Goa"), 230_000.0, 21_900_000),
    )

    // MARK: - shape

    @Test
    fun `legs that continue one another are a tour`() {
        assertEquals(SelectionShape.TOUR, AggregateSelection.shape(tour))
    }

    @Test
    fun `one ride is never an aggregate`() {
        assertEquals(SelectionShape.COLLECTION, AggregateSelection.shape(tour.take(1)))
        assertEquals(SelectionShape.COLLECTION, AggregateSelection.shape(emptyList()))
    }

    /**
     * The strictness is the point. A selection with one broken link is not one journey, and drawing
     * it as one would invent a leg between Hampi and Goa that nobody rode.
     */
    @Test
    fun `one broken link stops it being a tour`() {
        val broken = listOf(tour[0], tour[2])
        assertEquals(SelectionShape.TERRITORY, AggregateSelection.shape(broken))
    }

    @Test
    fun `the same commute twelve times is a collection, not a map`() {
        val office = 12.9352 to 77.6245
        val home = 12.9716 to 77.5946
        val commutes = (1..12).map { leg(home, office) }
        assertEquals(SelectionShape.COLLECTION, AggregateSelection.shape(commutes))
    }

    @Test
    fun `scattered rides with no chain are a territory`() {
        val scattered = listOf(leg(bengaluru, bengaluru), leg(badami, badami), leg(goa, goa))
        assertEquals(SelectionShape.TERRITORY, AggregateSelection.shape(scattered))
    }

    /**
     * A rider who stops recording at a hotel and restarts at a petrol station next morning has not
     * broken the trip. The tolerance has to survive that; this pins that it does, and that a genuine
     * 100 km gap still does not.
     */
    @Test
    fun `a short gap between recordings still chains, a long one does not`() {
        val nearHampi = 15.4000 to 76.5000   // ~8 km from the Hampi finish
        val shortGap = listOf(leg(bengaluru, hampi), leg(nearHampi, badami))
        assertEquals(SelectionShape.TOUR, AggregateSelection.shape(shortGap))

        val longGap = listOf(leg(bengaluru, hampi), leg(goa, badami))
        assertTrue(AggregateSelection.shape(longGap) != SelectionShape.TOUR)
    }

    // MARK: - regions, shvm's visited/crossed rule

    @Test
    fun `a region a ride starts from is visited`() {
        val districts = AggregateSelection.regions(tour, AdminLevel.DISTRICT)
        assertEquals(RegionRole.VISITED, districts["Bengaluru Urban"])
        assertEquals(RegionRole.VISITED, districts["Vijayanagara"])
        assertEquals(RegionRole.VISITED, districts["Bagalkot"])
    }

    /**
     * North Goa is where the trip ended. No selected ride sets off from it, so the data cannot show
     * the rider stayed — and the template will not claim they did.
     */
    @Test
    fun `a region only ever reached is crossed`() {
        val districts = AggregateSelection.regions(tour, AdminLevel.DISTRICT)
        assertEquals(RegionRole.CROSSED, districts["North Goa"])
    }

    @Test
    fun `visited wins wherever a region is both`() {
        // Vijayanagara is leg 1's finish and leg 2's start.
        assertEquals(RegionRole.VISITED, AggregateSelection.regions(tour, AdminLevel.DISTRICT)["Vijayanagara"])
    }

    @Test
    fun `states roll up the same way`() {
        val states = AggregateSelection.regions(tour, AdminLevel.STATE)
        assertEquals(RegionRole.VISITED, states["Karnataka"])
        assertEquals(RegionRole.CROSSED, states["Goa"])
        assertEquals(2, states.size)
    }

    @Test
    fun `regions read in the order ridden`() {
        assertEquals(
            listOf("Bengaluru Urban", "Vijayanagara", "Bagalkot", "North Goa"),
            AggregateSelection.regions(tour, AdminLevel.DISTRICT).keys.toList(),
        )
    }

    @Test
    fun `a selection with no geocoding claims no regions`() {
        assertTrue(AggregateSelection.regions(listOf(leg(bengaluru, hampi)), AdminLevel.STATE).isEmpty())
    }

    // MARK: - itinerary

    @Test
    fun `a tour yields one more stop than it has hops`() {
        val itinerary = AggregateSelection.itinerary(tour)!!
        assertEquals(4, itinerary.stops.size)
        assertEquals(3, itinerary.hops.size)
        assertEquals(listOf("Bengaluru", "Hampi", "Badami", "Panaji"), itinerary.stops.map { it.name })
        assertEquals(listOf("Bengaluru Urban", "Vijayanagara", "Bagalkot", "North Goa"), itinerary.stops.map { it.region })
    }

    @Test
    fun `the totals are the sum of the hops`() {
        val itinerary = AggregateSelection.itinerary(tour)!!
        assertEquals(710_000.0, itinerary.totalMeters, 0.001)
        assertEquals(65_100_000L, itinerary.totalMillis)
    }

    /**
     * An itinerary of a selection that is not a journey is a list of unrelated places pretending to
     * be one, so it is refused rather than rendered emptily.
     */
    @Test
    fun `anything that is not a tour has no itinerary`() {
        assertNull(AggregateSelection.itinerary(listOf(leg(bengaluru, bengaluru), leg(goa, goa))))
        assertNull(AggregateSelection.itinerary(tour.take(1)))
    }
}
