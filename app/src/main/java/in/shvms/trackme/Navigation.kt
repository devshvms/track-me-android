package `in`.shvms.trackme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import `in`.shvms.trackme.ui.home.HomeScreen
import `in`.shvms.trackme.ui.history.HistoryScreen
import `in`.shvms.trackme.ui.history.RideDetailScreen
import `in`.shvms.trackme.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("Home", "History", "Settings")

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { },
                        label = { Text(item) },
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
            modifier = Modifier.padding(innerPadding)
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
            composable("emergency_setup") { 
                `in`.shvms.trackme.ui.settings.EmergencySetupScreen(
                    navController = navController
                )
            }
        }
    }
}
