package `in`.shvms.trackme.data.remote

import android.util.Log
import `in`.shvms.trackme.auth.AuthManager
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.domain.sync.RideChunking
import `in`.shvms.trackme.domain.sync.RideDeletion
import `in`.shvms.trackme.utils.RideUtils
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Coerce a Firestore-decoded value into epoch-milliseconds.
 * Handles Android-written Long (ms), iOS-written com.google.firebase.Timestamp,
 * and defensively a numeric Double (seconds vs ms heuristic) or a java.util.Date.
 * Returns null only when the value is genuinely absent/uninterpretable.
 */
internal fun coerceEpochMillis(value: Any?): Long? = when (value) {
    null -> null
    is Long -> value
    is Int -> value.toLong()
    is com.google.firebase.Timestamp -> value.toDate().time
    is java.util.Date -> value.time
    is Double -> {
        // Firestore may surface a whole number as Double. Disambiguate s vs ms:
        // anything below ~10^11 is implausible as ms (that's year 1973) → treat as seconds.
        if (value < 100_000_000_000.0) (value * 1000.0).toLong() else value.toLong()
    }
    is Number -> value.toLong()   // fallback for any other numeric wrapper
    else -> null
}

// Pure. distanceMeters mirrors GPSProcessor's GeoDistanceCalculator seam so tests inject haversine.
internal fun computeCalcFromPoints(
    points: List<GPSPointEntity>,
    distanceMeters: (a: GPSPointEntity, b: GPSPointEntity) -> Float
): PostRideCalculation {
    if (points.size < 2) return PostRideCalculation(0f, 0.0, 0f, 0L)
    var totalDistance = 0.0
    var activeTimeMs = 0L
    var maxSpeed = 0f
    for (i in 1 until points.size) {
        val prev = points[i - 1]; val cur = points[i]
        if (cur.speed > maxSpeed) maxSpeed = cur.speed
        if (!prev.isPaused && !cur.isPaused) {
            totalDistance += distanceMeters(prev, cur)
            val gap = cur.timestamp - prev.timestamp
            if (gap in 1..60_000) activeTimeMs += gap   // ignore gaps > 60s (matches processor intent)
        }
    }
    if (points[0].speed > maxSpeed) maxSpeed = points[0].speed
    val avgSpeed = if (activeTimeMs > 0) (totalDistance / (activeTimeMs / 1000f)).toFloat() else 0f
    val total = points.last().timestamp - points.first().timestamp
    val pauseMs = maxOf(0L, total - activeTimeMs)
    return PostRideCalculation(maxSpeed, totalDistance, avgSpeed, pauseMs)
}

/** Sample rides and local tombstones are never candidates for any bulk upload pass. */
internal fun isRideEligibleForCloudSync(ride: RideEntity): Boolean =
    !ride.isSample && !ride.pendingDelete

sealed class SyncResult {
    object Idle : SyncResult()
    object Syncing : SyncResult()
    data class Success(val uploaded: Int, val downloaded: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/**
 * Outcome of one [FirestoreSyncManager.downloadNextBatch] step.
 * [insertedCount] counts rides that actually landed in Room — a page can be non-empty and still
 * insert nothing when every document on it is already local.
 * [reachedEnd] is true once the cursor has walked past the last document in the collection.
 */
data class CloudPageResult(val insertedCount: Int, val reachedEnd: Boolean)

/**
 * Pure. Decide whether the paginator should stop after a page, or keep skipping ahead because the
 * page held nothing new. Returns null to mean "fetch another page".
 */
internal fun cloudPageOutcome(
    documentsOnPage: Int,
    batchSize: Int,
    insertedSoFar: Int,
    pagesFetched: Int,
    maxPagesPerCall: Int
): CloudPageResult? = when {
    // Firestore only under-fills a limit at the end of the collection.
    documentsOnPage < batchSize -> CloudPageResult(insertedSoFar, reachedEnd = true)
    insertedSoFar > 0 -> CloudPageResult(insertedSoFar, reachedEnd = false)
    pagesFetched >= maxPagesPerCall -> CloudPageResult(insertedSoFar, reachedEnd = false)
    else -> null
}

class FirestoreSyncManager(
    private val rideDao: RideDao,
    private val authManager: AuthManager,
    private val errorLogger: `in`.shvms.trackme.utils.logger.ErrorLogger
) {
    private val firestore = FirebaseFirestore.getInstance()

    // A background sync failure must never kill the process: an unhandled exception
    // in a launch {} on this scope is fatal on Android (v1.5.11 signed-out crash).
    private val syncScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            errorLogger.log("Unhandled sync coroutine failure")
            errorLogger.recordException(e)
        }
    )

