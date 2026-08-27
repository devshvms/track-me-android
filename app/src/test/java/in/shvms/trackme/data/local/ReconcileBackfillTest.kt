package `in`.shvms.trackme.data.local

import `in`.shvms.trackme.data.local.dao.HistoryRideSummary
import `in`.shvms.trackme.data.local.dao.HomeDashboardDao
import `in`.shvms.trackme.data.local.dao.HomeDashboardRideProjection
import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade sweep had no test at all, which is why "older rides show the generic thumbnail" was a
 * question rather than an answer. TASK-231 made the route shape a *stored* fact, so every ride
 * recorded before it depends entirely on this sweep to get one.
 */
class ReconcileBackfillTest {

    private fun ride(id: Long, version: Int, pointCount: Int = 120) = RideEntity(
        id = id,
        startTime = 1_000L,
        endTime = 1_000L + 600_000L,
        dashboardActiveDurationMillis = 600_000L,
        dashboardMetadataVersion = version,
        dashboardPointCount = pointCount,
        dashboardRoutePolyline = null,
        postRideCalculation = PostRideCalculation(
            maxSpeed = 10f, distance = 5_000.0, avgSpeed = 8f, pauseDuration = 0L,
        ),
    )

    private fun points(rideId: Long, count: Int) = (0 until count).map { index ->
        GPSPointEntity(
            id = index.toLong(), rideId = rideId,
            latitude = 12.97 + index * 1e-4, longitude = 77.59 + index * 1e-4,
            altitude = 900.0, accuracy = 5f, speed = 8f,
            timestamp = 1_000L + index * 1_000L, isPaused = false,
        )
    }

