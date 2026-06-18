package com.example.trackme.data.remote

import android.util.Log
import com.example.trackme.auth.AuthManager
import com.example.trackme.data.local.dao.RideDao
import com.example.trackme.data.local.entity.GPSPointEntity
import com.example.trackme.data.local.entity.PostRideCalculation
import com.example.trackme.data.local.entity.RideEntity
import com.example.trackme.utils.RideUtils
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
    private val authManager: AuthManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncResult = MutableStateFlow<SyncResult>(SyncResult.Idle)
    val syncResult: StateFlow<SyncResult> = _syncResult.asStateFlow()

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

                // --- DOWNSTREAM: Cloud → Local ---
                downloaded = downloadFromCloud(user.uid)

                _syncResult.value = SyncResult.Success(uploaded, downloaded)
            } catch (e: Exception) {
                Log.e("FirestoreSyncManager", "Sync failed", e)
                _syncResult.value = SyncResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun syncRecent(limit: Int) {
        syncScope.launch {
            val user = authManager.currentUser.value ?: return@launch
            _syncResult.value = SyncResult.Syncing
            try {
                val downloaded = downloadFromCloud(user.uid, limit)
                _syncResult.value = SyncResult.Success(0, downloaded)
            } catch (e: Exception) {
                Log.e("FirestoreSyncManager", "Sync recent failed", e)
                _syncResult.value = SyncResult.Error(e.message ?: "Unknown error")
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
            val docId = doc.id
            // Skip rides already present locally
            if (existingFirestoreIds.contains(docId)) continue

            try {
                val startTime = doc.getLong("startTime") ?: continue
                val endTime = doc.getLong("endTime")
                val sourceInfo = doc.getString("sourceInfo") ?: "Cloud Sync"
                val title = doc.getString("title") ?: RideUtils.getDefaultTitle(startTime)

                // Reconstruct PostRideCalculation if present
                val maxSpeed = (doc.getDouble("maxSpeed") ?: 0.0).toFloat()
                val distance = doc.getDouble("distance") ?: 0.0
                val avgSpeed = (doc.getDouble("avgSpeed") ?: 0.0).toFloat()
                val pauseDuration = doc.getLong("pauseDuration") ?: 0L

                val calc = PostRideCalculation(maxSpeed, distance, avgSpeed, pauseDuration)

                val newRide = RideEntity(
                    startTime = startTime,
                    endTime = endTime,
                    sourceInfo = sourceInfo,
                    isSynced = true,
                    firestoreId = docId,
                    title = title,
                    postRideCalculation = calc
                )
                val rideId = rideDao.insertRide(newRide)

                // Insert GPS points
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
                count++
            } catch (e: Exception) {
                Log.e("FirestoreSyncManager", "Failed to download ride $docId", e)
            }
        }
        return count
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
            Log.e("FirestoreSyncManager", "Failed to upload ride $rideId", e)
        }
    }

    suspend fun deleteRide(rideId: Long) {
        val user = authManager.currentUser.value ?: throw Exception("User not logged in")
        try {
            firestore.collection("users")
                .document(user.uid)
                .collection("rides")
                .document(rideId.toString())
                .delete()
        } catch (e: Exception) {
            Log.e("FirestoreSyncManager", "Failed to queue ride $rideId for deletion", e)
        }
    }
}
