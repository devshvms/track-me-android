package `in`.shvms.trackme.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import `in`.shvms.trackme.data.local.BroadcastStore
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import `in`.shvms.trackme.utils.logger.ErrorLogger
import kotlinx.coroutines.tasks.await

/**
 * SCOPE_1.8.7 §6.3 — the fallback that makes "we told everyone" true.
 *
 * Push is the fast path, not the only one, and it is unreliable in ways nobody controls: the user
 * declined notifications, the device was off, FCM dropped it, the topic subscription had not
 * completed yet, or the payload arrived while the app was being force-stopped. Any of those would
 * otherwise mean a rider never learns that the build they are running has a defect.
 *
 * So every foreground also reads the collection. The store is idempotent by id, so a broadcast that
 * did arrive by push is not shown twice — and one that did not is picked up here silently, without
 * a second notification, because the moment to interrupt has passed.
 *
 * Reads are unauthenticated by design: `firestore.rules` makes `broadcasts` world-readable and
 * nobody-writable. Requiring sign-in would silence exactly the local-only users this app is built
 * for, and the collection carries no personal data — it is addressed to everyone by definition.
 */
object BroadcastReconciler {

    /** Matches `BroadcastStore.MAX_RETAINED`: reading more than we would keep is wasted bandwidth. */
    private const val LIMIT = BroadcastStore.MAX_RETAINED.toLong()

    /**
     * @return the number of broadcasts that were new to this device.
     */
    suspend fun reconcile(
        store: BroadcastStore,
        versionCode: Int,
        errorLogger: ErrorLogger?,
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    ): Int = try {
        val snapshot = firestore.collection("broadcasts")
            .orderBy("created_at_millis", Query.Direction.DESCENDING)
            .limit(LIMIT)
            .get()
            .await()

        snapshot.documents.count { document ->
            // Parsed, not trusted. The rules make this collection unwritable by clients, but the
            // parser is what enforces the closed tag vocabulary and the length limits — and a
            // security rule protects the collection, not the shape of what is in it.
            val broadcast = OperatorBroadcast.parse(document.data.orEmpty()) ?: return@count false
            if (!broadcast.appliesTo(versionCode)) return@count false
            store.store(broadcast)
        }
    } catch (e: Exception) {
        // Offline is the normal case for this app, not an error worth surfacing. The next
        // foreground retries, and nothing is lost.
        errorLogger?.recordException(e)
        0
    }
}
