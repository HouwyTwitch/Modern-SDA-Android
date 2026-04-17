package com.houwytwitch.modernsda.ui.screens.qr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.houwytwitch.modernsda.data.repository.AccountRepository
import com.houwytwitch.modernsda.domain.steam.QrLoginResult
import com.houwytwitch.modernsda.domain.steam.QrLoginService
import com.houwytwitch.modernsda.ui.navigation.QrScanRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QrScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val qrLoginService: QrLoginService,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val steamId: Long = savedStateHandle.toRoute<QrScanRoute>().steamId

    sealed class UiState {
        data object Idle : UiState()
        data object Processing : UiState()
        data object Success : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    // Tracks the last scanned text so we don't reprocess the same QR
    private var lastScannedText = ""

    fun onQrCodeDetected(text: String) {
        val current = _uiState.value
        if (current !is UiState.Idle) return
        if (text == lastScannedText) return
        if (!qrLoginService.looksLikeSteamLoginQr(text)) return

        lastScannedText = text
        viewModelScope.launch {
            _uiState.value = UiState.Processing

            val account = accountRepository.getAccountById(steamId)
            if (account == null) {
                _uiState.value = UiState.Error("Account not found")
                return@launch
            }

            _uiState.value = when (val result = qrLoginService.approveLoginRequest(account, text)) {
                is QrLoginResult.Success -> UiState.Success
                is QrLoginResult.Error   -> UiState.Error(result.message)
            }
        }
    }

    fun resetState() {
        lastScannedText = ""
        _uiState.value = UiState.Idle
    }
}