    @Test
    fun `a ride left at the previous metadata version gets its route shape on upgrade`() = runTest {
        val subject = ride(id = 1L, version = 2)
        val dashboardDao = FakeDashboardDao(listOf(subject))
        val rideDao = FakeRideDao(mapOf(1L to points(1L, 120))).linkedTo(dashboardDao)
        val repository = HomeDashboardRepository(
            dashboardDao = dashboardDao,
            rideDao = rideDao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repository.reconcileLegacyMetadata()

        val written = rideDao.updated.getValue(1L)
        assertEquals(HOME_DASHBOARD_METADATA_VERSION, written.dashboardMetadataVersion)
        assertNotNull("the sweep must store a polyline", written.dashboardRoutePolyline)
        assertTrue(written.dashboardRoutePolyline!!.isNotEmpty())
    }

    /**
     * The case that explains a card still showing the generic glyph after the sweep: the row says
     * it once had points, but the points are gone. It must reconcile to a null polyline and then
     * **leave the candidate set**, or the paged loop never terminates.
     */
    @Test
    fun `a ride whose points are gone reconciles to no shape and stops being a candidate`() = runTest {
        val subject = ride(id = 2L, version = 2, pointCount = 340)
        val dashboardDao = FakeDashboardDao(listOf(subject))
        val rideDao = FakeRideDao(emptyMap()).linkedTo(dashboardDao)
        val repository = HomeDashboardRepository(
            dashboardDao = dashboardDao,
            rideDao = rideDao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repository.reconcileLegacyMetadata()

        val written = rideDao.updated.getValue(2L)
        assertNull(written.dashboardRoutePolyline)
        assertEquals(HOME_DASHBOARD_METADATA_VERSION, written.dashboardMetadataVersion)
        assertTrue("the sweep must terminate", dashboardDao.candidatePages < 10)
    }

    @Test
    fun `the sweep pages through more rides than one page holds`() = runTest {
        val rides = (1L..60L).map { ride(it, version = 2) }
        val dashboardDao = FakeDashboardDao(rides)
        val rideDao = FakeRideDao(rides.associate { it.id to points(it.id, 30) }).linkedTo(dashboardDao)
        val repository = HomeDashboardRepository(
            dashboardDao = dashboardDao,
            rideDao = rideDao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        repository.reconcileLegacyMetadata(pageSize = 25)

        assertEquals(60, rideDao.updated.size)
        assertTrue(rideDao.updated.values.all { it.dashboardRoutePolyline != null })
    }

    private class FakeDashboardDao(rides: List<RideEntity>) : HomeDashboardDao {
        private val pending = rides.associateBy { it.id }.toMutableMap()
        var candidatePages = 0

        override fun observeRides(): Flow<List<HomeDashboardRideProjection>> = flowOf(emptyList())
        override fun observeHasSampleRide(): Flow<Boolean> = flowOf(false)

        override suspend fun getBackfillCandidates(limit: Int): List<RideEntity> {
            candidatePages++
            // Mirrors the real query: only rows below the current contract version come back, so a
            // row that has been reconciled must not reappear.
            return pending.values
                .filter { it.dashboardMetadataVersion < HOME_DASHBOARD_METADATA_VERSION }
                .sortedBy { it.startTime }
                .take(limit)
        }

        fun onUpdated(ride: RideEntity) { pending[ride.id] = ride }

        override suspend fun getRoutePointCount(rideId: Long): Int = 0
        override suspend fun getFirstRoutePoint(rideId: Long): HomeDashboardRoutePoint? = null
        override suspend fun getLastRoutePoint(rideId: Long): HomeDashboardRoutePoint? = null
        override suspend fun getRouteInteriorPoints(rideId: Long, stride: Int, limit: Int) =
            emptyList<HomeDashboardRoutePoint>()
    }

    private inner class FakeRideDao(
        private val pointsByRide: Map<Long, List<GPSPointEntity>>,
    ) : RideDao {
        val updated = linkedMapOf<Long, RideEntity>()
        private var dashboardDao: FakeDashboardDao? = null

        /** Without this the reconciled row never leaves the candidate set and the sweep spins. */
        fun linkedTo(dao: FakeDashboardDao): FakeRideDao = apply { dashboardDao = dao }

        override suspend fun updateRide(ride: RideEntity): Int {
            updated[ride.id] = ride
            dashboardDao?.onUpdated(ride)
            return 1
        }
        override suspend fun getPointsForRideSync(rideId: Long): List<GPSPointEntity> =
            pointsByRide[rideId].orEmpty()

        override suspend fun insertRide(ride: RideEntity): Long = ride.id
        override suspend fun insertGPSPoint(point: GPSPointEntity): Long = point.id
        override suspend fun insertGPSPoints(points: List<GPSPointEntity>): List<Long> = points.map { it.id }
        override suspend fun getSampleRideId(): Long? = null
        override fun getAllRidesWithPoints(): Flow<List<RideWithPoints>> = flowOf(emptyList())
        override suspend fun getAllRidesWithPointsSync(): List<RideWithPoints> = emptyList()
        override fun getAllCompletedRidesWithPoints(): Flow<List<RideWithPoints>> = flowOf(emptyList())
        override fun getAllCompletedRideSummaries(): Flow<List<HistoryRideSummary>> = flowOf(emptyList())
        override fun getGroupRideSummaries(): Flow<List<HistoryRideSummary>> = flowOf(emptyList())
        override suspend fun getUncompletedRides(): List<RideEntity> = emptyList()
        override suspend fun getRideWithPointsById(rideId: Long): RideWithPoints? = null
        override fun getRideFlow(rideId: Long): Flow<RideEntity?> = flowOf(null)
        override fun getPointsForRide(rideId: Long): Flow<List<GPSPointEntity>> = flowOf(emptyList())
        override suspend fun deleteRide(rideId: Long): Int = 1
        override suspend fun deletePointsForRide(rideId: Long): Int = 1
        override suspend fun setPendingDelete(rideId: Long, pending: Boolean): Int = 1
        override suspend fun getPendingDeleteRides(): List<RideEntity> = emptyList()
        override suspend fun deleteSyncedPoints(): Int = 0
        override suspend fun deleteSyncedRides(): Int = 0
        override suspend fun markAllAsUnsynced(): Int = 0
        override suspend fun deleteAllPoints(): Int = 0
        override suspend fun deleteAllRides(): Int = 0
    }
}
