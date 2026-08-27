package `in`.shvms.trackme.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    @Embedded
    val postRideCalculation: PostRideCalculation? = null
)
