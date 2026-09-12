package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.export.template.ExportTemplateId
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.export.template.PlaceParts
import `in`.shvms.trackme.ui.localization.AppStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * SCOPE_1.8.9 Part 2 — the selection-to-content path, with the geocoder stubbed.
 *
 * The geocoder is a lambda here for the same reason `PlaceLabelResolver` takes one: an aggregate
 * export that needed a live network to be tested would never be tested at the case that matters,
 * which is the one where the lookup fails.
 */
class ExportTemplateAggregateTest {

    private val strings = AppStrings()
    private val locale = Locale.UK

    private fun point(lat: Double, lng: Double) =
        GPSPointEntity(rideId = 1, latitude = lat, longitude = lng, altitude = 0.0, accuracy = 5f, speed = 5f, timestamp = 0L, isPaused = false)

    private fun route(id: Long, from: Pair<Double, Double>, to: Pair<Double, Double>, metres: Double, label: String): ComparisonRoute {
        val points = listOf(point(from.first, from.second), point(to.first, to.second))
        val ride = RideWithPoints(
            ride = RideEntity(id = id, startTime = id * 1_000_000, endTime = id * 1_000_000 + 3_600_000, dashboardActiveDurationMillis = 3_600_000),
            points = points,
        )
        return ComparisonRoute(ride = ride, label = label, points = points)
    }

    private val bengaluru = 12.9716 to 77.5946
    private val hampi = 15.3350 to 76.4600
    private val badami = 15.9149 to 75.6768

    private val tourRoutes = listOf(
        route(1, bengaluru, hampi, 340_000.0, "A"),
        route(2, hampi, badami, 140_000.0, "B"),
    )

    private val places = mapOf(
        bengaluru to PlaceParts(locality = "Bengaluru", subAdminArea = "Bengaluru Urban", adminArea = "Karnataka", countryName = "India"),
        hampi to PlaceParts(locality = "Hampi", subAdminArea = "Vijayanagara", adminArea = "Karnataka", countryName = "India"),
        badami to PlaceParts(locality = "Badami", subAdminArea = "Bagalkot", adminArea = "Karnataka", countryName = "India"),
    )

    private val geocode: suspend (Double, Double) -> PlaceParts? = { lat, lng ->
        places.entries.firstOrNull { kotlin.math.abs(it.key.first - lat) < 0.01 && kotlin.math.abs(it.key.second - lng) < 0.01 }?.value
    }

    @Test
    fun `a tour offers the Itinerary`() {
        val legs = ExportTemplateAggregate.legs(tourRoutes)
        assertTrue(ExportTemplateId.ITINERARY in ExportTemplateAggregate.available(legs))
    }

    /**
     * The whole argument of §9.3 is that a template which cannot honestly fill itself is not
     * offered. A commute has no sequence, so the Itinerary must not appear for one.
     */
    @Test
    fun `a commute does not offer the Itinerary`() {
        val home = 12.9716 to 77.5946
        val office = 12.9352 to 77.6245
        val commutes = (1L..6L).map { route(it, home, office, 8_000.0, "R$it") }
        assertTrue(ExportTemplateId.ITINERARY !in ExportTemplateAggregate.available(ExportTemplateAggregate.legs(commutes)))
    }

    @Test
    fun `shape detection needs no geocoder`() {
        // Offline, the legs carry no places at all — and the right template is still offered.
        val legs = ExportTemplateAggregate.legs(tourRoutes)
        assertTrue(legs.all { it.startPlace == null && it.finishPlace == null })
        assertTrue(ExportTemplateId.ITINERARY in ExportTemplateAggregate.available(legs))
    }

    @Test
    fun `places and coverage come from the geocoded ends`() = runBlocking {
        val legs = ExportTemplateAggregate.legsWithPlaces(tourRoutes, geocode)
        val content = ExportTemplateAggregate.build(tourRoutes, legs, strings, imperial = false, locale = locale, dateLine = "MAR 2026", link = null)

        assertNotNull(content.itinerary)
        assertEquals(listOf("Bengaluru", "Hampi", "Badami"), content.itinerary!!.stops.map { it.name })
        assertEquals(listOf("Bengaluru Urban", "Vijayanagara", "Bagalkot"), content.itinerary!!.stops.map { it.region })
        assertEquals("Karnataka", content.coverageLine)
    }

    /**
     * A lookup that fails must cost place names, never the export. Offline is the ordinary case for
     * this app, not an edge one.
     */
    @Test
    fun `a geocoder that always fails still produces a rendering`() = runBlocking {
        val legs = ExportTemplateAggregate.legsWithPlaces(tourRoutes) { _, _ -> throw IllegalStateException("offline") }
        val content = ExportTemplateAggregate.build(tourRoutes, legs, strings, imperial = false, locale = locale, dateLine = "MAR 2026", link = null)

        assertNotNull("the chain is still a chain without names", content.itinerary)
        assertEquals(listOf(null, null, null), content.itinerary!!.stops.map { it.name })
        assertNull("no regions means no coverage claim", content.coverageLine)
    }

