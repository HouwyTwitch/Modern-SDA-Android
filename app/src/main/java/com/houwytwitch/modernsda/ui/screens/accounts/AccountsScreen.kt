package com.houwytwitch.modernsda.ui.screens.accounts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.ui.components.AccountCard
import com.houwytwitch.modernsda.ui.components.TotpCodeDisplay
import com.houwytwitch.modernsda.ui.screens.accounts.dialogs.AddAccountDialog
import com.houwytwitch.modernsda.ui.screens.accounts.dialogs.EditAccountDialog
import com.houwytwitch.modernsda.ui.screens.accounts.dialogs.RemoveAccountDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onAccountSelected: (Account?) -> Unit,
    copyOnClick: Boolean,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredAccounts by viewModel.filteredAccounts.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var accountToRemove by remember { mutableStateOf<Account?>(null) }
    var pendingMafileJson by remember { mutableStateOf("") }

    // Notify parent of selected account changes
    LaunchedEffect(uiState.selectedAccount) {
        onAccountSelected(uiState.selectedAccount)
    }

    // Snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearSnackbarMessage()
            }
        }
    }

    // File picker for .mafile import — opens dialog to also collect password
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            val json = context.contentResolver.openInputStream(it)?.use { stream ->
                stream.bufferedReader().readText()
            }
            if (!json.isNullOrBlank()) {
                pendingMafileJson = json
                viewModel.showAddAccountDialog()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SDA",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("application/json", "*/*")) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add account",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            // TOTP code display for selected account
            AnimatedVisibility(
                visible = uiState.selectedAccount != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    TotpCodeDisplay(
                        code = uiState.currentCode,
                        timeRemaining = uiState.timeRemaining,
                        progressFraction = uiState.progressFraction,
                        onCopyClick = {
                            if (copyOnClick && uiState.currentCode.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Steam Code", uiState.currentCode))
                                scope.launch {
                                    snackbarHostState.showSnackbar("Copied!")
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Search field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by account name or Steam ID…") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Account list
            if (filteredAccounts.isEmpty()) {
                EmptyAccountsState(hasQuery = uiState.searchQuery.isNotBlank())
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(filteredAccounts, key = { it.steamId }) { account ->
                        AccountCard(
                            account = account,
                            isSelected = uiState.selectedAccount?.steamId == account.steamId,
                            onSelect = { viewModel.selectAccount(account) },
                            onEdit = { viewModel.showEditAccountDialog(account) },
                            onRemove = { accountToRemove = account },
                        )
                    }
                }
            }
        }
    }

    // Add account dialog — used both for file picker (json pre-filled) and manual paste
    if (uiState.showAddAccountDialog) {
        AddAccountDialog(
            initialJson = pendingMafileJson,
            onDismiss = {
                pendingMafileJson = ""
                viewModel.hideAddAccountDialog()
            },
            onConfirm = { json, password ->
                pendingMafileJson = ""
                viewModel.addAccount(json, password)
            },
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onClearError = viewModel::clearError,
        )
    }

    // Edit account dialog
    uiState.editingAccount?.let { account ->
        if (uiState.showEditAccountDialog) {
            EditAccountDialog(
                account = account,
                onDismiss = viewModel::hideEditAccountDialog,
                onConfirm = { password, proxyUrl -> viewModel.updateAccount(account, password, proxyUrl) },
            )
        }
    }

    // Remove confirmation dialog
    accountToRemove?.let { account ->
        RemoveAccountDialog(
            accountName = account.accountName,
            onDismiss = { accountToRemove = null },
            onConfirm = {
                viewModel.removeAccount(account)
                accountToRemove = null
            },
        )
    }
}

@Composable
private fun EmptyAccountsState(hasQuery: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (hasQuery) "No accounts match your search" else "No accounts added yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasQuery) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap + to import a .mafile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
