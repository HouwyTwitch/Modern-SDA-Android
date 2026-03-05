package com.houwytwitch.modernsda.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import com.houwytwitch.modernsda.data.preferences.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

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
}
