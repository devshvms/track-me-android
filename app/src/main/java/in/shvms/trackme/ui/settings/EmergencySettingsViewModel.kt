package `in`.shvms.trackme.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.data.local.entity.EmergencyContactEntity
import `in`.shvms.trackme.data.local.entity.EmergencySettingsEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EmergencySettingsViewModel(private val app: TrackMeApp) : ViewModel() {
    private val emergencyDao = app.database.emergencyDao()
    private val firestoreSyncManager = app.firestoreSyncManager
    private val authManager = app.authManager

    val settings = emergencyDao.getSettingsFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val contacts = emergencyDao.getContactsFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            if (emergencyDao.getSettings() == null) {
                emergencyDao.updateSettings(EmergencySettingsEntity())
            }
        }
        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                if (user == null) {
                    // Disable local config if logged out
                    settings.value?.let { current ->
                        emergencyDao.updateSettings(current.copy(isSetupComplete = false))
                    }
                }
            }
        }
    }

    fun updateTemplate(template: String) {
        viewModelScope.launch {
            settings.value?.let { current ->
                emergencyDao.updateSettings(current.copy(messageTemplate = template))
            }
        }
    }

    fun syncUpstream() {
        firestoreSyncManager.syncEmergencyConfigUpstream()
    }

    fun logTestMessage(text: String, recipient: String) {
        firestoreSyncManager.logEmergencyMessage(System.currentTimeMillis(), text, recipient, "TEST")
    }

    fun completeSetupAndSync(template: String) {
        viewModelScope.launch {
            settings.value?.let { current ->
                emergencyDao.updateSettings(current.copy(isSetupComplete = true, messageTemplate = template))
                syncUpstream()
            }
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.value?.let { current ->
                emergencyDao.updateSettings(current.copy(isSetupComplete = enabled))
                syncUpstream()
            }
        }
    }

    fun addContact(name: String, phone: String, medium: String) {
        viewModelScope.launch {
            emergencyDao.insertContact(EmergencyContactEntity(name = name, phoneNumber = phone, medium = medium))
        }
    }

    fun deleteContact(contact: EmergencyContactEntity) {
        viewModelScope.launch {
            emergencyDao.deleteContact(contact)
        }
    }
}

class EmergencySettingsViewModelFactory(private val app: TrackMeApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EmergencySettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EmergencySettingsViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
