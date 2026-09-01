package `in`.shvms.trackme.domain.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** TASK-276: the achieved-on date and persona split are derived, so they must track the rides. */
class GamificationLedgerTest {

    private fun ride(at: String, persona: String, minutes: Long, metres: Double = 1_000.0) =
        GamificationLedger.RideFact(
            atEpochMillis = Instant.parse(at).toEpochMilli(),
            personaRaw = persona,
            activeDurationMillis = minutes * 60_000L,
            distanceMeters = metres,
        )

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun ledger(vararg rides: GamificationLedger.RideFact) =
        GamificationLedger.derive(rides.toList()).associateBy { it.levelId }

    @Test
    fun `no rides means nothing is achieved, including level one`() {
        val l = ledger()
        assertNull(l.getValue("level_1").achievedAtEpochMillis)
        assertNull(l.getValue("level_2").achievedAtEpochMillis)
        assertEquals(emptyList<Any>(), l.getValue("level_1").personaSplit)
    }

    @Test
    fun `level one is the first ride, not a threshold crossing`() {
        // Its threshold is zero, so "reached at 0 minutes" would be true before doing anything.
        val l = ledger(
            ride("2026-06-02T07:00:00Z", "CYCLING", 30),
            ride("2026-06-09T07:00:00Z", "CYCLING", 30),
        )
        assertEquals(at("2026-06-02T07:00:00Z"), l.getValue("level_1").achievedAtEpochMillis)
    }

    @Test
    fun `a level is dated by the ride that crossed it`() {
        // level_2 needs 120 minutes; the third ride is the one that gets there.
        val l = ledger(
            ride("2026-06-02T07:00:00Z", "CYCLING", 50),
            ride("2026-06-09T07:00:00Z", "CYCLING", 50),
            ride("2026-06-16T07:00:00Z", "WALKING", 30),
            ride("2026-06-23T07:00:00Z", "CYCLING", 40),
        )
        assertEquals(at("2026-06-16T07:00:00Z"), l.getValue("level_2").achievedAtEpochMillis)
    }

    @Test
    fun `the split covers everything up to the crossing and no further`() {
        val l = ledger(
            ride("2026-06-02T07:00:00Z", "CYCLING", 50, 12_000.0),
            ride("2026-06-09T07:00:00Z", "CYCLING", 50, 11_000.0),
            ride("2026-06-16T07:00:00Z", "WALKING", 30, 2_500.0),
            // after the crossing — must not appear in level_2's split
            ride("2026-06-23T07:00:00Z", "CYCLING", 40, 9_000.0),
        )
        val split = l.getValue("level_2").personaSplit
        assertEquals(2, split.size)
        assertEquals("CYCLING", split[0].personaRaw)
        assertEquals(100 * 60_000L, split[0].activeDurationMillis)
        assertEquals(23_000.0, split[0].distanceMeters, 0.001)
        assertEquals("WALKING", split[1].personaRaw)
        assertEquals(30 * 60_000L, split[1].activeDurationMillis)
    }

    @Test
    fun `an earlier level keeps its answer when a later one is reached`() {
        val l = ledger(
            ride("2026-06-02T07:00:00Z", "CYCLING", 130),
            ride("2026-07-02T07:00:00Z", "CYCLING", 500),
        )
        // level_2 was crossed by the first ride and must not absorb the second.
        assertEquals(at("2026-06-02T07:00:00Z"), l.getValue("level_2").achievedAtEpochMillis)
        assertEquals(130 * 60_000L, l.getValue("level_2").personaSplit.single().activeDurationMillis)
        assertEquals(at("2026-07-02T07:00:00Z"), l.getValue("level_3").achievedAtEpochMillis)
        assertEquals(630 * 60_000L, l.getValue("level_3").personaSplit.single().activeDurationMillis)
    }

    @Test
    fun `an unreached level has no date and no split`() {
        val l = ledger(ride("2026-06-02T07:00:00Z", "CYCLING", 130))
        assertNull(l.getValue("level_3").achievedAtEpochMillis)
        assertEquals(emptyList<Any>(), l.getValue("level_3").personaSplit)
    }

    @Test
    fun `out of order input does not change the answer`() {
        val forwards = ledger(
            ride("2026-06-02T07:00:00Z", "CYCLING", 50),
            ride("2026-06-09T07:00:00Z", "CYCLING", 50),
            ride("2026-06-16T07:00:00Z", "WALKING", 30),
        )
        val backwards = ledger(
            ride("2026-06-16T07:00:00Z", "WALKING", 30),
            ride("2026-06-09T07:00:00Z", "CYCLING", 50),
            ride("2026-06-02T07:00:00Z", "CYCLING", 50),
        )
        assertEquals(
            forwards.getValue("level_2").achievedAtEpochMillis,
            backwards.getValue("level_2").achievedAtEpochMillis,
        )
    }

    @Test
    fun `minutes round once, matching the engine rather than drifting below it`() {
        // Three rides of 40.5 minutes are 121 minutes together, which reaches level_2. Rounding each
        // ride down first would give 120 and land on the threshold by luck rather than arithmetic.
        val rides = List(3) { i ->
            GamificationLedger.RideFact(
                atEpochMillis = at("2026-06-0${i + 1}T07:00:00Z"),
                personaRaw = "CYCLING",
                activeDurationMillis = 40 * 60_000L + 30_000L,
                distanceMeters = 1_000.0,
            )
        }
        val engineMinutes = rides.sumOf { it.activeDurationMillis } / 60_000L
        assertEquals(121L, engineMinutes)
        val l = GamificationLedger.derive(rides).associateBy { it.levelId }
        assertEquals(at("2026-06-03T07:00:00Z"), l.getValue("level_2").achievedAtEpochMillis)
    }

    @Test
    fun `ties between personas order stably`() {
        val l = ledger(
            ride("2026-06-02T07:00:00Z", "WALKING", 60),
            ride("2026-06-03T07:00:00Z", "CYCLING", 60),
        )
        val split = l.getValue("level_2").personaSplit
        assertEquals(listOf("CYCLING", "WALKING"), split.map { it.personaRaw })
    }
}
