package com.houwytwitch.modernsda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!isQrScanActive) {
                    AppBottomNavigationBar(navController = navController)
                }
            },
        ) { innerPadding ->
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
        }
    }
}
