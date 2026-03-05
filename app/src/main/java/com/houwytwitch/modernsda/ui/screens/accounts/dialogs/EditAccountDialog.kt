package com.houwytwitch.modernsda.ui.screens.accounts.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.houwytwitch.modernsda.data.model.Account

@Composable
fun EditAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    onConfirm: (proxyUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var proxyUrl by remember { mutableStateOf(account.proxyUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Account") },
        text = {
            Column {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Steam ID: ${account.steamId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = proxyUrl,
                    onValueChange = { proxyUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTP Proxy (optional)") },
                    placeholder = { Text("http://proxy.example.com:8080") },
                    singleLine = true,
                    supportingText = { Text("Format: http://host:port") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(proxyUrl.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
