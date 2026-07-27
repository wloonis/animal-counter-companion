package com.animalcounter.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.animalcounter.R
import com.animalcounter.net.JetsonConnectionManager
import com.animalcounter.ui.dashboard.DashboardScreen
import com.animalcounter.ui.history.HistoryScreen
import com.animalcounter.ui.livecount.LiveCountScreen
import com.animalcounter.ui.sessiondetail.SessionDetailScreen
import com.animalcounter.ui.sessiondetail.VideoDetailScreen
import com.animalcounter.ui.sessions.SessionsScreen
import com.animalcounter.ui.settings.SettingsScreen

private object Destinations {
    const val LIVE_COUNT = "live-count"
    const val HISTORY = "history"
    const val DASHBOARD = "dashboard"
    const val SESSIONS_TAB = "sessions-tab"
    const val SESSION_DETAIL = "session/{sessionId}"
    const val VIDEO_DETAIL = "video/{videoId}?filename={filename}&countDelta={countDelta}&duration={duration}&fileDuration={fileDuration}&status={status}&sessionId={sessionId}&ts={ts}"
    const val SESSIONS = "sessions?days={days}"
    const val SETTINGS = "settings"
}

@Composable
fun AnimalCounterApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // App-lifecycle-scoped connection management: start the WiFi probe /
    // ~30s keep-alive / POST /api/time loop on app foreground (ON_START) and
    // fully stop it on background (ON_STOP) — never runs in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> JetsonConnectionManager.start(context)
                Lifecycle.Event.ON_STOP -> JetsonConnectionManager.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == Destinations.DASHBOARD,
                    onClick = { navController.navigateTo(Destinations.DASHBOARD) },
                    icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                    label = { TabLabel(R.string.tab_dashboard) },
                )
                NavigationBarItem(
                    selected = currentRoute == Destinations.LIVE_COUNT,
                    onClick = { navController.navigateTo(Destinations.LIVE_COUNT) },
                    icon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                    label = { TabLabel(R.string.tab_live_count) },
                )
                NavigationBarItem(
                    selected = currentRoute == Destinations.HISTORY,
                    onClick = { navController.navigateTo(Destinations.HISTORY) },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { TabLabel(R.string.tab_history) },
                )
                NavigationBarItem(
                    selected = currentRoute?.startsWith("sessions") == true,
                    onClick = { navController.navigateTo(Destinations.SESSIONS_TAB) },
                    icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                    label = { TabLabel(R.string.tab_sessions) },
                )
                NavigationBarItem(
                    selected = currentRoute == Destinations.SETTINGS,
                    onClick = { navController.navigateTo(Destinations.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { TabLabel(R.string.tab_settings) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Destinations.SETTINGS) { SettingsScreen() }
            composable(Destinations.LIVE_COUNT) { LiveCountScreen() }
            composable(Destinations.HISTORY) { HistoryScreen(navController) }
            composable(Destinations.DASHBOARD) {
                DashboardScreen(onSessionsClick = { days ->
                    navController.navigate("sessions?days=$days")
                })
            }
            composable(Destinations.SESSIONS_TAB) { SessionsScreen(navController, onBack = null) }
            composable(
                route = Destinations.VIDEO_DETAIL,
                arguments = listOf(
                    navArgument("videoId") {
                        type = NavType.StringType
                        nullable = false
                    },
                    navArgument("filename") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("countDelta") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("duration") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("fileDuration") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("status") {
                        type = NavType.StringType
                        defaultValue = "unknown"
                    },
                    navArgument("sessionId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("ts") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                VideoDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Destinations.SESSIONS,
                arguments = listOf(
                    navArgument("days") {
                        type = NavType.StringType
                        defaultValue = "1"
                    },
                ),
            ) {
                SessionsScreen(navController, onBack = { navController.popBackStack() })
            }
            composable(
                route = Destinations.SESSION_DETAIL,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                        nullable = false
                    },
                ),
            ) { backStackEntry ->
                SessionDetailScreen(
                    sessionId = backStackEntry.arguments?.getString("sessionId"),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun TabLabel(resId: Int) {
    Text(
        stringResource(resId),
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .wrapContentHeight(Alignment.CenterVertically),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun NavController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}