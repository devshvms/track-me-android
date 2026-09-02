package `in`.shvms.trackme.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TASK-275: how a ride entered the database. Stored as a String so Room needs no converter and an
 * unknown future value degrades to "not recorded here" rather than crashing on read.
 */
object RideSource {
    /** Produced by this app's recorder on this device. Earns levels and milestones. */
    const val RECORDED = "RECORDED"

    /** Parsed from a GPX file. Viewable, exportable and syncable; earns no progress. */
    const val IMPORTED = "IMPORTED"

    /** True only for the one value that may contribute to gamification. */
    fun earnsProgress(source: String?): Boolean = source == RECORDED
}

data class PostRideCalculation(
    val maxSpeed: Float,
    val distance: Double,
    val avgSpeed: Float,
    val pauseDuration: Long,
    val maxAcceleration: Float? = null,
    val rawPointCount: Int? = null,
    val elevationGainMeters: Double? = null
)

@Entity(
    tableName = "rides",
    indices = [Index(value = ["qualifiesForStats", "pendingDelete", "isSample", "startTime"], name = "index_rides_dashboard_summary")],
)
data class RideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val sourceInfo: String = "Android Device",
    val isBroadcasted: Boolean = false,
    val isSynced: Boolean = false,
    val firestoreId: String? = null,
    val title: String? = null,
    val persona: String = "AUTO",
    /**
     * IANA zone captured when a locally recorded ride starts. Legacy/imported rides remain null
     * and deliberately retain the historical device-zone fallback at read time.
     */
    val startZoneId: String? = null,
    /**
     * First-run sample rides are full local rides for replay/export, but are deliberately excluded
     * from cloud sync and retention aggregates. Defaults to false for every pre-1.8.2 row.
     */
    val isSample: Boolean = false,
    /**
     * SCOPE_1.7.3 §2(a), §0 contract 5 — set before the cloud delete, cleared only if the cloud
     * rejects it.
     *
     * Local and cloud cannot share one transaction: Room's `@Transaction` is SQLite-only and a
     * Firestore batch is server-only, so the *ordering* carries the correctness —
     * `pendingDelete` locally → commit the cloud batch → delete locally.
     *
     * The flag closes the window in both directions. Delete locally first and a cloud failure
     * leaves the ride live in the cloud to be re-downloaded later; delete cloud-first and a local
     * failure leaves an unsynced ride that re-uploads itself. Either way the ride returns from the
     * dead. The uploader refuses to upload anything carrying this.
     */
    val pendingDelete: Boolean = false,
    /**
     * Persisted dashboard eligibility. This is deliberately a stored fact rather than a Home-time
     * heuristic: every aggregate query must agree about junk/sample/deletion exclusion without
     * loading route points. Legacy rows remain false until the bounded metadata reconciler has
     * rebuilt their aggregate facts.
     */
    val qualifiesForStats: Boolean = false,
    /** Active (pause-excluded) duration used by dashboard projections. */
    val dashboardActiveDurationMillis: Long = 0L,
    /** Persisted route availability fact; Home never probes gps_points to decide card content. */
    val dashboardPointCount: Int = 0,
    /**
     * TASK-231: bounded route shape (Google encoded polyline, <= 40 points) carried on the ride row
     * so the History list can draw a real route without reading gps_points. Null means "no drawable
     * route" -- fewer than two points, or a row the reconciler has not reached yet, which its
     * metadata version distinguishes.
     */
    val dashboardRoutePolyline: String? = null,
    /** Version of the rebuildable dashboard metadata contract; 0 means reconciliation is pending. */
    val dashboardMetadataVersion: Int = 0,
    /**
     * TASK-275: whether *this app* recorded the ride, or it arrived from a file.
     *
     * Levels and activity milestones count [RideSource.RECORDED] only. That is the whole anti-gaming
     * mechanism, and deliberately so: proving *who* recorded a ride needs signed exports, key
     * distribution and a server, while knowing whether *we* recorded it is a boolean written once at
     * insert. Imported rides remain fully first-class everywhere else -- History, Ride Detail,
     * export, sync -- because a rider importing their old Strava history still wants to see it.
     *
     * Defaulting to RECORDED is correct for the migration: every row that exists before this column
     * does was produced by the recorder, since import wrote rows indistinguishable from it.
     */
    val source: String = RideSource.RECORDED,
    /**
     * TASK-275: stable identity of the track itself, from [RideContentHash]. Null for rows the
     * reconciler has not reached and for tracks too short to identify (< 2 points).
     */
    val contentHash: String? = null,
    /**
     * TASK-232: this ride was recorded while a group session was live.
     *
     * The marker and the count below are deliberately the *whole* record. No group id, no roster,
     * no name -- COMMUNITY_REDESIGN_SPEC SS2.2 allows a count and nothing else, because a count is
     * not identities and the promise printed on that screen is that nothing about the other riders
     * is saved. Neither field is synced: FirestoreSyncManager writes an explicit field map, and
     * these are not in it, so this opens no new Data Safety surface (SS5.4).
     */
    val wasGroupRide: Boolean = false,
    /**
     * How many riders were in the group, including this one. Null when it was never observed --
     * SS5.5's honesty rule: an unknown count renders no count, never `0`.
     */
    val groupRiderCount: Int? = null,
    @Embedded
    val postRideCalculation: PostRideCalculation? = null
)
