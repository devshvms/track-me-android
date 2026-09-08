package `in`.shvms.trackme.domain.notifications

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.7 §6.1.3 scenario 12a and §6.1.2 scenario 10b, proved against the frozen vectors.
 *
 * `ride-history-profile-v1.json` is canonical in `track-me-web/tests/fixtures` and copied verbatim
 * to both clients. Most of these cases assert a *refusal*, which is the half that matters: the
 * distinction between the shipped 12a and the cut 12 is entirely about when the app declines to
 * guess, and a platform that guesses where the other stays quiet is the app appearing to watch more
 * closely on one phone than on the other.
 */
class RideHistoryProfileVectorsTest {

    private val vectors: JSONObject =
        JSONObject(File("src/test/resources/ride-history-profile-v1.json").readText())

    private fun samples(array: JSONArray): List<RideHistoryProfile.Sample> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RideHistoryProfile.Sample(
                dayOfWeek = o.getInt("day_of_week"),
                hour = o.getInt("hour"),
                activeMinutes = o.getLong("active_minutes"),
                persona = o.getString("persona"),
            )
        }

    @Test
    fun `the constants in the vector file are the constants in the code`() {
        val constants = vectors.getJSONObject("constants")
        assertEquals(
            constants.getInt("min_rides_for_suggestion"),
            RideHistoryProfile.MIN_RIDES_FOR_SUGGESTION,
        )
        assertEquals(
            constants.getDouble("min_dominant_day_share"),
            RideHistoryProfile.MIN_DOMINANT_DAY_SHARE,
            0.0001,
        )
        assertEquals(
            constants.getInt("min_rides_on_dominant_day"),
            RideHistoryProfile.MIN_RIDES_ON_DOMINANT_DAY,
        )
    }

    @Test
    fun `every suggested-slot vector agrees`() {
        val cases = vectors.getJSONArray("suggest_slot")
        assertTrue("vectors went missing", cases.length() >= 8)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            val actual = RideHistoryProfile.suggestSlot(samples(case.getJSONArray("samples")))

            if (case.isNull("expected")) {
                assertNull("$description: expected no suggestion, got $actual", actual)
                continue
            }

            val expected = case.getJSONObject("expected")
            assertNotNull("$description: expected a suggestion, got none", actual)
            assertEquals("$description: day", expected.getInt("day_of_week"), actual!!.dayOfWeek)
            assertEquals("$description: hour", expected.getInt("hour"), actual.hour)
            assertEquals("$description: persona", expected.getString("persona"), actual.persona)
            assertEquals("$description: ride count", expected.getInt("ride_count"), actual.rideCount)
        }
    }

    @Test
    fun `every typical-active-minutes vector agrees`() {
        val cases = vectors.getJSONArray("typical_active_minutes")
        assertTrue("vectors went missing", cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            val actual = RideHistoryProfile.typicalActiveMinutes(
                samples(case.getJSONArray("samples")),
                if (case.isNull("persona")) null else case.getString("persona"),
            )
            if (case.isNull("expected")) {
                assertNull("$description: expected null, got $actual", actual)
            } else {
                assertEquals(description, case.getLong("expected"), actual)
            }
        }
    }

    @Test
    fun `every start-button proximity vector agrees`() {
        val cases = vectors.getJSONArray("start_button_proximity")
        assertTrue("vectors went missing", cases.length() >= 9)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val description = case.getString("description")
            val actual = StartButtonProximity.line(
                minutesToNextLevel =
                    if (case.isNull("minutes_to_next_level")) null else case.getLong("minutes_to_next_level"),
                nextLevelName =
                    if (case.isNull("next_level_name")) null else case.getString("next_level_name"),
                typicalActiveMinutes =
                    if (case.isNull("typical_active_minutes")) null else case.getLong("typical_active_minutes"),
            )
            if (case.isNull("expected")) {
                assertNull("$description: expected no line, got $actual", actual)
                continue
            }
            val expected = case.getJSONObject("expected")
            assertNotNull("$description: expected a line, got none", actual)
            assertEquals("$description: minutes", expected.getLong("minutes"), actual!!.minutes)
            assertEquals("$description: level", expected.getString("level_name"), actual.levelName)
        }
    }

    /**
     * The vectors number weekdays the ISO way. Both platforms' own calendars disagree with that
     * (`Calendar.SUNDAY` is 1, and `Foundation`'s weekday is 1 for Sunday too), so the conversion
     * has to happen at the platform boundary. This asserts the policy itself never sees a 0 or an
     * 8, which is what a missed conversion looks like.
     */
    @Test
    fun `the policy rejects weekday numbers outside the ISO range`() {
        listOf(0, 8, -1).forEach { bad ->
            assertTrue(
                "$bad should not be a valid reminder weekday",
                !ActivityReminder.Settings(enabled = true, dayOfWeek = bad).isValid,
            )
        }
        (1..7).forEach { good ->
            assertTrue(
                "$good should be a valid reminder weekday",
                ActivityReminder.Settings(enabled = true, dayOfWeek = good).isValid,
            )
        }
    }
}
