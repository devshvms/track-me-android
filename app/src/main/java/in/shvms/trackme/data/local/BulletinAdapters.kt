package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import `in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager
import `in`.shvms.trackme.domain.stats.WeeklyRecap

/**
 * SCOPE_1.8.7 §6.1.7 — the adapters that turn facts into bulletin rows.
 *
 * "Plus a copy of every notification actually sent" is the part of §6.1.7 that these implement, and
 * the reason they exist as one file rather than as calls scattered through the notifiers: the
 * bulletin is only trustworthy if it is *complete*. A fact that interrupted someone and then does
 * not appear in the feed is worse than one that never interrupted at all — they saw it, swiped it,
 * and now cannot find it.
 *
 * Every id here is derived from the fact itself rather than from a clock, so the same fact arriving
 * by two routes — a broadcast by push and by reconcile, a recap notified and then read in-app —
 * produces one row.
 */
object BulletinAdapters {

    fun from(broadcast: OperatorBroadcast): BulletinEntry = BulletinEntry(
        // The broadcast's own id: it is already unique and already the dedupe key everywhere else.
        id = "broadcast:${broadcast.id}",
        kind = BulletinKind.BROADCAST,
        createdAtMillis = broadcast.createdAtMillis,
        facts = buildMap {
            put(BulletinEntry.FACT_TITLE, broadcast.title)
            put(BulletinEntry.FACT_BODY, broadcast.body)
            put(BulletinEntry.FACT_TAG, broadcast.tag.name)
            broadcast.learnMoreUrl?.let { put(BulletinEntry.FACT_LINK, it) }
        },
    )

    fun from(recap: WeeklyRecap): BulletinEntry = BulletinEntry(
        // Keyed by week, so a recap that is both notified and read in-app is one row.
        id = "recap:${recap.weekStartEpochDay}",
        kind = BulletinKind.WEEKLY_RECAP,
        // The week it describes, not the moment it was noticed — otherwise a recap read late sorts
        // above facts that actually happened after it.
        createdAtMillis = recap.weekStartEpochDay * MILLIS_PER_DAY,
        facts = mapOf(
            BulletinEntry.FACT_RIDE_COUNT to recap.rideCount.toString(),
            BulletinEntry.FACT_DISTANCE_METERS to recap.distanceMeters.toString(),
            BulletinEntry.FACT_STREAK_WEEKS to recap.streakWeeks.toString(),
        ),
    )

    /**
     * One row per recovered ride, keyed by ride id.
     *
     * Per ride rather than per recovery *event*: the notification says "3 rides were saved" because
     * a notification has one line, but the feed has room to say which three, and a user checking
     * whether a particular ride survived is the whole reason to look.
     */
    fun from(summary: OrphanedRideRecoveryManager.RecoverySummary): List<BulletinEntry> =
        summary.recovered.map { ride ->
            BulletinEntry(
                id = "ride-saved:${ride.rideId}",
                kind = BulletinKind.RIDE_SAVED,
                createdAtMillis = ride.endTimeMillis,
                facts = mapOf(
                    BulletinEntry.FACT_ENDED_AT_MILLIS to ride.endTimeMillis.toString(),
                    BulletinEntry.FACT_DISTANCE_METERS to ride.distanceMeters.toString(),
                ),
            )
        }

    private const val MILLIS_PER_DAY = 86_400_000L
}
