package com.example.trackme.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyManager {
    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    fun triggerEmergency() {
        _isEmergencyActive.value = true
    }

    fun stopEmergency() {
        _isEmergencyActive.value = false
    }
}
