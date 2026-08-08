package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.0 **D6, D8, D9** — the optional start time, and the invariant it must never break.
 */
class GroupStartReminderTest {

    private val now = 1_785_000_000_000L
    private val minute = 60_000L

    @Test
    fun `no start time means nothing to schedule`() {
        // D6: both start time and destination are optional. The minimum path stays
        // create -> share -> join -> go, and a seven-deep prerequisite chain was the single
        // biggest friction risk in this feature.
        assertEquals(GroupStartReminder.Decision.None, GroupStartReminder.decide(null, now))
        assertEquals(GroupStartReminder.Decision.None, GroupStartReminder.decide(0L, now))
    }

    @Test
    fun `a reminder is scheduled fifteen minutes before the start`() {
        // §2.3: "We'll remind you 15 minutes before."
        val startAt = now + 60 * minute
        val decision = GroupStartReminder.decide(startAt, now) as GroupStartReminder.Decision.Schedule
        assertEquals(startAt - 15 * minute, decision.atEpochMillis)
    }

    @Test
    fun `a start time already inside the lead window schedules nothing`() {
        // Firing immediately would be noise: the user is either already here, or the start is
        // minutes away and they can see it.
        assertEquals(GroupStartReminder.Decision.None, GroupStartReminder.decide(now + 14 * minute, now))
        assertEquals(GroupStartReminder.Decision.None, GroupStartReminder.decide(now + 15 * minute, now))
        assertTrue(GroupStartReminder.decide(now + 16 * minute, now) is GroupStartReminder.Decision.Schedule)
    }

    @Test
    fun `a start time in the past schedules nothing and is not an error`() {
        // §8: the group simply waits for the leader. A time already gone is that case with the
        // reminder skipped — it must not block creating the group.
        assertEquals(GroupStartReminder.Decision.None, GroupStartReminder.decide(now - 60 * minute, now))
        assertTrue(GroupStartReminder.isUsableStartTime(now - 60 * minute, now + 4 * 60 * minute))
    }

    @Test
    fun `a start time after the group expires is the one nonsensical case`() {
        val expiresAt = now + 4 * 60 * minute
        assertTrue(GroupStartReminder.isUsableStartTime(now + 60 * minute, expiresAt))
        assertFalse(GroupStartReminder.isUsableStartTime(expiresAt + minute, expiresAt))
        assertTrue("no start time is always usable", GroupStartReminder.isUsableStartTime(null, expiresAt))
    }

    // --- D9, the invariant ------------------------------------------------------------------------

    @Test
    fun `a scheduled start time can never start sharing`() {
        // D9 is an invariant, not a preference: "Auto-broadcasting someone's location at a
        // calendar time, without them present, is unacceptable and must never be introduced as a
        // convenience."
        //
        // This reads the source rather than trusting a comment, because the convenience argument
        // WILL be made — it is the obvious next feature request — and by the time anyone notices,
        // someone's location has already been broadcast while they were not there.
        // Comments stripped first. This file's own doc comment explains what it must not do, and
        // therefore names every forbidden symbol — the third time in this feature that prose has
        // tripped a source scan (the Lua guard and the landing-page guard hit it too).
        val source = File(sourcePath()).readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        for (forbidden in listOf(
            "GroupSessionManager",
            "TrackingService",
            "startGroup",
            "ACTION_START",
            "presenceMode",
            "updatePosition",
        )) {
            assertFalse(
                "GroupStartReminder references \"$forbidden\" — D9 forbids a scheduled time " +
                    "reaching anything that starts sharing",
                source.contains(forbidden),
            )
        }
        assertTrue(GroupStartReminder.neverAutoStarts)
    }

    @Test
    fun `the decision type can only ask for a notification`() {
        // The return type is the enforcement: there is no branch that means "start". If a Start
        // variant is ever added, this fails.
        //
        // Uses java reflection rather than Kotlin's sealedSubclasses — kotlin-reflect is not on
        // the unit-test classpath, and adding it for one assertion is not worth a dependency.
        val variants = GroupStartReminder.Decision::class.java.permittedSubclasses
            ?.map { it.simpleName }
            ?.toSet()
            ?: emptySet()
        assertEquals(setOf("Schedule", "None"), variants)
    }

    private fun sourcePath(): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/domain/group/GroupStartReminder.kt"
        while (dir != null) {
            val a = File(dir, rel)
            if (a.exists()) return a.path
            val b = File(dir, rel.removePrefix("app/"))
            if (b.exists()) return b.path
            dir = dir.parentFile
        }
        throw AssertionError("GroupStartReminder.kt not found from ${File("").absolutePath}")
    }
}
