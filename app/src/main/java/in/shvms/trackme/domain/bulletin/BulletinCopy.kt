package `in`.shvms.trackme.domain.bulletin

/**
 * SCOPE_1.8.7 §6.1.7 — turning stored facts into the sentence a reader sees, at read time.
 *
 * This is the half that makes "derived from local facts on read" more than a phrase. An entry
 * stores counts and timestamps; the language and the unit system are applied here, every time the
 * feed is drawn. Someone who switches TrackMe to German sees a German bulletin, including the rows
 * that were written months ago in English.
 *
 * Expressed as a pure function over already-formatted *parts* — the caller formats distances and
 * dates, because those need `UnitFormatter` and a locale, and pulling either in here would make
 * this untestable without a device. What lives here is the *choice of sentence*, which is the part
 * that can be wrong.
 */
object BulletinCopy {

    /**
     * The rendered row, or null when the entry cannot be described.
     *
     * Null rather than a placeholder: a bulletin row reading "—" is worse than one that is not
     * there, and an entry whose facts are missing is a bug we want to notice as an absence rather
     * than paper over on screen.
     */
    fun render(entry: BulletinEntry, strings: Strings): Row? {
        return when (entry.kind) {
            BulletinKind.BROADCAST -> {
                // The one kind whose words are stored: an operator wrote them, in one language, and
                // there is nothing to re-derive them from.
                val title = entry.fact(BulletinEntry.FACT_TITLE)
                val body = entry.fact(BulletinEntry.FACT_BODY)
                if (title == null || body == null) null else Row(title, body, entry.fact(BulletinEntry.FACT_LINK))
            }

            BulletinKind.RIDE_SAVED -> {
                val endedAt = entry.fact(FORMATTED_ENDED_AT)
                val distance = entry.fact(FORMATTED_DISTANCE)
                Row(
                    title = strings.rideSavedTitle,
                    body = if (endedAt != null && distance != null) {
                        strings.rideSavedBody(endedAt, distance)
                    } else {
                        strings.rideSavedBodyPlain
                    },
                )
            }

            BulletinKind.WEEKLY_RECAP -> {
                val rides = entry.intFact(BulletinEntry.FACT_RIDE_COUNT) ?: return null
                val distance = entry.fact(FORMATTED_DISTANCE) ?: return null
                // A zero-ride week never reaches the bulletin either. §4.2 N2 is about notifications,
                // but a feed row saying "0 activities" is the same sentence with a quieter delivery.
                if (rides <= 0) return null
                Row(strings.weeklyRecapTitle, strings.weeklyRecapBody(rides, distance))
            }

            BulletinKind.LEVEL_REACHED -> {
                val level = entry.fact(BulletinEntry.FACT_LEVEL_NAME) ?: return null
                Row(strings.levelReachedTitle(level), strings.levelReachedBody)
            }

            BulletinKind.MILESTONE -> {
                val count = entry.intFact(BulletinEntry.FACT_MILESTONE_COUNT) ?: return null
                Row(strings.milestoneTitle(count), strings.milestoneBody)
            }

            BulletinKind.SYNC_PROBLEM -> {
                val unsynced = entry.intFact(BulletinEntry.FACT_UNSYNCED_COUNT) ?: return null
                // A missing date is a real state, not a broken row: the last-success key does not
                // exist until Track 2 has seen one succeed, so the FIRST failing episode after an
                // upgrade or install has no date to quote. Requiring one made that episode render
                // as nothing at all — the row was invisible and the notification was skipped, while
                // the episode was still marked reported. The one case where the user most needs to
                // hear about a broken backup was the one case that said nothing.
                val since = entry.fact(FORMATTED_SINCE)
                if (since == null) {
                    Row(strings.syncProblemTitle, strings.syncProblemBodyNoDate(unsynced))
                } else {
                    Row(strings.syncProblemTitle, strings.syncProblemBody(unsynced, since))
                }
            }

            BulletinKind.RETURN_NOTICE -> {
                val days = entry.intFact(BulletinEntry.FACT_DAYS_AWAY) ?: return null
                Row(strings.returnNoticeTitle, strings.returnNoticeBody(days))
            }

            BulletinKind.VERSION_NOTE -> {
                val version = entry.fact(BulletinEntry.FACT_TITLE) ?: return null
                Row(strings.versionNoteTitle(version), strings.versionNoteBody)
            }

            BulletinKind.FORGOTTEN_RIDE -> {
                val elapsed = entry.intFact(BulletinEntry.FACT_ELAPSED_MINUTES) ?: return null
                // Same shape as SYNC_PROBLEM: the clock time is the useful half — "no movement
                // since 15:10" is what lets someone reconstruct what happened — but a row that
                // vanishes because a timestamp would not format is a row that fails exactly when
                // the ride it describes was strangest.
                val since = entry.fact(FORMATTED_SINCE)
                if (since != null) {
                    Row(strings.forgottenRideTitle, strings.forgottenRideBody(elapsed, since))
                } else {
                    Row(strings.forgottenRideTitle, strings.forgottenRideBodyNoTime(elapsed))
                }
            }

            BulletinKind.GROUP_STILL_LIVE -> {
                // The group name is stored because it is the only way to tell two groups apart
                // months later, and it is text the user already sees. A group whose name did not
                // survive still gets a row: "which group" is less important than "you were live".
                val name = entry.fact(BulletinEntry.FACT_GROUP_NAME)
                if (name != null) {
                    Row(strings.groupStillLiveTitle, strings.groupStillLiveBody(name))
                } else {
                    Row(strings.groupStillLiveTitle, strings.groupStillLiveBodyNoName)
                }
            }
        }
    }

    /**
     * Keys the *caller* fills in with locale-and-unit-formatted values just before rendering.
     *
     * They are not persisted — they are derived from the stored numbers on every read, which is the
     * whole point. Persisting them would put formatted text back in the store by the side door.
     */
    const val FORMATTED_DISTANCE = "formatted_distance"
    const val FORMATTED_ENDED_AT = "formatted_ended_at"
    const val FORMATTED_SINCE = "formatted_since"

    data class Row(val title: String, val body: String, val link: String? = null)

    /**
     * The strings a row needs, as a small interface rather than the whole `AppStrings`.
     *
     * So the copy decisions can be tested against a fake without constructing a localisation table,
     * and so it is obvious at a glance exactly which strings this file can reach.
     */
    interface Strings {
        val rideSavedTitle: String
        val rideSavedBodyPlain: String
        fun rideSavedBody(endedAt: String, distance: String): String
        val weeklyRecapTitle: String
        fun weeklyRecapBody(rides: Int, distance: String): String
        fun levelReachedTitle(level: String): String
        val levelReachedBody: String
        fun milestoneTitle(count: Int): String
        val milestoneBody: String
        val syncProblemTitle: String
        fun syncProblemBody(unsynced: Int, since: String): String
        /** Used when there is no trustworthy last-success date — see the SYNC_PROBLEM branch. */
        fun syncProblemBodyNoDate(unsynced: Int): String
        fun versionNoteTitle(version: String): String
        val versionNoteBody: String
        val returnNoticeTitle: String
        fun returnNoticeBody(days: Int): String
        val forgottenRideTitle: String
        fun forgottenRideBody(elapsedMinutes: Int, stillSince: String): String
        /** Used when the stillness start cannot be formatted — see the FORGOTTEN_RIDE branch. */
        fun forgottenRideBodyNoTime(elapsedMinutes: Int): String
        val groupStillLiveTitle: String
        fun groupStillLiveBody(groupName: String): String
        val groupStillLiveBodyNoName: String
    }
}
