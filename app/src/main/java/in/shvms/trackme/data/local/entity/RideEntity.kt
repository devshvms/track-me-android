package `in`.shvms.trackme.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

data class PostRideCalculation(
    val maxSpeed: Float,
    val distance: Double,
    val avgSpeed: Float,
    val pauseDuration: Long,
    val maxAcceleration: Float? = null,
    val rawPointCount: Int? = null
)

@Entity(tableName = "rides")
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
    @Embedded
    val postRideCalculation: PostRideCalculation? = null
)
