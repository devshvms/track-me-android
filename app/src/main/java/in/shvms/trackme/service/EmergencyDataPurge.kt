package `in`.shvms.trackme.service

import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.utils.logger.ErrorLogger
import kotlinx.coroutines.tasks.await

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
