package `in`.shvms.trackme.domain.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SCOPE_1.8.7 §6.1.7 — the bulletin's copy decisions.
 *
 * The bulletin is what makes §6.0's one-per-week cap survivable rather than limiting: everything
 * the budget refuses lands here instead. So the rows have to be worth reading, and the two ways
 * they can fail — a row that says nothing, and a row that says something false — are both here.
 */
class BulletinCopyTest {

    /** A fake rather than the real 4,000-line table: this file is about the choice of sentence. */
    private object FakeStrings : BulletinCopy.Strings {
        override val rideSavedTitle = "Your ride was saved"
        override val rideSavedBodyPlain = "The app closed while you were recording."
        override fun rideSavedBody(endedAt: String, distance: String) =
            "Recording stopped at $endedAt. $distance was kept."
        override val weeklyRecapTitle = "Last week"
        override fun weeklyRecapBody(rides: Int, distance: String) = "$rides activities, $distance."
        override fun levelReachedTitle(level: String) = "You reached $level"
        override val levelReachedBody = "Keep going."
        override fun milestoneTitle(count: Int) = "$count rides"
        override val milestoneBody = "A milestone."
        override val syncProblemTitle = "Backup is not working"
        override fun syncProblemBody(unsynced: Int, since: String) =
            "$unsynced activities have not reached your cloud backup since $since."
        override fun versionNoteTitle(version: String) = "TrackMe $version"
        override val versionNoteBody = "See what changed."
        override fun syncProblemBodyNoDate(unsynced: Int) = "$unsynced not backed up."
        override val returnNoticeTitle = "Your rides are still here"
        override fun returnNoticeBody(days: Int) = "Last activity $days days ago."
        override val forgottenRideTitle = "Still recording"
        override fun forgottenRideBody(elapsedMinutes: Int, stillSince: String) =
            "$elapsedMinutes min recorded. No movement since $stillSince."
        override fun forgottenRideBodyNoTime(elapsedMinutes: Int) =
            "$elapsedMinutes min recorded, with no movement for a while."
        override val groupStillLiveTitle = "Still sharing"
        override fun groupStillLiveBody(groupName: String) = "Still visible in $groupName."
        override val groupStillLiveBodyNoName = "Still visible in a live group."
    }

    private fun entry(kind: BulletinKind, facts: Map<String, String>) =
        BulletinEntry(id = "e1", kind = kind, createdAtMillis = 1_000, facts = facts)

    @Test
    fun `a broadcast keeps the words the operator wrote`() {
        // The one kind whose text is stored rather than derived: a person wrote it, in one
        // language, and there is nothing to re-derive it from.
        val row = BulletinCopy.render(
            entry(BulletinKind.BROADCAST, mapOf(
                BulletinEntry.FACT_TITLE to "Cloud sync is paused",
                BulletinEntry.FACT_BODY to "Back in about two hours.",
                BulletinEntry.FACT_LINK to "https://trackme.shvms.in/blogs",
            )),
            FakeStrings,
        )
        assertEquals(
            BulletinCopy.Row("Cloud sync is paused", "Back in about two hours.", "https://trackme.shvms.in/blogs"),
            row,
        )
    }

    @Test
    fun `a broadcast missing its text is dropped rather than shown empty`() {
        assertNull(BulletinCopy.render(entry(BulletinKind.BROADCAST, mapOf(BulletinEntry.FACT_TITLE to "t")), FakeStrings))
        assertNull(BulletinCopy.render(entry(BulletinKind.BROADCAST, emptyMap()), FakeStrings))
    }

    @Test
    fun `everything else is rendered from facts, so it follows the app's language`() {
        // The property that justifies storing numbers instead of sentences: a row written months
        // ago in English renders in German the moment the user switches language.
        val row = BulletinCopy.render(
            entry(BulletinKind.WEEKLY_RECAP, mapOf(
                BulletinEntry.FACT_RIDE_COUNT to "3",
                BulletinCopy.FORMATTED_DISTANCE to "41.2 km",
            )),
            FakeStrings,
        )
        assertEquals(BulletinCopy.Row("Last week", "3 activities, 41.2 km."), row)
    }

    @Test
    fun `a zero-ride week never reaches the feed either`() {
        // §4.2 N2 is written about notifications, but a feed row reading "0 activities" is the same
        // sentence with a quieter delivery. A quieter way to tell someone they did nothing is still
        // telling them they did nothing.
        assertNull(
            BulletinCopy.render(
                entry(BulletinKind.WEEKLY_RECAP, mapOf(
                    BulletinEntry.FACT_RIDE_COUNT to "0",
                    BulletinCopy.FORMATTED_DISTANCE to "0 km",
                )),
                FakeStrings,
            )
        )
    }

    @Test
    fun `a saved ride falls back to the plain sentence rather than half of one`() {
        assertEquals(
            BulletinCopy.Row("Your ride was saved", "Recording stopped at 14:32. 12.3 km was kept."),
            BulletinCopy.render(
                entry(BulletinKind.RIDE_SAVED, mapOf(
                    BulletinCopy.FORMATTED_ENDED_AT to "14:32",
                    BulletinCopy.FORMATTED_DISTANCE to "12.3 km",
                )),
                FakeStrings,
            ),
        )
        assertEquals(
            BulletinCopy.Row("Your ride was saved", "The app closed while you were recording."),
            BulletinCopy.render(
                entry(BulletinKind.RIDE_SAVED, mapOf(BulletinCopy.FORMATTED_ENDED_AT to "14:32")),
                FakeStrings,
            ),
        )
    }

