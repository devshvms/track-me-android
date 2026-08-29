package `in`.shvms.trackme.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.data.local.AppDatabase
import `in`.shvms.trackme.data.local.dao.HistoryRideSummary
import `in`.shvms.trackme.domain.sync.RideDeletion
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import `in`.shvms.trackme.domain.import.GPXParser

internal data class RideDeleteAttempt(
    val localDeleted: Boolean,
    val cloudDeleted: Boolean
)

internal data class BatchDeleteSummary(
    val deletedCount: Int,
    val failedCount: Int
)

internal fun cloudDocumentIdForDelete(
    rideId: Long,
    firestoreId: String?,
    isSynced: Boolean
): String? {
    val storedId = firestoreId?.trim()
    if (!storedId.isNullOrEmpty()) return storedId
    return if (isSynced) rideId.toString() else null
}

internal fun summarizeBatchDelete(attempts: List<RideDeleteAttempt>): BatchDeleteSummary {
    val deleted = attempts.count { it.localDeleted && it.cloudDeleted }
    return BatchDeleteSummary(deletedCount = deleted, failedCount = attempts.size - deleted)
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as `in`.shvms.trackme.TrackMeApp
    private val db = app.database
    private val rideDao = db.rideDao()
    private val errorLogger = app.errorLogger
    private val actionMutex = Mutex()

    val rides: StateFlow<List<HistoryRideSummary>> = rideDao.getAllCompletedRideSummaries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                `in`.shvms.trackme.domain.recovery.OrphanedRideRecoveryManager.recoverOrphanedRides(
                    rideDao,
                    `in`.shvms.trackme.service.TrackingService.activeRideId
                )
            } catch (e: Exception) {
                errorLogger.recordException(e)
            }
        }
    }

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreCloudRides = MutableStateFlow(true)
    val hasMoreCloudRides: StateFlow<Boolean> = _hasMoreCloudRides.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent

    private val _selectedRideIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedRideIds: StateFlow<Set<Long>> = _selectedRideIds.asStateFlow()

    init {
        // Signing out latches hasMoreCloudRides to false. Without this the paginator stays dead
        // for a user who signs back in while this screen is still alive.
        viewModelScope.launch {
            app.authManager.currentUser.collect { user ->
                _hasMoreCloudRides.value = user != null
            }
        }
    }

    fun loadMoreRides() {
        if (_isLoadingMore.value || !_hasMoreCloudRides.value) return
        // Signed out → there is no cloud page to fetch. Clear the flag so the paginator stops
        // asking (otherwise it stays "true" forever after logout).
        val user = app.authManager.currentUser.value ?: run {
            _hasMoreCloudRides.value = false
            return
        }
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                // Already holding every cloud ride locally? Skip the reads entirely — otherwise a
                // fully-synced user re-walks the whole collection just to find nothing.
                val cloudCount = app.firestoreSyncManager.totalCloudRidesCount.value
                val localSyncedCount = rides.value.count { it.firestoreId != null }
                if (cloudCount > 0 && localSyncedCount >= cloudCount) {
                    _hasMoreCloudRides.value = false
                    return@launch
                }
                val page = app.firestoreSyncManager.downloadNextBatch(user.uid, batchSize = 10)
                if (page.reachedEnd) {
                    _hasMoreCloudRides.value = false
                }
            } catch (e: Exception) {
                errorLogger.log("Failed to load more rides")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun deleteRide(rideId: Long) {
        deleteRides(setOf(rideId))
    }

    fun toggleRideSelection(rideId: Long) {
        _selectedRideIds.update { selected ->
            selected.toMutableSet().apply {
                if (!add(rideId)) remove(rideId)
            }
        }
    }

    fun toggleSelectAll(visibleRideIds: Set<Long>) {
        if (visibleRideIds.isEmpty()) return
        _selectedRideIds.update { selected ->
            if (selected.containsAll(visibleRideIds)) selected - visibleRideIds else selected + visibleRideIds
        }
    }

    fun clearSelection() {
        _selectedRideIds.value = emptySet()
    }

    fun deleteRides(rideIds: Set<Long>) {
        if (rideIds.isEmpty()) return
        viewModelScope.launch {
            actionMutex.withLock {
                val attempts = mutableListOf<RideDeleteAttempt>()
                var anyQueuedOffline = false
                var rejectedCause: RideDeletion.Cause? = null
                rideIds.forEach { rideId ->
                    try {
                        val ride = rideDao.getRideWithPointsById(rideId)?.ride
                        if (ride == null || ride.endTime == null || ride.endTime <= 0L ||
                            `in`.shvms.trackme.service.TrackingService.activeRideId == rideId
                        ) {
                            attempts += RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
                            return@forEach
                        }

                        val cloudDocumentId = cloudDocumentIdForDelete(
                            rideId = ride.id,
                            firestoreId = ride.firestoreId,
                            isSynced = ride.isSynced
                        )
                        val outcome = deleteRideEverywhere(rideId, cloudDocumentId)
                        if (!RideDeletion.mayDeleteLocally(outcome)) {
                            attempts += RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
                            rejectedCause = (outcome as RideDeletion.Outcome.Rejected).cause
                            return@forEach
                        }
                        if (outcome is RideDeletion.Outcome.Queued) anyQueuedOffline = true
                        attempts += RideDeleteAttempt(localDeleted = true, cloudDeleted = true)
                    } catch (e: Exception) {
                        attempts += RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
                        errorLogger.log("Failed to delete ride $rideId")
                        errorLogger.recordException(e)
                    }
                }
                val summary = summarizeBatchDelete(attempts)
                _selectedRideIds.value = emptySet()
                rejectedCause?.let {
                    AnalyticsManager.trackRideDeleteFailed(cause = it.bucket, bulk = rideIds.size > 1)
                }
                _uiEvent.emit(
                    UiEvent.BatchDeleteCompleted(
                        summary.deletedCount,
                        summary.failedCount,
                        queuedOffline = anyQueuedOffline,
                    )
                )
            }
        }
    }

    /**
     * Removes one ride from the cloud and then from the device, in the order SCOPE_1.7.3 §0
     * contract 5 requires: **`pendingDelete` locally → cloud batch → local delete.**
     *
     * Room's `@Transaction` is SQLite-only and a Firestore batch is server-only, so no primitive
     * spans both and the ordering has to carry the correctness. The flag closes the window in both
     * directions: delete locally first and a cloud failure leaves the ride live in the cloud to be
     * re-downloaded later; delete cloud-first and a local failure leaves an unsynced ride that
     * re-uploads itself. Either way the ride comes back from the dead.
     */
    private suspend fun deleteRideEverywhere(
        rideId: Long,
        cloudDocumentId: String?,
    ): RideDeletion.Outcome {
        // Local-only ride: nothing in the cloud to race with, so the flag would be ceremony.
        if (cloudDocumentId == null) {
            rideDao.deletePointsForRide(rideId)
            rideDao.deleteRide(rideId)
            return RideDeletion.Outcome.Acknowledged
        }

        rideDao.setPendingDelete(rideId, true)
        val outcome = app.firestoreSyncManager.deleteRide(cloudDocumentId)
        if (RideDeletion.mustRestoreLocally(outcome)) {
            // Only a genuine rejection restores the row. Leaving it flagged would make it
            // invisible to the uploader and still present in History — and it would never resolve.
            rideDao.setPendingDelete(rideId, false)
            return outcome
        }
        // GPS points are not declared with a Room foreign key, so remove them explicitly before
        // the parent row to avoid orphaned data — the local mirror of children-before-parent.
        rideDao.deletePointsForRide(rideId)
        rideDao.deleteRide(rideId)
        return outcome
    }

    fun importGPX(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                val parser = GPXParser()
                val parsed = parser.parse(inputStream)
                
                // Check duplicate by TrackMeID
                if (parsed.originalTrackMeId != null) {
                    val existingRides = rideDao.getAllRidesWithPoints().first()
                    val isDuplicate = existingRides.any { 
                        it.ride.id.toString() == parsed.originalTrackMeId || 
                        it.ride.firestoreId == parsed.originalTrackMeId 
                    }
                    if (isDuplicate) {
                        _uiEvent.emit(UiEvent.ShowError("Identical ride already exists"))
                        return@launch
                    }
                }
                
                val newRideId = rideDao.insertRide(parsed.rideWithPoints.ride)
                val newPoints = parsed.rideWithPoints.points.map { it.copy(rideId = newRideId) }
                rideDao.insertGPSPoints(newPoints)
                
                app.firestoreSyncManager.uploadRide(newRideId)
                
                _uiEvent.emit(UiEvent.Success("GPX Imported Successfully"))
            } catch (e: Exception) {
                errorLogger.log("Failed to parse GPX")
                errorLogger.recordException(e)
                _uiEvent.emit(UiEvent.ShowError("Failed to import. Please ensure the file is a valid GPX format."))
            }
        }
    }

    // Filter & Grouping State
    val selectedTimeFrame = MutableStateFlow(TimeFrameOption.ALL_TIME)
    val customStartMillis = MutableStateFlow(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    val customEndMillis = MutableStateFlow(System.currentTimeMillis())
    val selectedPersonas = MutableStateFlow(RidePersona.entries.toSet())
    val searchQuery = MutableStateFlow("")
    val syncFilter = MutableStateFlow(SyncFilterOption.ALL)
    val distanceFilter = MutableStateFlow(DistanceFilterOption.ALL)
    val sortOption = MutableStateFlow(RideSortOption.NEWEST)
    val collapsedGroups = MutableStateFlow<Set<TimeGroup>>(emptySet())

    private val debouncedSearchQuery = searchQuery.debounce(250)

    val activeFilterCount: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        selectedTimeFrame, selectedPersonas, debouncedSearchQuery, distanceFilter
    ) { time, personas, query, dist ->
        var count = 0
        if (time != TimeFrameOption.ALL_TIME) count++
        if (personas.size != RidePersona.entries.size) count++
        if (query.isNotBlank()) count++
        if (dist != DistanceFilterOption.ALL) count++
        count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val customRange = kotlinx.coroutines.flow.combine(customStartMillis, customEndMillis) { start, end -> start to end }
    private val filterState: StateFlow<FilterState> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(
            selectedTimeFrame, selectedPersonas, debouncedSearchQuery, distanceFilter, sortOption
        ) { time, personas, query, dist, sort ->
            FilterState(time, personas, query, dist, sort, 0L, 0L)
        },
        customRange,
    ) { base, range ->
        base.copy(customStartMillis = range.first, customEndMillis = range.second)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FilterState(
            TimeFrameOption.ALL_TIME,
            RidePersona.entries.toSet(),
            "",
            DistanceFilterOption.ALL,
            RideSortOption.NEWEST,
            customStartMillis.value,
            customEndMillis.value,
        )
    )

    val groupedRides: StateFlow<Map<TimeGroup, List<HistoryRideSummary>>> = kotlinx.coroutines.flow.combine(
        rides, filterState
    ) { rawList, state ->
        val nowMillis = System.currentTimeMillis()

        // 1. Time Frame filter
        val timeFiltered = rawList.filter { item ->
            when (state.timeFrame) {
                TimeFrameOption.ALL_TIME -> true
                TimeFrameOption.THIS_MONTH -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    item.startTime >= cal.timeInMillis
                }
                TimeFrameOption.LAST_3_MONTHS -> item.startTime >= (nowMillis - 90L * 24 * 60 * 60 * 1000)
                TimeFrameOption.CUSTOM -> item.startTime in state.customStartMillis..state.customEndMillis
                TimeFrameOption.THIS_YEAR -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    item.startTime >= cal.timeInMillis
                }
            }
        }

        // 2. Persona and title search. Sync remains a quiet per-card affordance.
        val findableFiltered = timeFiltered.filter { item ->
            val persona = runCatching { RidePersona.valueOf(item.persona) }.getOrDefault(RidePersona.AUTO)
            val matchesPersona = persona in state.personas
            val matchesTitle = state.search.isBlank() ||
                (item.title ?: "").normalizeForSearch().contains(state.search.normalizeForSearch())
            matchesPersona && matchesTitle
        }

        // 3. Distance Filter (meters)
        val distFiltered = findableFiltered.filter { item ->
            val dist = item.distance ?: 0.0
            when (state.distance) {
                DistanceFilterOption.ALL -> true
                DistanceFilterOption.SHORT -> dist < 5000.0
                DistanceFilterOption.MEDIUM -> dist in 5000.0..20000.0
                DistanceFilterOption.LONG -> dist > 20000.0
            }
        }

        // 4. Sort
        val sorted = when (state.sort) {
            RideSortOption.NEWEST -> distFiltered.sortedByDescending { it.startTime }
            RideSortOption.OLDEST -> distFiltered.sortedBy { it.startTime }
            RideSortOption.LONGEST_DISTANCE -> distFiltered.sortedByDescending { it.distance ?: 0.0 }
            RideSortOption.FASTEST_SPEED -> distFiltered.sortedByDescending { it.avgSpeed ?: 0f }
        }

        // 5. Group into mutually exclusive buckets
        val groupedMap = java.util.LinkedHashMap<TimeGroup, MutableList<HistoryRideSummary>>()
        TimeGroup.values().forEach { group ->
            groupedMap[group] = mutableListOf()
        }

        sorted.forEach { item ->
            val group = getTimeGroupForRide(item.startTime, nowMillis)
            groupedMap[group]?.add(item)
        }

        // Filter out empty buckets
        groupedMap.filterValues { it.isNotEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setTimeFrame(option: TimeFrameOption) {
        selectedTimeFrame.value = option
    }

    fun setCustomStart(millis: Long) { customStartMillis.value = millis }

    fun setCustomEnd(millis: Long) { customEndMillis.value = millis }

    fun setSearchQuery(value: String) { searchQuery.value = value }

    fun togglePersona(persona: RidePersona) {
        selectedPersonas.update { current ->
            if (persona in current) current - persona else current + persona
        }
    }

    fun setSyncFilter(option: SyncFilterOption) {
        syncFilter.value = option
    }

    fun setDistanceFilter(option: DistanceFilterOption) {
        distanceFilter.value = option
    }

    fun setSortOption(option: RideSortOption) {
        sortOption.value = option
    }

    fun toggleGroupCollapse(group: TimeGroup) {
        val current = collapsedGroups.value.toMutableSet()
        if (current.contains(group)) {
            current.remove(group)
        } else {
            current.add(group)
        }
        collapsedGroups.value = current
    }

    fun resetFilters() {
        selectedTimeFrame.value = TimeFrameOption.ALL_TIME
        customStartMillis.value = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        customEndMillis.value = System.currentTimeMillis()
        selectedPersonas.value = RidePersona.entries.toSet()
        searchQuery.value = ""
        syncFilter.value = SyncFilterOption.ALL
        distanceFilter.value = DistanceFilterOption.ALL
    }

    companion object {
        fun getTimeGroupForRide(startTimeMillis: Long, nowMillis: Long = System.currentTimeMillis()): TimeGroup {
            val rideCal = java.util.Calendar.getInstance().apply { timeInMillis = startTimeMillis }
            val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }

            val rideYear = rideCal.get(java.util.Calendar.YEAR)
            val nowYear = nowCal.get(java.util.Calendar.YEAR)

            val rideDayOfYear = rideCal.get(java.util.Calendar.DAY_OF_YEAR)
            val nowDayOfYear = nowCal.get(java.util.Calendar.DAY_OF_YEAR)

            if (rideYear == nowYear && rideDayOfYear == nowDayOfYear) {
                return TimeGroup.TODAY
            }

            val yesterdayCal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            if (rideYear == yesterdayCal.get(java.util.Calendar.YEAR) &&
                rideDayOfYear == yesterdayCal.get(java.util.Calendar.DAY_OF_YEAR)) {
                return TimeGroup.YESTERDAY
            }

            val rideWeekOfYear = rideCal.get(java.util.Calendar.WEEK_OF_YEAR)
            val nowWeekOfYear = nowCal.get(java.util.Calendar.WEEK_OF_YEAR)
            if (rideYear == nowYear && rideWeekOfYear == nowWeekOfYear) {
                return TimeGroup.THIS_WEEK
            }

            val rideMonth = rideCal.get(java.util.Calendar.MONTH)
            val nowMonth = nowCal.get(java.util.Calendar.MONTH)
            if (rideYear == nowYear && rideMonth == nowMonth) {
                return TimeGroup.THIS_MONTH
            }

            if (rideYear == nowYear) {
                return TimeGroup.THIS_YEAR
            }

            return TimeGroup.EARLIER
        }
    }

    sealed class UiEvent {
        data class ShowError(val message: String) : UiEvent()
        data class Success(val message: String) : UiEvent()

        /**
         * [queuedOffline] is SCOPE_1.7.3 §0 contract 6's middle state — the deletion is durably
         * queued and will apply on reconnect. Not an error, and not silent either: reporting it as
         * a plain success would claim the cloud copy is already gone when it is not.
         */
        data class BatchDeleteCompleted(
            val deletedCount: Int,
            val failedCount: Int,
            val queuedOffline: Boolean = false,
        ) : UiEvent()
    }
}

enum class TimeFrameOption { ALL_TIME, THIS_MONTH, LAST_3_MONTHS, THIS_YEAR, CUSTOM }
enum class SyncFilterOption { ALL, SYNCED, LOCAL_ONLY }
enum class DistanceFilterOption { ALL, SHORT, MEDIUM, LONG }
enum class RideSortOption { NEWEST, OLDEST, LONGEST_DISTANCE, FASTEST_SPEED }
enum class TimeGroup { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, THIS_YEAR, EARLIER }

data class FilterState(
    val timeFrame: TimeFrameOption,
    val personas: Set<RidePersona>,
    val search: String,
    val distance: DistanceFilterOption,
    val sort: RideSortOption,
    val customStartMillis: Long,
    val customEndMillis: Long,
)

private fun String.normalizeForSearch(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(java.util.Locale.ROOT)
