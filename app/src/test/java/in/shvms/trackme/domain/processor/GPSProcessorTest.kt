package `in`.shvms.trackme.domain.processor

import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.dao.HistoryRideSummary
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GPSProcessorTest {

    private val testDistance = GeoDistanceCalculator { from, to ->
        val latDelta = to.latitude - from.latitude
        val lngDelta = to.longitude - from.longitude
        kotlin.math.sqrt(latDelta * latDelta + lngDelta * lngDelta).toFloat() * 1_000f
    }

    @Test
    fun disabledProcessingLeavesRideUntouched() = runTest {
        val dao = FakeRideDao()

        DefaultGPSProcessor(testDistance).processRide(7L, dao, isEnabled = false)

        assertEquals(0, dao.getPointsCalls)
        assertNull(dao.updatedRide)
    }

    @Test
    fun processingDropsAccelerationOutlierAndPersistsSmoothedStats() = runTest {
        val ride = RideEntity(id = 7L, startTime = 1_000L)
        val points = listOf(
            point(id = 1L, rideId = 7L, longitude = 0.000, altitude = 10.0, timestamp = 1_000L),
            // 100m in one second from the previous point: exceeds the 2G acceleration limit.
            point(id = 2L, rideId = 7L, longitude = 0.100, altitude = 50.0, timestamp = 2_000L),
            point(id = 3L, rideId = 7L, longitude = 0.002, altitude = 20.0, timestamp = 3_000L)
        )
        val dao = FakeRideDao(RideWithPoints(ride, points))

        DefaultGPSProcessor(testDistance).processRide(7L, dao, isEnabled = true)

        assertEquals(1, dao.getPointsCalls)
        assertEquals(listOf(1L, 3L), dao.insertedPoints.map { it.id })
        assertEquals(3, dao.updatedRide?.postRideCalculation?.rawPointCount)
        assertEquals(2.0, dao.updatedRide?.postRideCalculation?.distance ?: 0.0, 0.001)
        assertEquals(1.0f, dao.updatedRide?.postRideCalculation?.maxSpeed ?: 0f, 0.001f)
        assertEquals(15.0, dao.insertedPoints.first().altitude, 0.001)
        assertEquals(15.0, dao.insertedPoints.last().altitude, 0.001)
        assertNotNull(dao.updatedRide?.postRideCalculation)
    }

    @Test
    fun processingKeepsSeparateChunksAcrossGpsLoss() = runTest {
        val ride = RideEntity(id = 9L, startTime = 1_000L)
        val points = listOf(
            point(id = 1L, rideId = 9L, longitude = 0.000, timestamp = 1_000L),
            point(id = 2L, rideId = 9L, longitude = 0.001, timestamp = 2_000L),
            point(id = 3L, rideId = 9L, longitude = 0.002, timestamp = 20_000L),
            point(id = 4L, rideId = 9L, longitude = 0.003, timestamp = 21_000L)
        )
        val dao = FakeRideDao(RideWithPoints(ride, points))

        DefaultGPSProcessor(testDistance).processRide(9L, dao, isEnabled = true)

        assertEquals(listOf(1L, 2L, 3L, 4L), dao.insertedPoints.map { it.id })
        assertEquals(2.0, dao.updatedRide?.postRideCalculation?.distance ?: 0.0, 0.001)
    }

    @Test
    fun processingDerivesPeakFromTheSameObservedMovementAsAverage() = runTest {
        val ride = RideEntity(id = 10L, startTime = 1_000L)
        val points = listOf(
            point(id = 1L, rideId = 10L, longitude = 0.0000, timestamp = 1_000L),
            point(id = 2L, rideId = 10L, longitude = 0.0014, timestamp = 2_000L),
            point(id = 3L, rideId = 10L, longitude = 0.0028, timestamp = 3_000L),
            point(id = 4L, rideId = 10L, longitude = 0.0042, timestamp = 4_000L),
        )
        val dao = FakeRideDao(RideWithPoints(ride, points))

        DefaultGPSProcessor(testDistance).processRide(10L, dao, isEnabled = true)

        val calculation = dao.updatedRide?.postRideCalculation
        assertNotNull(calculation)
        assertEquals(1.4f, calculation?.avgSpeed ?: 0f, 0.001f)
        assertEquals(1.4f, calculation?.maxSpeed ?: 0f, 0.001f)
    }

    @Test
    fun routeCompressionDoesNotDiscardPeakSpeedEvidence() = runTest {
        val ride = RideEntity(id = 11L, startTime = 1_000L)
        val points = (0..8).map { index ->
            point(
                id = index.toLong() + 1L,
                rideId = 11L,
                longitude = index * 0.001,
                speed = if (index == 4) 5f else 1f,
                timestamp = 1_000L + index * 1_000L,
            )
        }
        val dao = FakeRideDao(RideWithPoints(ride, points))

        DefaultGPSProcessor(testDistance).processRide(11L, dao, isEnabled = true)

        assertEquals(2, dao.insertedPoints.size)
        assertEquals(1.8f, dao.updatedRide?.postRideCalculation?.maxSpeed ?: 0f, 0.001f)
    }

    @Test
    fun pauseAndGpsGapEvidenceCannotBecomePeakSpeed() = runTest {
        val pausedRide = RideEntity(id = 12L, startTime = 1_000L)
        val pausedPoints = listOf(
            point(id = 1L, rideId = 12L, longitude = 0.000, timestamp = 1_000L),
            point(
                id = 2L,
                rideId = 12L,
                longitude = 0.001,
                speed = 8f,
                timestamp = 2_000L,
                isPaused = true,
            ),
            point(id = 3L, rideId = 12L, longitude = 0.002, timestamp = 3_000L),
        )
        val pausedDao = FakeRideDao(RideWithPoints(pausedRide, pausedPoints))

        DefaultGPSProcessor(testDistance).processRide(12L, pausedDao, isEnabled = true)

        assertEquals(0f, pausedDao.updatedRide?.postRideCalculation?.maxSpeed ?: -1f, 0.001f)

        val gapRide = RideEntity(id = 13L, startTime = 1_000L)
        val gapPoints = listOf(
            point(id = 1L, rideId = 13L, longitude = 0.000, speed = 12f, timestamp = 1_000L),
            point(id = 2L, rideId = 13L, longitude = 0.100, speed = 12f, timestamp = 31_000L),
        )
        val gapDao = FakeRideDao(RideWithPoints(gapRide, gapPoints))

        DefaultGPSProcessor(testDistance).processRide(13L, gapDao, isEnabled = true)

        assertEquals(0f, gapDao.updatedRide?.postRideCalculation?.maxSpeed ?: -1f, 0.001f)
    }

    @Test
    fun invalidReportedSpeedsDoNotCorruptObservedPeakSpeed() = runTest {
        val ride = RideEntity(id = 14L, startTime = 1_000L)
        val points = listOf(
            point(
                id = 1L,
                rideId = 14L,
                longitude = 0.000,
                speed = Float.NaN,
                timestamp = 1_000L,
            ),
            point(
                id = 2L,
                rideId = 14L,
                longitude = 0.001,
                speed = -2f,
                timestamp = 2_000L,
            ),
            point(
                id = 3L,
                rideId = 14L,
                longitude = 0.002,
                speed = Float.POSITIVE_INFINITY,
                timestamp = 3_000L,
            ),
        )
        val dao = FakeRideDao(RideWithPoints(ride, points))

        DefaultGPSProcessor(testDistance).processRide(14L, dao, isEnabled = true)

        assertEquals(1f, dao.updatedRide?.postRideCalculation?.avgSpeed ?: 0f, 0.001f)
        assertEquals(1f, dao.updatedRide?.postRideCalculation?.maxSpeed ?: 0f, 0.001f)
    }

    private fun point(
        id: Long,
        rideId: Long,
        longitude: Double,
        altitude: Double = 0.0,
        speed: Float = 1f,
        isPaused: Boolean = false,
        timestamp: Long
    ) = GPSPointEntity(
        id = id,
        rideId = rideId,
        latitude = 0.0,
        longitude = longitude,
        altitude = altitude,
        accuracy = 5f,
        speed = speed,
        timestamp = timestamp,
        isPaused = isPaused,
    )

    private class FakeRideDao(
        private val rideWithPoints: RideWithPoints? = null
    ) : RideDao {
        /** TASK-275: unused here — neither the reconciler nor the processor imports. */
        override suspend fun countByContentHash(hash: String): Int = 0

        var getPointsCalls = 0
        var insertedPoints: List<GPSPointEntity> = emptyList()
        var updatedRide: RideEntity? = null

        override suspend fun insertRide(ride: RideEntity): Long = ride.id
        override suspend fun updateRide(ride: RideEntity): Int {
            updatedRide = ride
            return 1
        }
        override suspend fun insertGPSPoint(point: GPSPointEntity): Long = point.id
        override suspend fun insertGPSPoints(points: List<GPSPointEntity>): List<Long> {
            insertedPoints = points
            return points.map { it.id }
        }
        override suspend fun getSampleRideId(): Long? = null
        override fun getAllRidesWithPoints(): Flow<List<RideWithPoints>> = flowOf(emptyList())
        override suspend fun getAllRidesWithPointsSync(): List<RideWithPoints> = emptyList()
        override fun getAllCompletedRidesWithPoints(): Flow<List<RideWithPoints>> = flowOf(emptyList())
        override fun getAllCompletedRideSummaries(): Flow<List<HistoryRideSummary>> = flowOf(emptyList())
        override fun getGroupRideSummaries(): Flow<List<HistoryRideSummary>> = flowOf(emptyList())
        override suspend fun getUncompletedRides(): List<RideEntity> = emptyList()
        override suspend fun getRideWithPointsById(rideId: Long): RideWithPoints? = rideWithPoints
        override fun getRideFlow(rideId: Long): Flow<RideEntity?> = flowOf(rideWithPoints?.ride)
        override fun getPointsForRide(rideId: Long): Flow<List<GPSPointEntity>> {
            getPointsCalls++
            return flowOf(rideWithPoints?.points.orEmpty())
        }
        override suspend fun getPointsForRideSync(rideId: Long): List<GPSPointEntity> = rideWithPoints?.points.orEmpty()
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