    private val _syncResult = MutableStateFlow<SyncResult>(SyncResult.Idle)
    val syncResult: StateFlow<SyncResult> = _syncResult.asStateFlow()

    val totalCloudRidesCount = MutableStateFlow(0)

    // History pagination cursor. Held as a DocumentSnapshot, not a startTime value: Firestore
    // range filters are scoped to one value type, so a numeric `whereLessThan("startTime", ...)`
    // silently skips every ride whose startTime iOS wrote as a Timestamp. A snapshot cursor is a
    // position in the ordered result set, so it walks across that type boundary and sees both.
    private var pageCursorUid: String? = null
    private var pageCursor: com.google.firebase.firestore.DocumentSnapshot? = null

    init {
        syncScope.launch {
            authManager.currentUser.collect { user ->
                resetCloudPagination()
                refreshCloudCount()
                if (user != null) resumePendingDeletes()
            }
        }
    }

    /**
     * Finishes any deletion that was interrupted between its two halves — SCOPE_1.7.3 §0 contract 5.
     *
     * The order is `pendingDelete` locally → cloud batch → local delete, and a process death can
     * land in either gap. §2(a) is explicit that an offline delete must be *"durable across app
     * restart — a cloud delete that exists only in memory is an orphan waiting for a process
     * death."* Firestore's own persistence covers a batch that was already committed; this covers
     * the ride that never got that far, and the ride whose cloud half succeeded while the local
     * half did not.
     *
     * Re-running the whole delete is safe because a Firestore delete is idempotent: deleting an
     * already-absent document succeeds. Retrying is therefore always the right move, and doing
     * nothing is the only wrong one — a stranded flag makes the ride invisible to the uploader and
     * still present in History, and it would never resolve itself.
     */
    private suspend fun resumePendingDeletes() {
        val stranded = runCatching { rideDao.getPendingDeleteRides() }.getOrNull().orEmpty()
        for (ride in stranded) {
            val docId = ride.firestoreId?.takeIf { it.isNotBlank() }
                ?: ride.id.toString().takeIf { ride.isSynced }
                ?: run {
                    // Never reached the cloud. Nothing to cascade; just finish locally.
                    rideDao.deletePointsForRide(ride.id)
                    rideDao.deleteRide(ride.id)
                    continue
                }
            when (val outcome = deleteRide(docId)) {
                is RideDeletion.Outcome.Rejected -> {
                    // Still refused. Restore the row rather than leaving it stranded again —
                    // contract 6: only rejected restores, and a flag that survives two attempts is
                    // a ride the user can see and retry rather than one lost between states.
                    errorLogger.log("Resumed ride deletion still rejected (${outcome.cause.bucket})")
                    rideDao.setPendingDelete(ride.id, false)
                }
                else -> {
                    rideDao.deletePointsForRide(ride.id)
                    rideDao.deleteRide(ride.id)
                }
            }
        }
    }

