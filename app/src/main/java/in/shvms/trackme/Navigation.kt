package `in`.shvms.trackme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import `in`.shvms.trackme.ui.layout.rememberWindowClass
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
import `in`.shvms.trackme.ui.navigation.TabDoubleTapDetector
import `in`.shvms.trackme.service.TrackingState

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
    var tabScrollToTopRequest by remember { mutableIntStateOf(0) }
    // TASK-226. The platform's own double-tap timeout, so the gesture feels the same here as it
    // does everywhere else on the device.
    var tabDoubleTap by remember {
        mutableStateOf(
            TabDoubleTapDetector(
                windowMillis = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
            )
        )
    }

    /**
     * The ONE way to move between top-level tabs.
     *
     * Mixing this with a bare `navController.navigate(route)` corrupts the back stack in a way
     * that looks like the nav bar has stopped working: a raw push does not `saveState`, so a later
     * tab tap with `popUpTo(start) { saveState = true }` saves the whole pushed sub-stack under the
     * destination it is leaving. Tapping that tab again then *restores* that sub-stack — whose top
     * is the screen you were trying to leave — so you land back where you started and the tab
     * appears dead.
     *
     * That is exactly what "click sign in on Community, then Community won't open again" was.
     * Every tab-level navigation goes through here so the two idioms cannot diverge again.
     */
    fun navigateToTab(route: String) {
        if (currentRoute == route && (route == "home" || route == "history")) {
            tabScrollToTopRequest++
        }
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        }
    }

    /**
     * TASK-226: the bottom bar's and rail's own entry point, so a double-tap is only ever a
     * *rider's* two taps. Programmatic hops -- a deep-linked invite, "show member on map" -- keep
     * calling [navigateToTab] directly and cannot pair up with a real tap that follows them.
     *
     * shvm asked for this three times; the scroll-to-top of SS4.4 stays exactly as it was and this
     * sits on top of it.
     */
    fun onTabItemTapped(route: String) {
        val outcome = tabDoubleTap.tap(route, android.os.SystemClock.uptimeMillis())
        tabDoubleTap = outcome.detector
        navigateToTab(route)
        if (outcome.isDoubleTap) {
            // The tab's main page, guaranteed: drop anything still sitting above it, whether it was
            // pushed just now or restored by `restoreState`. A no-op when the route is already the
            // top of the stack, which is why the common case costs nothing.
            navController.popBackStack(route, inclusive = false)
        }
    }
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

    // §4.6 flagged this: "NavController is created inside MainNavigation()… must be hoisted for a
    // deep link to land on the Community tab." Rather than hoist the controller out to the
    // activity — which would touch every existing destination — the invite is held on the
    // application and observed here, where the controller already lives. Same outcome, none of the
    // blast radius.
    val app = LocalContext.current.applicationContext as TrackMeApp
    val trackingState by app.trackingManager.trackingState.collectAsState()
    val topLevelNavigationVisible = trackingState == TrackingState.IDLE
    val animationsEnabled = remember(app.contentResolver) {
        android.provider.Settings.Global.getFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
    val pendingInvite by app.pendingGroupInvite.collectAsState()
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) navigateToTab("community")
    }

    // App-scoped so a message survives the screen that sent it being popped — "Ride deleted"
    // is shown immediately before popBackStack(). See Messenger.kt.
    val messenger = `in`.shvms.trackme.ui.components.rememberAppMessenger(snackbarHostState)

    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        `in`.shvms.trackme.ui.components.LocalTrackMeMessenger provides messenger,
    ) {
        // A bottom bar on a wide, short window spends the axis that is already scarce, and on a
        // tablet it strands the destinations at the far edge of the screen. M3 answers both with
        // the rail above 600dp. Below it nothing changes — a phone in portrait renders exactly
        // the bar it rendered before.
        val useRail = rememberWindowClass().usesNavigationRail

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    AnimatedVisibility(
                        visible = topLevelNavigationVisible,
                        enter = if (animationsEnabled) {
                            slideInVertically(tween(300)) { it } + fadeIn(tween(300))
                        } else EnterTransition.None,
                        exit = if (animationsEnabled) {
                            slideOutVertically(tween(300)) { it } + fadeOut(tween(300))
                        } else ExitTransition.None,
                    ) {
                        NavigationBar {
                            items.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    icon = { Icon(icons[index], contentDescription = item) },
                                    label = { Text(item) },
                                    alwaysShowLabel = true,
                                    selected = selectedItem == index,
                                    onClick = { onTabItemTapped(routes[index]) }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail) {
                    AnimatedVisibility(
                        visible = topLevelNavigationVisible,
                        enter = if (animationsEnabled) fadeIn(tween(300)) else EnterTransition.None,
                        exit = if (animationsEnabled) fadeOut(tween(300)) else ExitTransition.None,
                    ) {
                        NavigationRail {
                            items.forEachIndexed { index, item ->
                                NavigationRailItem(
                                    icon = { Icon(icons[index], contentDescription = item) },
                                    label = { Text(item) },
                                    alwaysShowLabel = true,
                                    selected = selectedItem == index,
                                    onClick = { onTabItemTapped(routes[index]) }
                                )
                            }
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    composable("home") {
                        HomeScreen(
                            onOpenCommunity = { navigateToTab("community") },
                            onOpenHistory = { navigateToTab("history") },
                            onOpenRideDetail = { id -> navController.navigate("ride_detail/$id") },
                            scrollToTopRequest = tabScrollToTopRequest,
                        )
                    }
                    composable("history") { 
                        HistoryScreen(
                            onNavigateToDetail = { id -> navController.navigate("ride_detail/$id") },
                            onNavigateToComparison = { ids ->
                                navController.navigate("ride_compare/${ids.joinToString(",")}")
                            },
                            scrollToTopRequest = tabScrollToTopRequest,
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
                        CommunityScreen(
                            onNavigateToSignIn = { navigateToTab("settings") },
                            onOpenHome = { navigateToTab("home") },
                            onOpenRideDetail = { id -> navController.navigate("ride_detail/$id") },
                            // 00a74: the focus travels on the application object rather than as a route
                            // argument, for the same reason the pending invite does 2014 a parameterised
                            // `home?uid=2026` route would miss the tab-highlight lookup above and would
                            // have to be pushed with a bare navigate(), which is precisely the
                            // back-stack corruption navigateToTab's comment documents.
                            onShowMemberOnMap = { focus ->
                                app.setPendingMemberFocus(focus)
                                navigateToTab("home")
                            },
                        )
                    }
                    composable("settings") { SettingsScreen(navController = navController) }
                    composable("account_management") {
                        `in`.shvms.trackme.ui.settings.AccountManagementScreen(navController = navController)
                    }
                    composable("help_feedback") {
                        `in`.shvms.trackme.ui.settings.HelpFeedbackScreen(navController = navController)
                    }
                    // 1.8.0 design system: the token gallery and phase-2 screenshot-test surface.
                    // Debug-only — the route does not exist in release builds, so the entry point in
                    // SettingsScreen cannot navigate to a missing destination.
                    if (BuildConfig.DEBUG) {
                        composable("design_catalog") {
                            `in`.shvms.trackme.ui.catalog.DesignCatalogScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
