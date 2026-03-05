package com.houwytwitch.modernsda.ui.screens.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.data.model.AccountWithCode
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import com.houwytwitch.modernsda.data.repository.AccountRepository
import com.houwytwitch.modernsda.data.repository.AddAccountResult
import com.houwytwitch.modernsda.domain.steam.SteamTotp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val accountsWithCodes: List<AccountWithCode> = emptyList(),
    val selectedAccount: Account? = null,
    val currentCode: String = "",
    val timeRemaining: Int = 30,
    val progressFraction: Float = 1f,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
    val showAddAccountDialog: Boolean = false,
    val showEditAccountDialog: Boolean = false,
    val editingAccount: Account? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _allAccounts = MutableStateFlow<List<Account>>(emptyList())

    val filteredAccounts: StateFlow<List<Account>> = combine(
        _allAccounts,
        _searchQuery,
    ) { accounts, query ->
        if (query.isBlank()) accounts
        else accounts.filter {
            it.accountName.contains(query, ignoreCase = true) ||
            it.steamId.toString().contains(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var codeRefreshJob: Job? = null

    init {
        observeAccounts()
        startCodeRefresh()
    }

    private fun observeAccounts() {
        viewModelScope.launch {
            accountRepository.accounts.collect { accounts ->
                _allAccounts.value = accounts

                // Auto-select first account if none selected
                val current = _uiState.value.selectedAccount
                if (current == null && accounts.isNotEmpty()) {
                    selectAccount(accounts.first())
                } else if (current != null) {
                    // Update selected account data if it changed
                    val updated = accounts.find { it.steamId == current.steamId }
                    if (updated != null && updated != current) {
                        _uiState.update { it.copy(selectedAccount = updated) }
                    } else if (updated == null) {
                        // Selected account was removed
                        val next = accounts.firstOrNull()
                        _uiState.update { it.copy(selectedAccount = next) }
                        if (next != null) generateCode(next)
                    }
                }
            }
        }
    }

    private fun startCodeRefresh() {
        codeRefreshJob?.cancel()
        codeRefreshJob = viewModelScope.launch {
            while (isActive) {
                val remaining = SteamTotp.getTimeRemaining()
                val fraction = SteamTotp.getProgressFraction()

                val selected = _uiState.value.selectedAccount
                val code = selected?.let { SteamTotp.generateCode(it.sharedSecret) } ?: ""

                _uiState.update { state ->
                    state.copy(
                        currentCode = code,
                        timeRemaining = remaining,
                        progressFraction = fraction,
                    )
                }
                delay(500)
            }
        }
    }

    fun selectAccount(account: Account) {
        _uiState.update { it.copy(selectedAccount = account) }
        generateCode(account)
        viewModelScope.launch {
            appPreferences.setLastSelectedSteamId(account.steamId)
        }
    }

    private fun generateCode(account: Account) {
        val code = SteamTotp.generateCode(account.sharedSecret)
        _uiState.update { it.copy(currentCode = code) }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun showAddAccountDialog() {
        _uiState.update { it.copy(showAddAccountDialog = true) }
    }

    fun hideAddAccountDialog() {
        _uiState.update { it.copy(showAddAccountDialog = false) }
    }

    fun showEditAccountDialog(account: Account) {
        _uiState.update { it.copy(showEditAccountDialog = true, editingAccount = account) }
    }

    fun hideEditAccountDialog() {
        _uiState.update { it.copy(showEditAccountDialog = false, editingAccount = null) }
    }

    fun addAccount(mafileJson: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = accountRepository.addAccountFromMafile(mafileJson)) {
                is AddAccountResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showAddAccountDialog = false,
                            snackbarMessage = "Account added successfully",
                        )
                    }
                }
                is AddAccountResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message,
                        )
                    }
                }
            }
        }
    }

    fun removeAccount(account: Account) {
        viewModelScope.launch {
            accountRepository.removeAccount(account)
            _uiState.update { it.copy(snackbarMessage = "Account removed") }
        }
    }

    fun updateAccountProxy(account: Account, proxyUrl: String) {
        viewModelScope.launch {
            accountRepository.updateAccount(account.copy(proxyUrl = proxyUrl))
            hideEditAccountDialog()
            _uiState.update { it.copy(snackbarMessage = "Account updated") }
        }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        codeRefreshJob?.cancel()
    }
}
