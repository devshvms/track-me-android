package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.local.dao.HomeDashboardDao
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.domain.home.HomeDashboardSelector
import `in`.shvms.trackme.domain.home.HomeDashboardSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HomeDashboardRepository(
    private val dashboardDao: HomeDashboardDao,
    private val rideDao: RideDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _isReconciling = MutableStateFlow(true)
    val isReconciling: StateFlow<Boolean> = _isReconciling.asStateFlow()

    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(nowMillis())
            delay(60_000L)
        }
    }

    val summary: Flow<HomeDashboardSummary> = combine(dashboardDao.observeRides(), clock) { rides, now ->
        HomeDashboardSelector.select(rides, now, zoneId())
    }

    /**
     * Upgrade reconciliation is intentionally paged. Every pre-dashboard row must read its points:
     * no shipped row ever persisted active duration, even when its other aggregates exist. A
     * corrupt/empty legacy row is versioned as non-qualifying rather than guessing wall duration.
     */
    suspend fun reconcileLegacyMetadata(pageSize: Int = 25) {
        try {
            withContext(ioDispatcher) {
                require(pageSize in 1..100)
                while (true) {
                    val page = dashboardDao.getBackfillCandidates(pageSize)
                    if (page.isEmpty()) break
                    page.forEach { ride -> reconcile(ride) }
                }
            }
        } finally {
            _isReconciling.value = false
        }
    }

    suspend fun routePreview(localId: Long, limit: Int = 256): List<HomeDashboardRoutePoint> =
        withContext(ioDispatcher) {
            require(limit >= 2)
            val count = dashboardDao.getRoutePointCount(localId)
            val interiorLimit = (limit - 2).coerceAtLeast(1)
            val stride = kotlin.math.ceil((count - 2).coerceAtLeast(0) / interiorLimit.toDouble())
                .toInt()
                .coerceAtLeast(1)
            val first = dashboardDao.getFirstRoutePoint(localId)
            val last = dashboardDao.getLastRoutePoint(localId)
            if (first == null) return@withContext emptyList()
            if (last == null || last == first) return@withContext listOf(first)
            buildList {
                add(first)
                addAll(dashboardDao.getRouteInteriorPoints(localId, stride, interiorLimit))
                add(last)
            }.let { downsampleRoute(it, limit) }
        }

    private suspend fun reconcile(ride: RideEntity) {
        val existing = ride.postRideCalculation
        val points = rideDao.getPointsForRideSync(ride.id)
        val activeDuration = dashboardActiveDurationFromPoints(points)
        if (activeDuration == null) {
            rideDao.updateRide(withUnavailableDashboardMetadata(ride, points.size))
            return
        }
        val rebuilt = (existing ?: calculationFrom(points, activeDuration))?.copy(
            rawPointCount = points.size,
        )
        if (rebuilt == null) {
            rideDao.updateRide(withUnavailableDashboardMetadata(ride, points.size))
            return
        }
        rideDao.updateRide(
            withDashboardMetadata(
                ride.copy(postRideCalculation = rebuilt),
                activeDuration,
                points.size,
            )
        )
    }

    private fun calculationFrom(
        points: List<GPSPointEntity>,
        activeDurationMillis: Long,
    ): PostRideCalculation? {
        if (points.size < 2) return null
        var distance = 0.0
        var maxSpeed = points.first().speed
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            maxSpeed = maxOf(maxSpeed, current.speed)
            if (!previous.isPaused && !current.isPaused) {
                distance += haversineMeters(previous, current)
            }
        }
        val totalMillis = (points.last().timestamp - points.first().timestamp).coerceAtLeast(0L)
        return PostRideCalculation(
            maxSpeed = maxSpeed,
            distance = distance,
            avgSpeed = if (activeDurationMillis > 0) {
                (distance / (activeDurationMillis / 1_000.0)).toFloat()
            } else 0f,
            pauseDuration = (totalMillis - activeDurationMillis).coerceAtLeast(0L),
            rawPointCount = points.size,
            elevationGainMeters = `in`.shvms.trackme.domain.processor.calculateElevationGainMeters(points),
        )
    }

    private fun haversineMeters(a: GPSPointEntity, b: GPSPointEntity): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        internal fun downsampleRoute(
            points: List<HomeDashboardRoutePoint>,
            limit: Int,
        ): List<HomeDashboardRoutePoint> {
            if (points.size <= limit) return points
            val interiorSlots = limit - 2
            val lastIndex = points.lastIndex
            return buildList(limit) {
                add(points.first())
                for (slot in 1..interiorSlots) {
                    val index = ((slot.toLong() * lastIndex) / (limit - 1)).toInt()
                    add(points[index])
                }
                add(points.last())
            }
        }
    }
}
