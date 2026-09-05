package `in`.shvms.trackme.service

import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.utils.logger.ErrorLogger
import kotlinx.coroutines.tasks.await

/**
 * Deletes the emergency contacts a pre-1.6.5 install synced to Firestore.
 *
 * **This survived TASK-309 on purpose, and it is the only thing that did.** Everything else about
 * the retired SOS feature — the manager, the removal notice, the state cleanup, the strings — was
 * removed in 1.8.7 because it was code kept alive to explain a deletion. This is not that. It is
 * the deletion: `emergency_config` holds real phone numbers belonging to real people the rider
 * named, and they sit in Firestore until something removes them.
 *
 * The cohort is small and shrinking — anyone who has opened 1.6.5 or later has already had this
 * run — but "small" is not "empty", and the leftovers are the most sensitive data TrackMe ever
 * stored. Deleting other people's phone numbers is worth thirty lines and one preference key. It
 * can go when the purge is provably universal, not before.
 *
 * The privacy policy no longer describes this, which is fine in the direction that matters:
 * account deletion removes *more* than it promises, never less.
 */
internal object EmergencyDataPurge {
    const val PURGED_KEY = "emergency_data_purged_v165"

    suspend fun purgeOnce(
        prefs: SharedPreferences,
        authManager: AuthManager,
        errorLogger: ErrorLogger,
    ) {
        if (prefs.getBoolean(PURGED_KEY, false)) return
        prefs.edit().putBoolean(PURGED_KEY, true).apply()
        
        // Best-effort Firestore cleanup
        val uid = authManager.currentUser.value?.uid ?: return
        try {
            val firestore = FirebaseFirestore.getInstance()
            // Delete emergency_config/settings doc
            firestore.collection("users").document(uid)
                .collection("emergency_config").document("settings")
                .delete().await()
            // Delete emergency_logs subcollection
            val logsSnapshot = firestore.collection("users").document(uid)
                .collection("emergency_logs").get().await()
            for (doc in logsSnapshot.documents) {
                doc.reference.delete().await()
            }
        } catch (e: Exception) {
            errorLogger.log("EmergencyDataPurge: best-effort Firestore cleanup failed")
            errorLogger.recordException(e)
        }
    }
}