    /**
     * §9.3's aggregate Trace is "N routes on one ground", and the thing that makes it readable is
     * that ride 2's line is not ride 1's colour. The palette is per *run*, not per ride, because one
     * ride with a GPS gap contributes two runs — and both of them have to stay its colour.
     */
    @Test
    fun `every selected ride reaches the canvas in its own colour`() {
        val legs = ExportTemplateAggregate.legs(tourRoutes)
        val content = ExportTemplateAggregate.build(tourRoutes, legs, strings, imperial = false, locale = locale, dateLine = "", link = null)

        assertEquals("one run per selected ride", tourRoutes.size, content.runs.size)
        assertEquals("a colour for every run", content.runs.size, content.runPalette?.size)
        assertEquals("two rides, two colours", 2, content.runPalette!!.distinct().size)
    }

    @Test
    fun `a ride with no drawable geometry contributes no line`() {
        val single = listOf(route(9, bengaluru, bengaluru, 0.0, "A").let { it.copy(points = it.points.take(1)) })
        val content = ExportTemplateAggregate.build(single, ExportTemplateAggregate.legs(single), strings, imperial = false, locale = locale, dateLine = "", link = null)
        assertTrue(content.runs.isEmpty())
        assertTrue(content.runPalette!!.isEmpty())
    }

    /**
     * The date line is the only place the selection says *when*, and a tour that crosses a month
     * boundary has to say so — "3 RIDES · MAR 2026" would be wrong for a trip that ended in April.
     */
    @Test
    fun `the date line spans the months the selection covers`() {
        val month: (Long) -> String = { if (it < 2_500_000) "MAR 2026" else "APR 2026" }
        val oneMonth = ExportTemplateAggregate.dateLine(tourRoutes, strings, locale, month)
        assertEquals("2 RIDES · MAR 2026", oneMonth)

        val across = tourRoutes + route(3, badami, bengaluru, 500_000.0, "C")
        assertEquals("3 RIDES · MAR 2026 – APR 2026", ExportTemplateAggregate.dateLine(across, strings, locale, month))
    }

    /**
     * Naming stops where it would not fit. Four regions is the first selection that has to count,
     * and a count is only ever reached with a plural, which is what keeps the copy correct without
     * plural rules in seven catalogues.
     */
    @Test
    fun `too many regions to name are counted instead`() = runBlocking {
        val wide = listOf(
            route(1, bengaluru, hampi, 0.0, "A"),
            route(2, hampi, badami, 0.0, "B"),
        )
        val places = mapOf(
            bengaluru to PlaceParts(locality = "Bengaluru", subAdminArea = "Bengaluru Urban", adminArea = "Karnataka"),
            hampi to PlaceParts(locality = "Hampi", subAdminArea = "Vijayanagara", adminArea = "Goa"),
            badami to PlaceParts(locality = "Badami", subAdminArea = "Bagalkot", adminArea = "Maharashtra"),
        )
        val threeLegs = ExportTemplateAggregate.legsWithPlaces(wide) { lat, lng ->
            places.entries.firstOrNull { kotlin.math.abs(it.key.first - lat) < 0.01 && kotlin.math.abs(it.key.second - lng) < 0.01 }?.value
        }
        assertEquals("Karnataka · Goa · Maharashtra", ExportTemplateAggregate.coverageLine(threeLegs, strings, locale))

        val fourth = threeLegs + threeLegs.last().copy(
            startPlace = PlaceParts(adminArea = "Telangana"), finishPlace = PlaceParts(adminArea = "Kerala"),
        )
        assertEquals("5 regions", ExportTemplateAggregate.coverageLine(fourth, strings, locale))
    }

    @Test
    fun `the hero is the sum of the legs`() = runBlocking {
        val legs = ExportTemplateAggregate.legsWithPlaces(tourRoutes, geocode)
            .mapIndexed { i, leg -> leg.copy(distanceMeters = if (i == 0) 340_000.0 else 140_000.0) }
        val content = ExportTemplateAggregate.build(tourRoutes, legs, strings, imperial = false, locale = locale, dateLine = "", link = null)
        // Asserted against the shared formatter rather than a literal: the point is that an
        // aggregate total reads in the same precision as the single-ride figure it is the sum of,
        // and hard-coding the digits here would let the two drift while this test still passed.
        assertEquals(UnitFormatter.rideDistanceValue(480_000.0, false, locale), content.heroValue)
        assertEquals(UnitFormatter.distanceUnitLabel(false), content.heroUnit)
    }
}
