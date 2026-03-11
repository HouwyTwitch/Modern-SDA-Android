package com.houwytwitch.modernsda.ui.screens.confirmations

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.data.model.Confirmation
import com.houwytwitch.modernsda.data.model.ConfirmationResult
import com.houwytwitch.modernsda.data.repository.ConfirmationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ConfirmationsState {
    data object NoAccount : ConfirmationsState()
    data object Loading : ConfirmationsState()
    data class Loaded(val confirmations: List<Confirmation>) : ConfirmationsState()
    data class Empty(val message: String = "No confirmations at this moment") : ConfirmationsState()
    data class Error(val message: String) : ConfirmationsState()
}

data class ConfirmationsUiState(
    val state: ConfirmationsState = ConfirmationsState.NoAccount,
    val selectedAccount: Account? = null,
    val processingIds: Set<String> = emptySet(),
    val snackbarMessage: String? = null,
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class ConfirmationsViewModel @Inject constructor(
    private val confirmationRepository: ConfirmationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmationsUiState())
    val uiState: StateFlow<ConfirmationsUiState> = _uiState.asStateFlow()

    fun onAccountSelected(account: Account?) {
        _uiState.update { it.copy(selectedAccount = account) }
        if (account != null) {
            loadConfirmations()
        } else {
            _uiState.update { it.copy(state = ConfirmationsState.NoAccount) }
        }
    }

    fun loadConfirmations() {
        val account = _uiState.value.selectedAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(state = ConfirmationsState.Loading) }
            confirmationRepository.fetchConfirmations(account).fold(
                onSuccess = { confirmations ->
                    _uiState.update {
                        it.copy(
                            state = if (confirmations.isEmpty()) {
                                ConfirmationsState.Empty()
                            } else {
                                ConfirmationsState.Loaded(confirmations)
                            },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e("ConfirmationsVM", "loadConfirmations failed", error)
                    _uiState.update {
                        it.copy(state = ConfirmationsState.Error(error.message ?: "Failed to load confirmations"))
                    }
                },
            )
        }
    }

    fun refreshConfirmations() {
        val account = _uiState.value.selectedAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            confirmationRepository.fetchConfirmations(account).fold(
                onSuccess = { confirmations ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            state = if (confirmations.isEmpty()) {
                                ConfirmationsState.Empty()
                            } else {
                                ConfirmationsState.Loaded(confirmations)
                            },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e("ConfirmationsVM", "refreshConfirmations failed", error)
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            snackbarMessage = error.message ?: "Refresh failed",
                        )
                    }
                },
            )
        }
    }

    fun acceptConfirmation(confirmation: Confirmation) {
        val account = _uiState.value.selectedAccount ?: return
        viewModelScope.launch {
            markProcessing(confirmation.id)
            val result = confirmationRepository.acceptConfirmation(account, confirmation)
            handleActionResult(result, confirmation.id, accepted = true)
        }
    }

    fun declineConfirmation(confirmation: Confirmation) {
        val account = _uiState.value.selectedAccount ?: return
        viewModelScope.launch {
            markProcessing(confirmation.id)
            val result = confirmationRepository.declineConfirmation(account, confirmation)
            handleActionResult(result, confirmation.id, accepted = false)
        }
    }

    fun acceptAllConfirmations() {
        val account = _uiState.value.selectedAccount ?: return
        val state = _uiState.value.state
        if (state !is ConfirmationsState.Loaded) return

        viewModelScope.launch {
            val ids = state.confirmations.map { it.id }.toSet()
            _uiState.update { it.copy(processingIds = ids) }

            val result = confirmationRepository.acceptAllConfirmations(account, state.confirmations)
            when (result) {
                is ConfirmationResult.Success -> {
                    _uiState.update {
                        it.copy(
                            state = ConfirmationsState.Empty(),
                            processingIds = emptySet(),
                            snackbarMessage = "All confirmations accepted",
                        )
                    }
                }
                is ConfirmationResult.Error -> {
                    _uiState.update {
                        it.copy(
                            processingIds = emptySet(),
                            snackbarMessage = "Error: ${result.message}",
                        )
                    }
                }
            }
        }
    }

    private fun markProcessing(id: String) {
        _uiState.update { it.copy(processingIds = it.processingIds + id) }
    }

    private fun handleActionResult(result: ConfirmationResult, id: String, accepted: Boolean) {
        when (result) {
            is ConfirmationResult.Success -> {
                val currentState = _uiState.value.state
                if (currentState is ConfirmationsState.Loaded) {
                    val remaining = currentState.confirmations.filter { it.id != id }
                    _uiState.update {
                        it.copy(
                            state = if (remaining.isEmpty()) {
                                ConfirmationsState.Empty()
                            } else {
                                ConfirmationsState.Loaded(remaining)
                            },
                            processingIds = it.processingIds - id,
                            snackbarMessage = if (accepted) "Confirmation accepted" else "Confirmation declined",
                        )
                    }
                }
            }
            is ConfirmationResult.Error -> {
                _uiState.update {
                    it.copy(
                        processingIds = it.processingIds - id,
                        snackbarMessage = "Error: ${result.message}",
                    )
                }
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
