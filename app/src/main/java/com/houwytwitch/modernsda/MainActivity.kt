package com.houwytwitch.modernsda

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.ui.navigation.AccountsRoute
import com.houwytwitch.modernsda.ui.navigation.AppBottomNavigationBar
import com.houwytwitch.modernsda.ui.navigation.ConfirmationsRoute
import com.houwytwitch.modernsda.ui.navigation.QrScanRoute
import com.houwytwitch.modernsda.ui.navigation.SettingsRoute
import com.houwytwitch.modernsda.ui.screens.accounts.AccountsScreen
import com.houwytwitch.modernsda.ui.screens.confirmations.ConfirmationsScreen
import com.houwytwitch.modernsda.ui.screens.qr.QrScanScreen
import com.houwytwitch.modernsda.ui.screens.settings.SettingsScreen
import com.houwytwitch.modernsda.ui.screens.settings.SettingsViewModel
import com.houwytwitch.modernsda.ui.theme.ModernSdaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainContent()
        }
    }
}

@Composable
private fun MainContent() {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsState()

    ModernSdaTheme(
        darkTheme = settings.darkTheme,
        dynamicColor = settings.useDynamicColor,
    ) {
        val navController = rememberNavController()
        var selectedAccount by remember { mutableStateOf<Account?>(null) }

        val currentBackStackEntry by navController.currentBackStackEntryAsState()
        val isQrScanActive = currentBackStackEntry?.destination?.hasRoute(QrScanRoute::class) == true
        var isUnlocked by remember(settings.pinCode) { mutableStateOf(settings.pinCode.isNullOrBlank()) }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!isQrScanActive && isUnlocked) {
                    AppBottomNavigationBar(navController = navController)
                }
            },
        ) { innerPadding ->
            if (isUnlocked) {
                NavHost(
                    navController = navController,
                    startDestination = AccountsRoute,
                    modifier = Modifier.padding(innerPadding),
                    enterTransition = { fadeIn(tween(200)) },
                    exitTransition = { fadeOut(tween(200)) },
                ) {
                    composable<AccountsRoute> {
                        AccountsScreen(
                            onAccountSelected = { account -> selectedAccount = account },
                            copyOnClick = settings.copyOnClick,
                            onQrScanClick = { steamId ->
                                navController.navigate(QrScanRoute(steamId))
                            },
                        )
                    }
                    composable<ConfirmationsRoute> {
                        ConfirmationsScreen(selectedAccount = selectedAccount)
                    }
                    composable<SettingsRoute> {
                        SettingsScreen()
                    }
                    composable<QrScanRoute> {
                        QrScanScreen(onBack = { navController.popBackStack() })
                    }
                }
            } else {
                AppLockDialog(
                    pinCode = settings.pinCode,
                    biometricEnabled = settings.biometricEnabled,
                    onUnlock = { isUnlocked = true },
                )
            }
        }
    }
}

@Composable
private fun AppLockDialog(
    pinCode: String?,
    biometricEnabled: Boolean,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var biometricAttempted by remember { mutableStateOf(false) }
    val canUseBiometric = remember(context, biometricEnabled) {
        biometricEnabled &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    BackHandler(enabled = true) {}

    LaunchedEffect(canUseBiometric, biometricAttempted) {
        if (canUseBiometric && !biometricAttempted) {
            biometricAttempted = true
            val activity = context as? FragmentActivity ?: return@LaunchedEffect
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onUnlock()
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Modern SDA")
                .setSubtitle("Authenticate to continue")
                .setNegativeButtonText("Use PIN")
                .build()
            prompt.authenticate(info)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("App Locked") },
        text = {
            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    pinInput = it.filter(Char::isDigit).take(8)
                    showError = false
                },
                label = { Text("Enter PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = if (showError) {
                    { Text("Incorrect PIN") }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!pinCode.isNullOrBlank() && pinInput == pinCode) {
                        onUnlock()
                    } else {
                        showError = true
                    }
                },
            ) { Text("Unlock") }
        },
        dismissButton = {
            if (canUseBiometric) {
                TextButton(onClick = { biometricAttempted = false }) { Text("Use Biometric") }
            }
        },
    )
}
