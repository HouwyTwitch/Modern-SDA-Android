package com.houwytwitch.modernsda.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Store
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var showPinDialog by remember { mutableStateOf(false) }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Appearance section
            SettingsSectionHeader(title = "Appearance")

            SettingsCard {
                ToggleSettingRow(
                    icon = Icons.Outlined.DarkMode,
                    title = "Dark Theme",
                    subtitle = "Use dark color scheme",
                    checked = settings.darkTheme,
                    onCheckedChange = viewModel::setDarkTheme,
                )
                ToggleSettingRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Material You",
                    subtitle = "Use dynamic colors from your wallpaper",
                    checked = settings.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Code section
            SettingsSectionHeader(title = "Authentication Code")

            SettingsCard {
                ToggleSettingRow(
                    icon = Icons.Outlined.ContentCopy,
                    title = "Click to Copy",
                    subtitle = "Copy code to clipboard when tapped",
                    checked = settings.copyOnClick,
                    onCheckedChange = viewModel::setCopyOnClick,
                )
                ToggleSettingRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Auto-refresh Code",
                    subtitle = "Automatically refresh display at set interval",
                    checked = settings.autoRefreshCode,
                    onCheckedChange = viewModel::setAutoRefreshCode,
                )

                if (settings.autoRefreshCode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Refresh Interval",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${settings.refreshIntervalSeconds}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Slider(
                        value = settings.refreshIntervalSeconds.toFloat(),
                        onValueChange = { viewModel.setRefreshInterval(it.roundToInt()) },
                        valueRange = 1f..60f,
                        steps = 58,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text("1s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("60s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Confirmations / background sync section
            SettingsSectionHeader(title = "Confirmations")

            SettingsCard {
                ToggleSettingRow(
                    icon = Icons.Outlined.Sync,
                    title = "Background Sync",
                    subtitle = "Periodically check for pending confirmations",
                    checked = settings.backgroundSyncEnabled,
                    onCheckedChange = viewModel::setBackgroundSyncEnabled,
                )

                if (settings.backgroundSyncEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sync Interval",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${settings.syncIntervalMinutes} min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Slider(
                        value = settings.syncIntervalMinutes.toFloat(),
                        onValueChange = { viewModel.setSyncIntervalMinutes(it.roundToInt()) },
                        valueRange = 15f..60f,
                        steps = 2,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text("15 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("60 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    ToggleSettingRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Notify on Pending",
                        subtitle = "Show notification when confirmations are waiting",
                        checked = settings.notifyOnPendingConfirmations,
                        onCheckedChange = viewModel::setNotifyOnPending,
                    )
                }

                ToggleSettingRow(
                    icon = Icons.Outlined.Store,
                    title = "Auto-confirm Market",
                    subtitle = "Automatically accept market listing confirmations",
                    checked = settings.autoConfirmMarket,
                    onCheckedChange = viewModel::setAutoConfirmMarket,
                )

                ToggleSettingRow(
                    icon = Icons.AutoMirrored.Outlined.CompareArrows,
                    title = "Auto-confirm Trades",
                    subtitle = "Automatically accept trade offer confirmations",
                    checked = settings.autoConfirmTrades,
                    onCheckedChange = viewModel::setAutoConfirmTrades,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSectionHeader(title = "Security")
            SettingsCard {
                ToggleSettingRow(
                    icon = Icons.Outlined.Lock,
                    title = "PIN Code",
                    subtitle = if (settings.pinCode.isNullOrBlank()) {
                        "Require PIN when opening the app"
                    } else {
                        "PIN is enabled for app unlock"
                    },
                    checked = !settings.pinCode.isNullOrBlank(),
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showPinDialog = true
                        } else {
                            viewModel.setPinCode(null)
                        }
                    },
                )
                if (!settings.pinCode.isNullOrBlank()) {
                    Button(
                        onClick = { showPinDialog = true },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        Text("Change PIN")
                    }
                    ToggleSettingRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = "Biometric Unlock",
                        subtitle = "Use fingerprint/face before PIN fallback",
                        checked = settings.biometricEnabled,
                        onCheckedChange = viewModel::setBiometricEnabled,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About section
            SettingsSectionHeader(title = "About")
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Modern SDA",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Version 1.1.1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Android port of Modern-SDA by HouwyTwitch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(88.dp))
        }
    }

    if (showPinDialog) {
        PinCodeDialog(
            currentPin = settings.pinCode,
            onDismiss = { showPinDialog = false },
            onSave = { pin ->
                viewModel.setPinCode(pin)
                showPinDialog = false
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        content()
    }
}

@Composable
private fun ToggleSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun PinCodeDialog(
    currentPin: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var pinInput by remember { mutableStateOf(currentPin.orEmpty()) }
    val isValid = pinInput.length in 4..8 && pinInput.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentPin.isNullOrBlank()) "Set PIN Code" else "Update PIN Code") },
        text = {
            OutlinedTextField(
                value = pinInput,
                onValueChange = { value ->
                    pinInput = value.filter { it.isDigit() }.take(8)
                },
                label = { Text("PIN (4-8 digits)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pinInput) },
                enabled = isValid,
            ) { Text("Save") }
        },
    )
}
