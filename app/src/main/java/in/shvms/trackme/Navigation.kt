package `in`.shvms.trackme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Group
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.ui.home.HomeScreen
import `in`.shvms.trackme.ui.history.HistoryScreen
import `in`.shvms.trackme.ui.history.RideDetailScreen
import `in`.shvms.trackme.ui.history.MultiRideCompareRoute
import `in`.shvms.trackme.ui.settings.SettingsScreen
import `in`.shvms.trackme.ui.community.CommunityScreen
import `in`.shvms.trackme.ui.localization.LocalAppStrings

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
    val strings = LocalAppStrings.current
    val items = listOf(strings.navHome, strings.navHistory, strings.navCommunity, strings.navSettings)
    val routes = listOf("home", "history", "community", "settings")
    val icons = listOf(Icons.Default.Home, Icons.Default.History, Icons.Default.Group, Icons.Default.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // §4.6: selectedItem was a local `remember` int, not derived from the back stack — so any
    // navigation the user did not initiate from the bar (a back press, or a deep link once App
    // Links land in 1.7.1) left the wrong tab highlighted. With a fourth destination that becomes
    // visible immediately, so it is derived here rather than tracked.
    val currentRoute = navBackStackEntry?.destination?.route
    val selectedItem = routes.indexOf(currentRoute).takeIf { it >= 0 } ?: -1
    var currentScreenStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var currentScreenName by remember { mutableStateOf("") }

    LaunchedEffect(navBackStackEntry) {
        val route = navBackStackEntry?.destination?.route ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (currentScreenName.isNotEmpty() && currentScreenName != route) {
            val duration = (now - currentScreenStartTime) / 1000L
            `in`.shvms.trackme.analytics.AnalyticsManager.trackScreenViewed(currentScreenName, duration)
        }
        if (currentScreenName != route) {
            currentScreenName = route
            currentScreenStartTime = now
        }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        alwaysShowLabel = true,
                        selected = selectedItem == index,
                        onClick = {
                            val route = routes[index]
                            navController.navigate(route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
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
            composable("home") {
                HomeScreen(onOpenCommunity = { navController.navigate("community") })
            }
            composable("history") { 
                HistoryScreen(
                    onNavigateToDetail = { id -> navController.navigate("ride_detail/$id") },
                    onNavigateToComparison = { ids ->
                        navController.navigate("ride_compare/${ids.joinToString(",")}")
                    }
                )
            }
            composable("ride_compare/{rideIds}") { backStackEntry ->
                val ids = backStackEntry.arguments?.getString("rideIds")
                    ?.split(",")
                    ?.mapNotNull(String::toLongOrNull)
                    .orEmpty()
                MultiRideCompareRoute(rideIds = ids, onBack = { navController.popBackStack() })
            }
            composable("ride_detail/{rideId}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("rideId")?.toLongOrNull() ?: return@composable
                RideDetailScreen(rideId = id, navController = navController)
            }
            composable("community") {
                CommunityScreen(onNavigateToSignIn = { navController.navigate("settings") })
            }
            composable("settings") { SettingsScreen(navController = navController) }
            composable("account_management") {
                `in`.shvms.trackme.ui.settings.AccountManagementScreen(navController = navController)
            }
            composable("help_feedback") {
                `in`.shvms.trackme.ui.settings.HelpFeedbackScreen(navController = navController)
            }
        }
    }
}
}
