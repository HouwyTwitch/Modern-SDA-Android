package com.houwytwitch.modernsda.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.serialization.Serializable

// Type-safe navigation routes
@Serializable object AccountsRoute
@Serializable object ConfirmationsRoute
@Serializable object SettingsRoute

data class TopLevelRoute(
    val label: String,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelRoutes = listOf(
    TopLevelRoute(
        label = "Accounts",
        route = AccountsRoute,
        selectedIcon = Icons.Filled.AccountCircle,
        unselectedIcon = Icons.Outlined.AccountCircle,
    ),
    TopLevelRoute(
        label = "Confirmations",
        route = ConfirmationsRoute,
        selectedIcon = Icons.Filled.CheckCircle,
        unselectedIcon = Icons.Outlined.CheckCircle,
    ),
    TopLevelRoute(
        label = "Settings",
        route = SettingsRoute,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)

@Composable
fun AppBottomNavigationBar(navController: NavHostController) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination

    NavigationBar {
        topLevelRoutes.forEach { topLevel ->
            val isSelected = currentDestination?.hasRoute(topLevel.route::class) == true
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(topLevel.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (isSelected) topLevel.selectedIcon else topLevel.unselectedIcon,
                        contentDescription = topLevel.label,
                    )
                },
                label = { Text(topLevel.label) },
            )
        }
    }
}
