package com.houwytwitch.modernsda.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import com.houwytwitch.modernsda.data.preferences.AppSettings
import com.houwytwitch.modernsda.data.security.BiometricAuthenticator
import com.houwytwitch.modernsda.data.security.PinHasher
import com.houwytwitch.modernsda.service.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val syncScheduler: SyncScheduler,
    private val pinHasher: PinHasher,
    private val biometricAuthenticator: BiometricAuthenticator,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun isBiometricAvailable(): Boolean = biometricAuthenticator.isUsable()

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setDarkTheme(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setDynamicColor(enabled) }
    }

    fun setAutoRefreshCode(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoRefreshCode(enabled) }
    }

    fun setRefreshInterval(seconds: Int) {
        viewModelScope.launch { appPreferences.setRefreshIntervalSeconds(seconds.coerceIn(1, 60)) }
    }

    fun setCopyOnClick(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setCopyOnClick(enabled) }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setBackgroundSyncEnabled(enabled)
            if (enabled) {
                val interval = appPreferences.settings.first().syncIntervalMinutes.toLong()
                syncScheduler.schedule(interval)
            } else {
                syncScheduler.cancel()
            }
        }
    }

    fun setSyncIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val clamped = minutes.coerceIn(15, 60)
            appPreferences.setSyncIntervalMinutes(clamped)
            if (appPreferences.settings.first().backgroundSyncEnabled) {
                syncScheduler.schedule(clamped.toLong())
            }
        }
    }

    fun setAutoConfirmMarket(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoConfirmMarket(enabled) }
    }

    fun setAutoConfirmTrades(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAutoConfirmTrades(enabled) }
    }

    fun setNotifyOnPending(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setNotifyOnPendingConfirmations(enabled) }
    }

    fun savePin(pin: String) {
        viewModelScope.launch {
            val hashed = withContext(Dispatchers.Default) { pinHasher.hashPin(pin) }
            appPreferences.savePinCredentials(
                hash = hashed.hash,
                salt = hashed.salt,
                length = pin.length,
            )
        }
    }

    fun disablePin() {
        viewModelScope.launch { appPreferences.clearPinCredentials() }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setBiometricEnabled(enabled) }
    }
}
