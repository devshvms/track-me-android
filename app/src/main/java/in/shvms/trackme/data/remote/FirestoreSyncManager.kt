package `in`.shvms.trackme.data.remote

import android.util.Log
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.utils.RideUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class SyncResult {
    object Idle : SyncResult()
    object Syncing : SyncResult()
    data class Success(val uploaded: Int, val downloaded: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class FirestoreSyncManager(
    private val rideDao: RideDao,
    private val emergencyDao: `in`.shvms.trackme.data.local.dao.EmergencyDao,
    private val authManager: AuthManager,
    private val errorLogger: `in`.shvms.trackme.utils.logger.ErrorLogger
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncResult = MutableStateFlow<SyncResult>(SyncResult.Idle)
    val syncResult: StateFlow<SyncResult> = _syncResult.asStateFlow()

    val totalCloudRidesCount = MutableStateFlow(0)

    init {
        syncScope.launch {
            authManager.currentUser.collect { user ->
                refreshCloudCount()
            }
        }
    }

    fun refreshCloudCount() {
        val user = authManager.currentUser.value ?: run {
            totalCloudRidesCount.value = 0
            return
        }
        syncScope.launch {
            try {
                val aggregateQuery = firestore.collection("users")
                    .document(user.uid)
                    .collection("rides")
                    .count()
                val snapshot = aggregateQuery.get(com.google.firebase.firestore.AggregateSource.SERVER).await()
                totalCloudRidesCount.value = snapshot.count.toInt()
            } catch (e: Exception) {
                errorLogger.log("Failed to refresh cloud count")
            }
        }
    }

    fun syncAll() {
        syncScope.launch {
            val user = authManager.currentUser.value ?: run {
                _syncResult.value = SyncResult.Error("Not signed in")
                return@launch
            }
            _syncResult.value = SyncResult.Syncing
            var uploaded = 0
            var downloaded = 0
            try {
                // --- UPSTREAM: Local → Cloud ---
                val allRides = rideDao.getAllRidesWithPoints().first()
                val unsyncedRides = allRides.filter { !it.ride.isSynced }
                for (rideWithPoints in unsyncedRides) {
                    uploadRideInternal(rideWithPoints.ride.id)
                    uploaded++
                }

                // --- DOWNSTREAM: Cloud → Local (Full Sync All) ---
                downloaded = downloadFromCloud(user.uid, null)
                refreshCloudCount()
                _syncResult.value = SyncResult.Success(uploaded, downloaded)
            } catch (e: Exception) {
                errorLogger.log("Sync failed")
                errorLogger.recordException(e)
                _syncResult.value = SyncResult.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun syncPeriodic() {
        syncScope.launch {
            val user = authManager.currentUser.value ?: return@launch
            _syncResult.value = SyncResult.Syncing
            var uploaded = 0
            var downloaded = 0
            try {
                // --- UPSTREAM: Local → Cloud ---
                val allRides = rideDao.getAllRidesWithPoints().first()
                val unsyncedRides = allRides.filter { !it.ride.isSynced }
                for (rideWithPoints in unsyncedRides) {
                    uploadRideInternal(rideWithPoints.ride.id)
                    uploaded++
                }

                // --- DOWNSTREAM: Cloud → Local (Lazy Load Top 10 for Periodic Sync) ---
                downloaded = downloadFromCloud(user.uid, 10)
                refreshCloudCount()
                _syncResult.value = SyncResult.Success(uploaded, downloaded)
            } catch (e: Exception) {
                errorLogger.log("Periodic sync failed")
                errorLogger.recordException(e)
                _syncResult.value = SyncResult.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun syncRecent(limit: Int) {
        syncScope.launch {
            val user = authManager.currentUser.value ?: return@launch
            _syncResult.value = SyncResult.Syncing
            try {
                val downloaded = downloadFromCloud(user.uid, limit)
                refreshCloudCount()
                _syncResult.value = SyncResult.Success(0, downloaded)
            } catch (e: Exception) {
                errorLogger.log("Sync recent failed")
                errorLogger.recordException(e)
                _syncResult.value = SyncResult.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    private suspend fun downloadFromCloud(uid: String, limit: Int? = null): Int {
        var count = 0
        val existingFirestoreIds = rideDao.getAllRidesWithPoints().first()
            .mapNotNull { it.ride.firestoreId }
            .toSet()

        var query: com.google.firebase.firestore.Query = firestore.collection("users")
            .document(uid)
            .collection("rides")
            .orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)

        if (limit != null) {
            query = query.limit(limit.toLong())
        }

        val snapshot = query.get().await()

        for (doc in snapshot.documents) {
            if (existingFirestoreIds.contains(doc.id)) continue
            if (insertRideDocument(doc)) {
                count++
            }
        }
        return count
    }

    suspend fun downloadNextBatch(uid: String, batchSize: Int = 10): Int {
        val existingFirestoreIds = rideDao.getAllRidesWithPoints().first()
            .mapNotNull { it.ride.firestoreId }
            .toSet()

        val existingSyncedRides = rideDao.getAllRidesWithPoints().first()
            .map { it.ride }
            .filter { it.firestoreId != null }

        val oldestStartTime = existingSyncedRides.minOfOrNull { it.startTime }

        var query: com.google.firebase.firestore.Query = firestore.collection("users")
            .document(uid)
            .collection("rides")

        query = if (oldestStartTime != null) {
            query.orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .whereLessThan("startTime", oldestStartTime)
        } else {
            query.orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)
        }

        val snapshot = query.limit(batchSize.toLong()).get().await()

        for (doc in snapshot.documents) {
            if (existingFirestoreIds.contains(doc.id)) continue
            insertRideDocument(doc)
        }
        return snapshot.documents.size
    }

    private suspend fun insertRideDocument(doc: com.google.firebase.firestore.DocumentSnapshot): Boolean {
        return try {
            val docId = doc.id
            val startTime = doc.getLong("startTime") ?: return false
            val endTime = doc.getLong("endTime")
            val sourceInfo = doc.getString("sourceInfo") ?: "Cloud Sync"
            val title = doc.getString("title") ?: RideUtils.getDefaultTitle(startTime)

            val maxSpeed = (doc.getDouble("maxSpeed") ?: 0.0).toFloat()
            val distance = doc.getDouble("distance") ?: 0.0
            val avgSpeed = (doc.getDouble("avgSpeed") ?: 0.0).toFloat()
            val pauseDuration = doc.getLong("pauseDuration") ?: 0L

            val calc = PostRideCalculation(maxSpeed, distance, avgSpeed, pauseDuration)

            val persona = doc.getString("persona") ?: "AUTO"
            val newRide = RideEntity(
                startTime = startTime,
                endTime = endTime,
                sourceInfo = sourceInfo,
                isSynced = true,
                firestoreId = docId,
                title = title,
                persona = persona,
                postRideCalculation = calc
            )
            val rideId = rideDao.insertRide(newRide)

            @Suppress("UNCHECKED_CAST")
            val pointsList = doc.get("points") as? List<Map<String, Any>> ?: emptyList()
            val gpsPoints = pointsList.mapIndexed { index, map ->
                GPSPointEntity(
                    rideId = rideId,
                    latitude = (map["lat"] as? Double) ?: 0.0,
                    longitude = (map["lng"] as? Double) ?: 0.0,
                    altitude = (map["altitude"] as? Double) ?: 0.0,
                    accuracy = ((map["accuracy"] as? Double) ?: 0.0).toFloat(),
                    speed = ((map["speed"] as? Double) ?: 0.0).toFloat(),
                    timestamp = (map["timestamp"] as? Long) ?: (startTime + index * 1000L),
                    isPaused = (map["isPaused"] as? Boolean) ?: false
                )
            }
            if (gpsPoints.isNotEmpty()) {
                rideDao.insertGPSPoints(gpsPoints)
            }
            true
        } catch (e: Exception) {
            errorLogger.log("Failed to download ride ${doc.id}")
            errorLogger.recordException(e)
            false
        }
    }

    fun uploadRide(rideId: Long) {
        syncScope.launch {
            uploadRideInternal(rideId)
        }
    }

    private suspend fun uploadRideInternal(rideId: Long) {
        val user = authManager.currentUser.value ?: return
        try {
            val rideWithPoints = rideDao.getRideWithPointsById(rideId) ?: return
            if (rideWithPoints.ride.isSynced) return

            val rideDocRef = firestore.collection("users")
                .document(user.uid)
                .collection("rides")
                .document(rideId.toString())

            val calc = rideWithPoints.ride.postRideCalculation
            val rideData = mapOf(
                "startTime" to rideWithPoints.ride.startTime,
                "endTime" to rideWithPoints.ride.endTime,
                "sourceInfo" to rideWithPoints.ride.sourceInfo,
                "title" to (rideWithPoints.ride.title ?: RideUtils.getDefaultTitle(rideWithPoints.ride.startTime)),
                "persona" to rideWithPoints.ride.persona,
                "maxSpeed" to (calc?.maxSpeed ?: 0f),
                "distance" to (calc?.distance ?: 0.0),
                "avgSpeed" to (calc?.avgSpeed ?: 0f),
                "pauseDuration" to (calc?.pauseDuration ?: 0L),
                "points" to rideWithPoints.points.map { point ->
                    mapOf(
                        "lat" to point.latitude,
                        "lng" to point.longitude,
                        "altitude" to point.altitude,
                        "accuracy" to point.accuracy,
                        "speed" to point.speed,
                        "timestamp" to point.timestamp,
                        "isPaused" to point.isPaused
                    )
                }
            )

            rideDocRef.set(rideData).await()
            val updatedRide = rideWithPoints.ride.copy(isSynced = true, firestoreId = rideId.toString())
            rideDao.updateRide(updatedRide)
        } catch (e: Exception) {
            errorLogger.log("Failed to upload ride $rideId")
            errorLogger.recordException(e)
        }
    }

    suspend fun deleteRide(firestoreDocId: String) {
        val user = authManager.currentUser.value ?: throw Exception("User not logged in")
        try {
            firestore.collection("users")
                .document(user.uid)
                .collection("rides")
                .document(firestoreDocId)
                .delete()
        } catch (e: Exception) {
            errorLogger.log("Failed to queue ride $firestoreDocId for deletion")
            errorLogger.recordException(e)
        }
    }

    fun syncEmergencyConfigUpstream() {
        syncScope.launch {
            val user = authManager.currentUser.value ?: return@launch
            try {
                val settings = emergencyDao.getSettings() ?: return@launch
                val contacts = emergencyDao.getContacts()

                val configData = mapOf(
                    "settings" to mapOf(
                        "isSetupComplete" to settings.isSetupComplete,
                        "messageTemplate" to settings.messageTemplate,
                        "premiumToken" to settings.premiumToken,
                        "broadcastIntervalSeconds" to settings.broadcastIntervalSeconds
                    ),
                    "contacts" to contacts.map { 
                        mapOf("name" to it.name, "phoneNumber" to it.phoneNumber, "medium" to it.medium) 
                    }
                )

                firestore.collection("users").document(user.uid)
                    .collection("emergency_config").document("settings")
                    .set(configData).await()
            } catch (e: Exception) {
                errorLogger.log("Failed to upload emergency config")
                errorLogger.recordException(e)
            }
        }
    }

    suspend fun syncEmergencyConfigDownstream() {
        val user = authManager.currentUser.value ?: return
        try {
            val doc = firestore.collection("users").document(user.uid)
                .collection("emergency_config").document("settings")
                .get().await()

            if (doc.exists()) {
                @Suppress("UNCHECKED_CAST")
                val settingsMap = doc.get("settings") as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val contactsList = doc.get("contacts") as? List<Map<String, Any>>

                if (settingsMap != null) {
                    val settings = `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity(
                        isSetupComplete = settingsMap["isSetupComplete"] as? Boolean ?: false,
                        messageTemplate = settingsMap["messageTemplate"] as? String ?: "EMERGENCY! I need help. My last known location is: [Location Link]",
                        premiumToken = settingsMap["premiumToken"] as? String,
                        broadcastIntervalSeconds = (settingsMap["broadcastIntervalSeconds"] as? Long)?.toInt() ?: 120
                    )
                    emergencyDao.updateSettings(settings)
                }

                if (contactsList != null) {
                    // Replace contacts
                    val oldContacts = emergencyDao.getContacts()
                    oldContacts.forEach { emergencyDao.deleteContact(it) }

                    contactsList.forEach { cMap ->
                        val contact = `in`.shvms.trackme.data.local.entity.EmergencyContactEntity(
                            name = cMap["name"] as? String ?: "Unknown",
                            phoneNumber = cMap["phoneNumber"] as? String ?: "",
                            medium = cMap["medium"] as? String ?: "SMS"
                        )
                        emergencyDao.insertContact(contact)
                    }
                }
            }
        } catch (e: Exception) {
            errorLogger.log("Failed to download emergency config")
            errorLogger.recordException(e)
        }
    }

    fun logEmergencyMessage(timestamp: Long, messageText: String, recipientNumber: String, msgType: String) {
        val user = authManager.currentUser.value ?: return
        val logData = mapOf(
            "timestamp" to timestamp,
            "messageText" to messageText,
            "recipientNumber" to recipientNumber,
            "msgType" to msgType
        )
        firestore.collection("users").document(user.uid)
            .collection("emergency_logs")
            .add(logData)
    }

    suspend fun deleteAllCloudData(): Result<Unit> {
        return try {
            val user = authManager.currentUser.value ?: throw Exception("Not signed in")
            val uid = user.uid
            
            // Delete rides and their subcollections
            val ridesRef = firestore.collection("users").document(uid).collection("rides")
            val ridesSnapshot = ridesRef.get().await()
            for (rideDoc in ridesSnapshot) {
                // Delete all points in the subcollection
                val pointsSnapshot = rideDoc.reference.collection("points").get().await()
                for (pointDoc in pointsSnapshot) {
                    pointDoc.reference.delete().await()
                }
                // Delete the ride document
                rideDoc.reference.delete().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            errorLogger.log("Delete all cloud data failed")
            errorLogger.recordException(e)
            Result.failure(e)
        }
    }

    suspend fun submitFeedback(text: String, type: String): Result<Unit> {
        return try {
            val user = authManager.currentUser.value
            val data = hashMapOf(
                "text" to text,
                "type" to type,
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "uid" to (user?.uid ?: "anonymous")
            )
            firestore.collection("feedbacks").add(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            errorLogger.log("Submit feedback failed")
            errorLogger.recordException(e)
            Result.failure(e)
        }
    }
}
