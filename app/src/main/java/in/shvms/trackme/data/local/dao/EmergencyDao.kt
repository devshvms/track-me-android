package `in`.shvms.trackme.data.local.dao

import androidx.room.*
import `in`.shvms.trackme.data.local.entity.EmergencyContactEntity
import `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
@JvmSuppressWildcards
interface EmergencyDao {

    @Query("SELECT * FROM emergency_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<EmergencySettingsEntity?>

    @Query("SELECT * FROM emergency_settings WHERE id = 1")
    suspend fun getSettings(): EmergencySettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSettings(settings: EmergencySettingsEntity): Long

    @Query("SELECT * FROM emergency_contacts")
    fun getContactsFlow(): Flow<List<EmergencyContactEntity>>

    @Query("SELECT * FROM emergency_contacts")
    suspend fun getContacts(): List<EmergencyContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: EmergencyContactEntity): Long

    @Delete
    suspend fun deleteContact(contact: EmergencyContactEntity): Int
    
    @Query("DELETE FROM emergency_contacts")
    suspend fun deleteAllContacts(): Int

    @Query("DELETE FROM emergency_settings")
    suspend fun deleteSettings(): Int
}