    @Test
    fun `a sync problem with no date still renders`() {
        // Codex review finding 3. The last-success key does not exist until a sync has succeeded,
        // so the first failing episode after an install or upgrade has no date. Requiring one made
        // that row invisible — for the user who has never had a working backup.
        assertEquals(
            BulletinCopy.Row("Backup is not working", "3 not backed up."),
            BulletinCopy.render(
                entry(BulletinKind.SYNC_PROBLEM, mapOf(BulletinEntry.FACT_UNSYNCED_COUNT to "3")),
                FakeStrings,
            ),
        )
    }

    @Test
    fun `a sent return notice is in the feed`() {
        // §6.1.7: "a copy of every notification actually sent". A Class C notice that interrupted
        // someone and cannot then be found is the exact failure the bulletin exists to prevent.
        assertEquals(
            BulletinCopy.Row("Your rides are still here", "Last activity 30 days ago."),
            BulletinCopy.render(
                entry(BulletinKind.RETURN_NOTICE, mapOf(BulletinEntry.FACT_DAYS_AWAY to "30")),
                FakeStrings,
            ),
        )
    }

    @Test
    fun `a forgotten-ride row falls back when the clock time is missing`() {
        // Same shape as the sync-problem fallback: the clock time is the useful half, but a row
        // that vanishes because a timestamp would not format fails exactly when the ride it
        // describes was strangest.
        assertEquals(
            BulletinCopy.Row("Still recording", "222 min recorded. No movement since 15:10."),
            BulletinCopy.render(
                entry(BulletinKind.FORGOTTEN_RIDE, mapOf(
                    BulletinEntry.FACT_ELAPSED_MINUTES to "222",
                    BulletinCopy.FORMATTED_SINCE to "15:10",
                )),
                FakeStrings,
            ),
        )
        assertEquals(
            BulletinCopy.Row("Still recording", "222 min recorded, with no movement for a while."),
            BulletinCopy.render(
                entry(BulletinKind.FORGOTTEN_RIDE, mapOf(BulletinEntry.FACT_ELAPSED_MINUTES to "222")),
                FakeStrings,
            ),
        )
    }

    @Test
    fun `a group-presence row renders with or without the group name`() {
        // "Which group" is less important than "you were live", so a lost name still gets a row.
        assertEquals(
            BulletinCopy.Row("Still sharing", "Still visible in Sunday Riders."),
            BulletinCopy.render(
                entry(BulletinKind.GROUP_STILL_LIVE, mapOf(BulletinEntry.FACT_GROUP_NAME to "Sunday Riders")),
                FakeStrings,
            ),
        )
        assertEquals(
            BulletinCopy.Row("Still sharing", "Still visible in a live group."),
            BulletinCopy.render(entry(BulletinKind.GROUP_STILL_LIVE, emptyMap()), FakeStrings),
        )
    }

    @Test
    fun `every kind either renders or is dropped, and none of them crashes`() {
        // Exhaustive over the vocabulary. A kind added later without a branch here would be a
        // silent blank row, which is the failure mode a feed is least likely to have noticed.
        BulletinKind.entries.forEach { kind ->
            BulletinCopy.render(entry(kind, emptyMap()), FakeStrings)
        }
    }

    @Test
    fun `an entry with missing facts is dropped, not padded with a placeholder`() {
        // A row reading "—" is worse than a row that is not there: it looks like the app knows
        // something and will not say it.
        listOf(
            BulletinKind.LEVEL_REACHED,
            BulletinKind.MILESTONE,
            BulletinKind.VERSION_NOTE,
            BulletinKind.WEEKLY_RECAP,
            BulletinKind.RETURN_NOTICE,
            // GROUP_STILL_LIVE is deliberately absent: it renders without a group name, because
            // "which group" is less important than "you were live".
            BulletinKind.FORGOTTEN_RIDE,
        ).forEach { kind ->
            assertNull(kind.name, BulletinCopy.render(entry(kind, emptyMap()), FakeStrings))
        }
    }

    /**
     * The raw values are the persisted format, and the iOS twin pins the identical list. A rename
     * on one platform would silently drop every stored row of that kind on the other after a
     * restore — and pinning it on only one side means only one side's rename is caught.
     */
    @Test
    fun `the kind vocabulary matches iOS exactly`() {
        assertEquals(
            listOf(
                "BROADCAST", "RIDE_SAVED", "SYNC_PROBLEM", "WEEKLY_RECAP", "LEVEL_REACHED",
                "MILESTONE", "VERSION_NOTE", "RETURN_NOTICE", "FORGOTTEN_RIDE", "GROUP_STILL_LIVE",
            ),
            BulletinKind.entries.map { it.name },
        )
    }

    @Test
    fun `the kind vocabulary is parsed exactly`() {
        BulletinKind.entries.forEach { assertEquals(it, BulletinKind.parse(it.name)) }
        listOf("broadcast", "Broadcast", "UNKNOWN", "", null).forEach { assertNull(BulletinKind.parse(it)) }
    }
}