    /** Rewind the history paginator to the top of the collection (sign-in/out, full re-sync). */
    fun resetCloudPagination() {
        pageCursorUid = null
        pageCursor = null
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
                val unsyncedRides = allRides.filter {
                    !it.ride.isSynced && isRideEligibleForCloudSync(it.ride)
                }
                for (rideWithPoints in unsyncedRides) {
                    if (uploadRideInternal(rideWithPoints.ride.id)) {
                        uploaded++
                    }
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

    suspend fun syncPeriodic(): SyncResult {
        val user = authManager.currentUser.value ?: return SyncResult.Error("Not signed in")
        _syncResult.value = SyncResult.Syncing
        var uploaded = 0
        return try {
            // --- UPSTREAM: Local → Cloud ---
            val allRides = rideDao.getAllRidesWithPoints().first()
            val unsyncedRides = allRides.filter {
                !it.ride.isSynced && isRideEligibleForCloudSync(it.ride)
            }
            for (rideWithPoints in unsyncedRides) {
                if (uploadRideInternal(rideWithPoints.ride.id)) {
                    uploaded++
                }
            }

            // --- DOWNSTREAM: Cloud → Local (Lazy Load Top 10 for Periodic Sync) ---
            val downloaded = downloadFromCloud(user.uid, 10)
            refreshCloudCount()
            SyncResult.Success(uploaded, downloaded).also { _syncResult.value = it }
        } catch (e: Exception) {
            errorLogger.log("Periodic sync failed")
            errorLogger.recordException(e)
            SyncResult.Error(e.localizedMessage ?: "Unknown error").also { _syncResult.value = it }
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

    /**
     * Pull the next slice of cloud rides for the History list.
     *
     * Walks the `rides` collection with a DocumentSnapshot cursor rather than a startTime range
     * filter. That matters because `startTime` is mixed-type in practice — Android uploads a Long
     * (Firestore Number), iOS uploads a Date (Firestore Timestamp) — and range filters only ever
     * match one type group, so the old numeric cursor could never reach an iOS-written ride.
     *
     * Pages whose documents are all already local are skipped over rather than returned, so a
     * single call makes visible progress instead of burning a scroll gesture on a no-op page.
     * [maxPagesPerCall] bounds how far one call will skip ahead.
     */
    suspend fun downloadNextBatch(
        uid: String,
        batchSize: Int = 10,
        maxPagesPerCall: Int = 25
    ): CloudPageResult {
        if (pageCursorUid != uid) {
            pageCursorUid = uid
            pageCursor = null
        }

        val knownFirestoreIds = rideDao.getAllRidesWithPoints().first()
            .mapNotNull { it.ride.firestoreId }
            .toMutableSet()

        var inserted = 0
        var pagesFetched = 0

        while (true) {
            var query: com.google.firebase.firestore.Query = firestore.collection("users")
                .document(uid)
                .collection("rides")
                .orderBy("startTime", com.google.firebase.firestore.Query.Direction.DESCENDING)

            pageCursor?.let { query = query.startAfter(it) }

            val snapshot = query.limit(batchSize.toLong()).get().await()
            pagesFetched++

            if (snapshot.documents.isEmpty()) {
                return CloudPageResult(inserted, reachedEnd = true)
            }
            pageCursor = snapshot.documents.last()

            for (doc in snapshot.documents) {
                if (knownFirestoreIds.contains(doc.id)) continue
                if (insertRideDocument(doc)) {
                    knownFirestoreIds.add(doc.id)
                    inserted++
                }
            }

            cloudPageOutcome(
                documentsOnPage = snapshot.documents.size,
                batchSize = batchSize,
                insertedSoFar = inserted,
                pagesFetched = pagesFetched,
                maxPagesPerCall = maxPagesPerCall
            )?.let { return it }
        }
    }

    /**
     * Reads a ride's points from whichever shape the cloud copy is in — SCOPE_1.7.3 §2(a).
     *
     * **Both shapes are permanent, not a migration that ends.** Every ride uploaded before 1.7.3
     * keeps its `points` array, and rewriting them all would be a mass re-upload of the user's
     * entire history for no benefit they can see. The array is checked first because it is the
     * cheaper answer and needs no extra reads.
     *
     * Returns null when the ride must be **skipped rather than partially imported**. The parent is
     * written last, so a parent that exists should always have all its chunks; if one is missing
     * anyway, half a ride that reassembles into something plausible is worse than no ride — it
     * would look like a real ride that simply ended early, and the user would have no way to tell.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun readPointMaps(
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): List<Map<String, Any>>? {
        (doc.get("points") as? List<Map<String, Any>>)?.let { return it }

        val chunkCount = doc.getLong(RideChunking.CHUNK_COUNT_FIELD)?.toInt() ?: return emptyList()
        if (chunkCount <= 0) return emptyList()

        val pointsRef = doc.reference.collection(RideChunking.POINTS_SUBCOLLECTION)
        val assembled = mutableListOf<Map<String, Any>>()
        // Exactly chunkCount chunks, by constructed id, in order — never a query. That is what
        // makes a stale orphan from a re-upload inert, and what keeps reassembly ordered by
        // construction rather than by however the server happened to sort.
        for (id in RideChunking.chunkIds(chunkCount)) {
            val chunk = pointsRef.document(id).get().await()
            val slice = chunk.get(RideChunking.CHUNK_POINTS_FIELD) as? List<Map<String, Any>>
            if (!chunk.exists() || slice == null) {
                errorLogger.log("Ride ${doc.id} is missing chunk $id of $chunkCount; skipping it")
                return null
            }
            assembled += slice
        }
        return assembled
    }

    private suspend fun insertRideDocument(doc: com.google.firebase.firestore.DocumentSnapshot): Boolean {
        return try {
            val docId = doc.id
            val startTime = coerceEpochMillis(doc.get("startTime")) ?: return false
            val endTime = coerceEpochMillis(doc.get("endTime"))
            val sourceInfo = doc.getString("sourceInfo") ?: "Cloud Sync"
            val title = doc.getString("title") ?: RideUtils.getDefaultTitle(startTime)

            val maxSpeed = (doc.getDouble("maxSpeed") ?: 0.0).toFloat()
            val distance = doc.getDouble("distance") ?: 0.0
            val avgSpeed = (doc.getDouble("avgSpeed") ?: 0.0).toFloat()
            val pauseDuration = doc.getLong("pauseDuration") ?: 0L

            val docHasStats = doc.get("distance") != null

            val pointsList = readPointMaps(doc) ?: return false
            val gpsPoints = pointsList.mapIndexed { index, map ->
                GPSPointEntity(
                    rideId = 0L,
                    latitude = (map["lat"] as? Double) ?: 0.0,
                    longitude = (map["lng"] as? Double) ?: 0.0,
                    altitude = (map["altitude"] as? Double) ?: 0.0,
                    accuracy = ((map["accuracy"] as? Double) ?: 0.0).toFloat(),
                    speed = ((map["speed"] as? Double) ?: 0.0).toFloat(),
                    timestamp = coerceEpochMillis(map["timestamp"]) ?: (startTime + index * 1000L),
                    isPaused = (map["isPaused"] as? Boolean) ?: false
                )
            }

            val calc = if (docHasStats) {
                PostRideCalculation(maxSpeed, distance, avgSpeed, pauseDuration)
            } else if (gpsPoints.isNotEmpty()) {
                computeCalcFromPoints(gpsPoints) { a, b ->
                    val r = FloatArray(1)
                    android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
                    r[0]
                }
            } else {
                PostRideCalculation(0f, 0.0, 0f, 0L)
            }

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

            if (gpsPoints.isNotEmpty()) {
                val finalGpsPoints = gpsPoints.map { it.copy(rideId = rideId) }
                rideDao.insertGPSPoints(finalGpsPoints)
            }
            true
        } catch (e: Exception) {
            errorLogger.log("Failed to download ride ${doc.id}")
            errorLogger.recordException(e)
            false
        }
    }

    fun uploadRide(rideId: Long) {
        // uploadRideInternal rethrows failures for syncAll()/syncPeriodic() to report;
        // this fire-and-forget path must swallow them — the ride stays local and is
        // retried by the next full sync pass.
        syncScope.launchSyncTask(errorLogger, "uploadRide") {
            uploadRideInternal(rideId)
        }
    }

    /**
     * Serialises one GPS point exactly as the wire format has always had it.
     *
     * Unchanged from the single-document shape on purpose: only the *container* changed, so an
     * existing array-shaped ride and a new chunk hold byte-identical point maps and the reader
     * below can take either without a second decoder.
     */
    private fun pointToMap(point: GPSPointEntity): Map<String, Any> = mapOf(
        "lat" to point.latitude,
        "lng" to point.longitude,
        "altitude" to point.altitude,
        "accuracy" to point.accuracy,
        "speed" to point.speed,
        "timestamp" to point.timestamp,
        "isPaused" to point.isPaused
    )

    /**
     * Uploads a ride as **N chunk documents plus a parent** — SCOPE_1.7.3 §2(a), contracts 3–4.
     *
     * The 1 MiB ceiling was a property of the storage *shape*, not of the data: every point went
     * into one `points` array, and at the measured 100 bytes per point (§9) that shape dies at
     * 10,483 points. A subcollection removes the ceiling entirely and permanently, at any ride
     * length, with post-processing left exactly where it is.
     *
     * **Write order is the whole design.** Chunks first, parent last:
     *
     * - The parent is the **commit marker**. `isSynced` is set only after it lands, so an upload
     *   interrupted halfway leaves chunks with no parent — invisible to every query, rather than a
     *   half-ride that reassembles into something plausible and wrong.
     * - Surplus chunks from a previous, longer upload are removed afterwards. Readers take exactly
     *   `chunkCount` chunks, so a stale orphan is inert even before it is cleaned up — which is
     *   what makes doing this last safe.
     *
     * @return true if this call uploaded the ride, false if it was skipped.
     */
    private suspend fun uploadRideInternal(rideId: Long): Boolean {
        val user = authManager.currentUser.value ?: run {
            // Signed-out is a normal state, not an error: the ride is already saved
            // locally and will sync after the next sign-in.
            errorLogger.log("Skipped upload of ride $rideId: not signed in")
            return false
        }
        try {
            val rideWithPoints = rideDao.getRideWithPointsById(rideId)
                ?: throw IllegalStateException("Ride $rideId was not found")
            if (rideWithPoints.ride.isSynced) return false
            if (rideWithPoints.ride.isSample) {
                errorLogger.log("Skipped upload of local sample ride $rideId")
                return false
            }
            // §0 contract 5: "the uploader must refuse to upload anything carrying it." Without
            // this, deleting a ride while an upload is in flight re-creates it in the cloud after
            // the batch has already removed it — the ride returns from the dead.
            if (rideWithPoints.ride.pendingDelete) {
                errorLogger.log("Skipped upload of ride $rideId: it is pending deletion")
                return false
            }

            val rideDocRef = firestore.collection("users")
                .document(user.uid)
                .collection("rides")
                .document(rideId.toString())
            val pointsRef = rideDocRef.collection(RideChunking.POINTS_SUBCOLLECTION)

            // How many chunks the cloud copy currently has, so surplus can be cleaned up after.
            // Read before anything is written; a missing/absent parent simply means none.
            val previousChunkCount = runCatching {
                rideDocRef.get().await().getLong(RideChunking.CHUNK_COUNT_FIELD)?.toInt()
            }.getOrNull() ?: 0

            val chunks = RideChunking.partition(rideWithPoints.points)
            chunks.forEachIndexed { index, chunk ->
                pointsRef.document(RideChunking.chunkId(index))
                    .set(mapOf(RideChunking.CHUNK_POINTS_FIELD to chunk.map(::pointToMap)))
                    .await()
            }

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
                RideChunking.CHUNK_COUNT_FIELD to chunks.size
            )

            // LAST. Everything above can be retried; this is what makes the ride real.
            rideDocRef.set(rideData).await()

            val updatedRide = rideWithPoints.ride.copy(isSynced = true, firestoreId = rideId.toString())
            rideDao.updateRide(updatedRide)

            // Surplus from a longer previous upload (post-processing compressed 10 chunks to 2).
            // Best-effort: readers already ignore anything past chunkCount, so failing here leaves
            // inert documents rather than a corrupt ride.
            if (previousChunkCount > chunks.size) {
                for (index in chunks.size until previousChunkCount) {
                    runCatching { pointsRef.document(RideChunking.chunkId(index)).delete().await() }
                }
            }
            return true
        } catch (e: Exception) {
            errorLogger.log("Failed to upload ride $rideId")
            errorLogger.recordException(e)
            throw e
        }
    }

    /**
     * Deletes a ride and **every one of its chunks**, as one atomic batched write —
     * SCOPE_1.7.3 §2(a), contracts 5–6.
     *
     * ### Why a batch and not a transaction
     *
     * A Firestore **transaction cannot enumerate a subcollection** — transactions operate on
     * document references known up front and cannot run a collection query, so "read all chunks,
     * then delete them and the parent" is not expressible as one at all. A [WriteBatch] needs no
     * reads, is genuinely atomic server-side, and gets its references from the parent's
     * `chunkCount` rather than from a query.
     *
     * ### Why this is urgent rather than tidy
     *
     * `deleteRide` used to delete only the parent, and **Firestore does not cascade**. The moment
     * chunks exist, that leaves every chunk behind — and because the parent was the only thing
     * pointing at them, they become *unreachable*: no screen lists them, no query finds them, and
     * the app can never delete them again. Location data the user believes they erased would
     * persist indefinitely. For a product that promises deletability and declares it in Play Data
     * Safety, that is a privacy and compliance failure, not untidiness.
     *
     * The chunk count comes from the **parent document**, not from the local point count. Those can
     * legitimately disagree — a re-upload whose surplus cleanup failed leaves more chunks in the
     * cloud than the local ride has points to explain — and deleting the smaller number is exactly
     * how an unreachable orphan is made. Reading the parent also works offline, since it is already
     * in Firestore's local cache from the query that listed it.
     */
    suspend fun deleteRide(firestoreDocId: String): RideDeletion.Outcome {
        val user = authManager.currentUser.value
            ?: return RideDeletion.Outcome.Rejected(RideDeletion.Cause.PERMISSION, null)

        val rideDocRef = firestore.collection("users")
            .document(user.uid)
            .collection("rides")
            .document(firestoreDocId)
        val pointsRef = rideDocRef.collection(RideChunking.POINTS_SUBCOLLECTION)

        // A legacy array-shaped ride has no chunkCount, and correctly resolves to zero: it is a
        // parent document and nothing else.
        val chunkCount = runCatching {
            rideDocRef.get().await().getLong(RideChunking.CHUNK_COUNT_FIELD)?.toInt()
        }.getOrNull() ?: 0

        return try {
            // Children before the parent, always, and the parent in the LAST batch. Violating that
            // in either direction produces the unreachable-orphan state above. Realistic rides are
            // one batch (§2(a): ~499,000 points); the paging path exists so a ride beyond that
            // degrades to non-atomic rather than to wrong.
            val chunkIds = RideChunking.chunkIds(chunkCount)
            val perBatch = RideChunking.DELETE_BATCH_LIMIT
            var index = 0
            while (index < chunkIds.size) {
                val slice = chunkIds.subList(index, minOf(index + perBatch, chunkIds.size))
                val isFinalSlice = index + slice.size >= chunkIds.size
                val batch = firestore.batch()
                slice.forEach { batch.delete(pointsRef.document(it)) }
                // The parent joins the last batch only if it fits; otherwise it gets its own.
                val parentFitsHere = isFinalSlice && slice.size < perBatch
                if (parentFitsHere) batch.delete(rideDocRef)
                commitAwaitingAck(batch)?.let { return it }
                if (isFinalSlice && !parentFitsHere) {
                    val parentBatch = firestore.batch()
                    parentBatch.delete(rideDocRef)
                    commitAwaitingAck(parentBatch)?.let { return it }
                }
                index += slice.size
            }
            if (chunkIds.isEmpty()) {
                val batch = firestore.batch()
                batch.delete(rideDocRef)
                commitAwaitingAck(batch)?.let { return it }
            }
            RideDeletion.Outcome.Acknowledged
        } catch (e: Exception) {
            val status = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name
            val cause = RideDeletion.causeOf(status)
            errorLogger.log("Ride deletion rejected (${cause.bucket})")
            // §2(a): only a genuine rejection reaches Crashlytics. The chunk count goes with it —
            // the one number that makes an orphan diagnosable and is not personal.
            errorLogger.recordException(
                IllegalStateException("Cascade delete rejected with $chunkCount chunks", e)
            )
            RideDeletion.Outcome.Rejected(cause, e)
        }
    }

    /**
     * Commits a batch, distinguishing "the server has it" from "it is queued offline".
     *
     * **Offline is not an error, and this is the trap most likely to produce a false message.**
     * Firestore's offline persistence is on by default; when offline the batch is queued locally,
     * survives app restart, and applies on reconnect — but the completion callback does not fire
     * until the *server* acknowledges. Awaiting it and treating the wait as failure would tell the
     * user "couldn't delete, try again" for a deletion that is queued and will succeed, and the
     * retry would then try to delete documents already pending deletion.
     *
     * The timeout is therefore not a network timeout. The write is already durable when it
     * expires, and abandoning the wait does not abandon the write.
     *
     * @return null when the server acknowledged (carry on), or the outcome to return immediately.
     */
    private suspend fun commitAwaitingAck(
        batch: com.google.firebase.firestore.WriteBatch
    ): RideDeletion.Outcome? {
        val acknowledged = kotlinx.coroutines.withTimeoutOrNull(RideDeletion.ACK_TIMEOUT_MS) {
            batch.commit().await()
            true
        }
        return if (acknowledged == null) RideDeletion.Outcome.Queued else null
    }

    suspend fun deleteAllCloudData(): Result<Unit> {
        return try {
            val user = authManager.currentUser.value ?: throw Exception("Not signed in")
            val uid = user.uid
            
            // Delete rides and their chunk subcollections.
            //
            // This path was already shaped correctly — it iterated the `points` subcollection back
            // when nothing wrote one, as dead defensive code. §2(a) notes that the *single-ride*
            // path was the wrong one, and that this must stay correct as chunk counts grow. It
            // queries the subcollection rather than trusting `chunkCount`, deliberately: a wipe is
            // the one place that must also remove orphans left by an interrupted upload, whose
            // parent carries no count at all.
            val ridesRef = firestore.collection("users").document(uid).collection("rides")
            val ridesSnapshot = ridesRef.get().await()
            for (rideDoc in ridesSnapshot) {
                val pointsSnapshot = rideDoc.reference
                    .collection(RideChunking.POINTS_SUBCOLLECTION).get().await()
                // Children before the parent, in batches that respect the 500-operation limit.
                pointsSnapshot.documents
                    .chunked(RideChunking.DELETE_BATCH_LIMIT)
                    .forEach { slice ->
                        val batch = firestore.batch()
                        slice.forEach { batch.delete(it.reference) }
                        batch.commit().await()
                    }
                rideDoc.reference.delete().await()
            }

            // Delete emergency configuration and delivery logs owned by this user.
            val emergencyConfigSnapshot = firestore.collection("users").document(uid)
                .collection("emergency_config").get().await()
            for (document in emergencyConfigSnapshot.documents) {
                document.reference.delete().await()
            }

            val emergencyLogsSnapshot = firestore.collection("users").document(uid)
                .collection("emergency_logs").get().await()
            for (document in emergencyLogsSnapshot.documents) {
                document.reference.delete().await()
            }

            // Feedback is user-owned and may be deleted only through the matching UID query.
            val feedbackSnapshot = firestore.collection("feedbacks")
                .whereEqualTo("uid", uid)
                .get().await()
            for (document in feedbackSnapshot.documents) {
                document.reference.delete().await()
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

/**
 * Launches a fire-and-forget background task that must never crash the process:
 * an exception escaping a bare launch {} is fatal on Android. Failures are
 * breadcrumb-logged and swallowed; they are recorded at their throw sites.
 */
internal fun CoroutineScope.launchSyncTask(
    errorLogger: `in`.shvms.trackme.utils.logger.ErrorLogger,
    taskName: String,
    block: suspend () -> Unit
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorLogger.log("Background task '$taskName' failed: ${e.message}")
    }
}
