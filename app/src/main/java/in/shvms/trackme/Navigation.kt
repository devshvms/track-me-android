package `in`.shvms.trackme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.ui.home.HomeScreen
import `in`.shvms.trackme.ui.history.HistoryScreen
import `in`.shvms.trackme.ui.history.RideDetailScreen
import `in`.shvms.trackme.ui.settings.SettingsScreen

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState provided")
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("Home", "History", "Settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.History, Icons.Default.Settings)

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        alwaysShowLabel = false,
                        selected = selectedItem == index,
                        onClick = {
                            selectedItem = index
                            val route = item.lowercase()
                            navController.navigate(route) {
                                launchSingleTop = true
                                popUpTo("home")
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            composable("home") { HomeScreen() }
            composable("history") { 
                HistoryScreen(onNavigateToDetail = { id -> navController.navigate("ride_detail/$id") }) 
            }
            composable("ride_detail/{rideId}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("rideId")?.toLongOrNull() ?: return@composable
                RideDetailScreen(rideId = id, navController = navController)
            }
            composable("settings") { SettingsScreen(navController = navController) }
            composable("account_management") {
                `in`.shvms.trackme.ui.settings.AccountManagementScreen(navController = navController)
            }
            composable("emergency_setup") { 
                `in`.shvms.trackme.ui.settings.EmergencySetupScreen(
                    navController = navController
                )
            }
        }
    }
}
}
