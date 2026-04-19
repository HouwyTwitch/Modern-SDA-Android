package com.houwytwitch.modernsda.data.security

import com.houwytwitch.modernsda.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockState @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            val settings = appPreferences.settings.first()
            _isLocked.value = settings.pinEnabled
        }
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun onAppBackgrounded() {
        scope.launch {
            val settings = appPreferences.settings.first()
            if (settings.pinEnabled) _isLocked.value = true
        }
    }
}
