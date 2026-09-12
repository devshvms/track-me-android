package `in`.shvms.trackme.domain.export.template

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.9 Part 2 §9.3, proved against the frozen vectors.
 *
 * `aggregate-selection-v1.json` is canonical in `track-me-web/tests/fixtures` and copied byte for
 * byte to both clients. The shape of a selection decides which templates a rider is *offered*, so a
 * platform that reads the same three rides as a tour where the other reads a collection is not a
 * rendering difference — it is a different feature on each phone.
 *
 * Part 1 shipped with no vectors and the two sides agreed only because one was ported from the other
 * within a day. This file is what makes the next change to either side fail loudly.
 */
class AggregateSelectionVectorsTest {

    private val vectors = JSONObject(File("src/test/resources/aggregate-selection-v1.json").readText())

    private fun parts(o: JSONObject?): PlaceParts? {
        if (o == null) return null
        fun v(key: String) = o.optString(key, "").takeIf { it.isNotEmpty() }
        return PlaceParts(
            subLocality = v("sub_locality"),
            locality = v("locality"),
            subAdminArea = v("sub_admin_area"),
            adminArea = v("admin_area"),
            thoroughfare = v("thoroughfare"),
            countryName = v("country"),
        )
    }

    private fun legs(array: JSONArray): List<SelectionLeg> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val start = o.getJSONArray("start")
        val finish = o.getJSONArray("finish")
        SelectionLeg(
            startLatitude = start.getDouble(0), startLongitude = start.getDouble(1),
            finishLatitude = finish.getDouble(0), finishLongitude = finish.getDouble(1),
            startPlace = parts(o.optJSONObject("start_place")),
            finishPlace = parts(o.optJSONObject("finish_place")),
            distanceMeters = o.optDouble("distance_meters", 0.0),
            movingMillis = o.optLong("moving_millis", 0L),
        )
    }

    @Test
    fun `the constants in the vector file are the constants in the code`() {
        val constants = vectors.getJSONObject("constants")
        assertEquals(constants.getDouble("chain_join_meters"), AggregateSelection.CHAIN_JOIN_METERS, 0.0)
        assertEquals(constants.getDouble("territory_spread_meters"), AggregateSelection.TERRITORY_SPREAD_METERS, 0.0)
    }

    @Test
    fun `every shape vector holds`() {
        val cases = vectors.getJSONArray("shape")
        (0 until cases.length()).forEach { i ->
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            assertEquals(
                description,
                SelectionShape.valueOf(case.getString("expected").uppercase()),
                AggregateSelection.shape(legs(case.getJSONArray("legs"))),
            )
        }
    }

    @Test
    fun `every region vector holds, in the order ridden`() {
        val cases = vectors.getJSONArray("regions")
        (0 until cases.length()).forEach { i ->
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            val level = AdminLevel.valueOf(case.getString("level").uppercase())
            val actual = AggregateSelection.regions(legs(case.getJSONArray("legs")), level)
            val expected = case.getJSONArray("expected")
            assertEquals("$description — count", expected.length(), actual.size)
            actual.entries.forEachIndexed { index, entry ->
                val want = expected.getJSONObject(index)
                assertEquals("$description — name at $index", want.getString("name"), entry.key)
                assertEquals(
                    "$description — role of ${entry.key}",
                    RegionRole.valueOf(want.getString("role").uppercase()),
                    entry.value,
                )
            }
        }
    }

    @Test
    fun `every itinerary vector holds`() {
        val cases = vectors.getJSONArray("itinerary")
        (0 until cases.length()).forEach { i ->
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            val actual = AggregateSelection.itinerary(legs(case.getJSONArray("legs")))
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(description, actual)
                return@forEach
            }
            checkNotNull(actual) { "$description — expected an itinerary" }
            val stops = expected.getJSONArray("stops")
            assertEquals("$description — stop count", stops.length(), actual.stops.size)
            actual.stops.forEachIndexed { index, stop ->
                val want = stops.getJSONObject(index)
                assertEquals("$description — name at $index", want.opt("name").takeIf { it != JSONObject.NULL }, stop.name)
                assertEquals("$description — region at $index", want.opt("region").takeIf { it != JSONObject.NULL }, stop.region)
            }
            val hops = expected.getJSONArray("hops")
            assertEquals("$description — hop count", hops.length(), actual.hops.size)
            actual.hops.forEachIndexed { index, hop ->
                val want = hops.getJSONObject(index)
                assertEquals("$description — hop $index distance", want.getDouble("distance_meters"), hop.distanceMeters, 0.001)
                assertEquals("$description — hop $index millis", want.getLong("moving_millis"), hop.movingMillis)
            }
            assertEquals("$description — total metres", expected.getDouble("total_meters"), actual.totalMeters, 0.001)
            assertEquals("$description — total millis", expected.getLong("total_millis"), actual.totalMillis)
        }
    }
}
