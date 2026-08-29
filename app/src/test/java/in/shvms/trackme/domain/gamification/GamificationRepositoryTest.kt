package `in`.shvms.trackme.domain.gamification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.data.local.AppPreferencesManager
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.dao.HistoryRideSummary
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class GamificationRepositoryTest {

    private lateinit var rideDao: FakeRideDao
    private lateinit var prefs: AppPreferencesManager
    private lateinit var repository: GamificationRepository
    
    private val mockRidesFlow = MutableStateFlow<List<RideEntity>>(emptyList())

    inner class FakeRideDao : RideDao {
        override fun observeAllRides(): Flow<List<RideEntity>> = mockRidesFlow
        
        override suspend fun insertRide(ride: RideEntity): Long = 1
        override suspend fun updateRide(ride: RideEntity): Int = 1
        override suspend fun insertGPSPoint(point: GPSPointEntity): Long = 1
        override suspend fun insertGPSPoints(points: List<GPSPointEntity>): List<Long> = emptyList()
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
        override suspend fun getPointsForRideSync(rideId: Long): List<GPSPointEntity> = emptyList()
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

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferencesManager(context)
        rideDao = FakeRideDao()
        repository = GamificationRepository(rideDao, prefs)
    }

    private fun createRide(durationMs: Long, qualifies: Boolean = true): RideEntity {
        return RideEntity(
            startTime = System.currentTimeMillis(),
            dashboardActiveDurationMillis = durationMs,
            qualifiesForStats = qualifies,
            postRideCalculation = PostRideCalculation(10f, 1000.0, 5f, 0)
        )
    }

    @Test
    fun `test level up reveal`() = runBlocking {
        prefs.setGamificationLastSeenLevel(1)
        mockRidesFlow.value = emptyList()
        
        assertNull(repository.newLevelReveal.first())
        
        mockRidesFlow.value = listOf(createRide(125 * 60 * 1000L))
        
        val reveal = repository.newLevelReveal.first()
        assertEquals(2, reveal?.level)
        assertEquals("Moving", reveal?.name)
        
        repository.acknowledgeNewLevel(reveal!!)
        
        assertNull(repository.newLevelReveal.first())
    }

    @Test
    fun `test achievement reveal`() = runBlocking {
        prefs.addGamificationSeenAchievements(emptySet())
        mockRidesFlow.value = emptyList()
        
        assertEquals(emptyList<String>(), repository.newAchievementsReveal.first())
        
        mockRidesFlow.value = listOf(createRide(10 * 60 * 1000L))
        
        var reveals = repository.newAchievementsReveal.first()
        assertEquals(listOf("First Qualifying Activity"), reveals)
        
        repository.acknowledgeAchievements(reveals)
        assertEquals(emptyList<String>(), repository.newAchievementsReveal.first())
        
        mockRidesFlow.value = List(5) { createRide(10 * 60 * 1000L) }
        
        reveals = repository.newAchievementsReveal.first()
        assertEquals(listOf("Getting Moving"), reveals)
    }
}
