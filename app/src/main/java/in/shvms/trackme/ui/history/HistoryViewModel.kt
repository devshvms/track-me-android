package `in`.shvms.trackme.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.data.local.AppDatabase
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

    val rides: StateFlow<List<RideWithPoints>> = rideDao.getAllCompletedRidesWithPoints()
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
                val localSyncedCount = rides.value.count { it.ride.firestoreId != null }
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
                var deletedCount = 0
                var failedCount = 0
                val attempts = mutableListOf<RideDeleteAttempt>()
                rideIds.forEach { rideId ->
                    try {
                        val ride = rideDao.getRideWithPointsById(rideId)?.ride
                        if (ride == null || ride.endTime == null || ride.endTime <= 0L ||
                            `in`.shvms.trackme.service.TrackingService.activeRideId == rideId
                        ) {
                            attempts += RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
                            return@forEach
                        }

                        val cloudDeleteSucceeded = if (ride.firestoreId != null) {
                            app.firestoreSyncManager.deleteRide(ride.firestoreId)
                        } else {
                            true
                        }
                        // GPS points are not declared with a Room foreign key, so remove them
                        // explicitly before deleting the parent ride to avoid orphaned data.
                        rideDao.deletePointsForRide(rideId)
                        rideDao.deleteRide(rideId)
                        attempts += RideDeleteAttempt(localDeleted = true, cloudDeleted = cloudDeleteSucceeded)
                    } catch (e: Exception) {
                        attempts += RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
                        errorLogger.log("Failed to delete ride $rideId")
                        errorLogger.recordException(e)
                    }
                }
                val summary = summarizeBatchDelete(attempts)
                deletedCount = summary.deletedCount
                failedCount = summary.failedCount
                _selectedRideIds.value = emptySet()
                _uiEvent.emit(UiEvent.BatchDeleteCompleted(deletedCount, failedCount))
            }
        }
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
    val syncFilter = MutableStateFlow(SyncFilterOption.ALL)
    val distanceFilter = MutableStateFlow(DistanceFilterOption.ALL)
    val sortOption = MutableStateFlow(RideSortOption.NEWEST)
    val collapsedGroups = MutableStateFlow<Set<TimeGroup>>(emptySet())

    val activeFilterCount: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        syncFilter, distanceFilter
    ) { sync, dist ->
        var count = 0
        if (sync != SyncFilterOption.ALL) count++
        if (dist != DistanceFilterOption.ALL) count++
        count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val filterState: StateFlow<FilterState> = kotlinx.coroutines.flow.combine(
        selectedTimeFrame, syncFilter, distanceFilter, sortOption
    ) { time, sync, dist, sort ->
        FilterState(time, sync, dist, sort)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FilterState(TimeFrameOption.ALL_TIME, SyncFilterOption.ALL, DistanceFilterOption.ALL, RideSortOption.NEWEST)
    )

    val groupedRides: StateFlow<Map<TimeGroup, List<RideWithPoints>>> = kotlinx.coroutines.flow.combine(
        rides, filterState
    ) { rawList, state ->
        val nowMillis = System.currentTimeMillis()

        // 1. Time Frame filter
        val timeFiltered = rawList.filter { item ->
            when (state.timeFrame) {
                TimeFrameOption.ALL_TIME -> true
                TimeFrameOption.LAST_7_DAYS -> item.ride.startTime >= (nowMillis - 7L * 24 * 60 * 60 * 1000)
                TimeFrameOption.THIS_MONTH -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    item.ride.startTime >= cal.timeInMillis
                }
                TimeFrameOption.THIS_YEAR -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    item.ride.startTime >= cal.timeInMillis
                }
            }
        }

        // 2. Sync Filter
        val syncFiltered = timeFiltered.filter { item ->
            when (state.sync) {
                SyncFilterOption.ALL -> true
                SyncFilterOption.SYNCED -> item.ride.firestoreId != null
                SyncFilterOption.LOCAL_ONLY -> item.ride.firestoreId == null
            }
        }

        // 3. Distance Filter (meters)
        val distFiltered = syncFiltered.filter { item ->
            val dist = item.ride.postRideCalculation?.distance ?: 0.0
            when (state.distance) {
                DistanceFilterOption.ALL -> true
                DistanceFilterOption.SHORT -> dist < 5000.0
                DistanceFilterOption.MEDIUM -> dist in 5000.0..20000.0
                DistanceFilterOption.LONG -> dist > 20000.0
            }
        }

        // 4. Sort
        val sorted = when (state.sort) {
            RideSortOption.NEWEST -> distFiltered.sortedByDescending { it.ride.startTime }
            RideSortOption.OLDEST -> distFiltered.sortedBy { it.ride.startTime }
            RideSortOption.LONGEST_DISTANCE -> distFiltered.sortedByDescending { it.ride.postRideCalculation?.distance ?: 0.0 }
            RideSortOption.FASTEST_SPEED -> distFiltered.sortedByDescending { it.ride.postRideCalculation?.avgSpeed ?: 0f }
        }

        // 5. Group into mutually exclusive buckets
        val groupedMap = java.util.LinkedHashMap<TimeGroup, MutableList<RideWithPoints>>()
        TimeGroup.values().forEach { group ->
            groupedMap[group] = mutableListOf()
        }

        sorted.forEach { item ->
            val group = getTimeGroupForRide(item.ride.startTime, nowMillis)
            groupedMap[group]?.add(item)
        }

        // Filter out empty buckets
        groupedMap.filterValues { it.isNotEmpty() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setTimeFrame(option: TimeFrameOption) {
        selectedTimeFrame.value = option
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
        data class BatchDeleteCompleted(val deletedCount: Int, val failedCount: Int) : UiEvent()
    }
}

enum class TimeFrameOption { ALL_TIME, LAST_7_DAYS, THIS_MONTH, THIS_YEAR }
enum class SyncFilterOption { ALL, SYNCED, LOCAL_ONLY }
enum class DistanceFilterOption { ALL, SHORT, MEDIUM, LONG }
enum class RideSortOption { NEWEST, OLDEST, LONGEST_DISTANCE, FASTEST_SPEED }
enum class TimeGroup { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, THIS_YEAR, EARLIER }

data class FilterState(
    val timeFrame: TimeFrameOption,
    val sync: SyncFilterOption,
    val distance: DistanceFilterOption,
    val sort: RideSortOption
)
