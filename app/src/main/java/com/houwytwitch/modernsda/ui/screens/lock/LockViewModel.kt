package com.houwytwitch.modernsda.ui.screens.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import com.houwytwitch.modernsda.data.security.BiometricAuthenticator
import com.houwytwitch.modernsda.data.security.PinHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LockUiState(
    val pinLength: Int = 4,
    val entered: Int = 0,
    val biometricOffered: Boolean = false,
    val error: Boolean = false,
    val verifying: Boolean = false,
    val unlocked: Boolean = false,
    val biometricAvailable: Boolean = false,
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val pinHasher: PinHasher,
    private val biometricAuthenticator: BiometricAuthenticator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState = _uiState.asStateFlow()

    private val pinBuilder = StringBuilder()

    init {
        viewModelScope.launch {
            val creds = appPreferences.getPinCredentials()
            val settings = appPreferences.settings.first()
            val usable = biometricAuthenticator.isUsable()
            _uiState.update {
                it.copy(
                    pinLength = creds?.length ?: 4,
                    biometricOffered = settings.biometricEnabled && usable,
                    biometricAvailable = usable,
                )
            }
        }
    }

    fun appendDigit(digit: Char) {
        val state = _uiState.value
        if (state.verifying || state.unlocked) return
        if (pinBuilder.length >= state.pinLength) return
        pinBuilder.append(digit)
        _uiState.update {
            it.copy(entered = pinBuilder.length, error = false)
        }
        if (pinBuilder.length == state.pinLength) {
            verifyCurrentPin()
        }
    }

    fun backspace() {
        val state = _uiState.value
        if (state.verifying || state.unlocked) return
        if (pinBuilder.isEmpty()) return
        pinBuilder.deleteCharAt(pinBuilder.length - 1)
        _uiState.update { it.copy(entered = pinBuilder.length, error = false) }
    }

    fun onBiometricSuccess() {
        _uiState.update { it.copy(unlocked = true) }
    }

    private fun verifyCurrentPin() {
        val attempt = pinBuilder.toString()
        pinBuilder.clear()
        _uiState.update { it.copy(verifying = true) }
        viewModelScope.launch {
            val creds = appPreferences.getPinCredentials()
            if (creds == null) {
                _uiState.update {
                    it.copy(unlocked = true, verifying = false, entered = 0)
                }
                return@launch
            }
            val valid = withContext(Dispatchers.Default) {
                pinHasher.verifyPin(attempt, creds.hash, creds.salt)
            }
            if (valid) {
                _uiState.update {
                    it.copy(unlocked = true, verifying = false, entered = 0, error = false)
                }
            } else {
                val fullLen = _uiState.value.pinLength
                _uiState.update {
                    it.copy(verifying = false, entered = fullLen, error = true)
                }
                delay(550)
                _uiState.update { it.copy(entered = 0, error = false) }
            }
        }
    }
}
