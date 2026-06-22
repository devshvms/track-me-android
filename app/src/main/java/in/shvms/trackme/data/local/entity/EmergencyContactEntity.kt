package `in`.shvms.trackme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true)
    val contactId: Long = 0,
    val name: String,
    val phoneNumber: String,
    val medium: String // "SMS", "WHATSAPP", "TELEGRAM"
)
