package `in`.shvms.trackme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_settings")
data class EmergencySettingsEntity(
    @PrimaryKey
    val id: Int = 1, // Singleton row
    val isSetupComplete: Boolean = false,
    val messageTemplate: String = "EMERGENCY! I need help. My last known location is: [Location Link]. Battery: [Battery Percent]. Device: [Device Name]. Time: [Timestamp]",
    val premiumToken: String? = null,
    val broadcastIntervalSeconds: Int = 120 // Configurable interval
)
