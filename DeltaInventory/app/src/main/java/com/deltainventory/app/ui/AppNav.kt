package com.deltainventory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.deltainventory.app.domain.ScanViewModel

object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val REVIEW = "review"
    const val STATS = "stats"
    const val GRID = "grid"
    const val HISTORY = "history"
    const val DATABASE = "database"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{instanceId}"
    fun detail(id: String) = "detail/$id"
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF4CAF50),
            background = androidx.compose.ui.graphics.Color(0xFF0D1117),
            surface = androidx.compose.ui.graphics.Color(0xFF161B22)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(onStartScan: () -> Unit, onStopScan: () -> Unit, vm: ScanViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val navController = rememberNavController()
    data class NavItem(val route: String, val label: String, val icon: ImageVector)
    val items = listOf(
        NavItem(Routes.HOME, "主页", Icons.Default.Home),
        NavItem(Routes.SCAN, "盘点", Icons.Default.Scanner),
        NavItem(Routes.REVIEW, "待确认", Icons.Default.FactCheck),
        NavItem(Routes.STATS, "统计", Icons.Default.Analytics),
        NavItem(Routes.GRID, "背包", Icons.Default.GridView),
        NavItem(Routes.HISTORY, "历史", Icons.Default.History),
        NavItem(Routes.DATABASE, "数据库", Icons.Default.Storage),
        NavItem(Routes.SETTINGS, "设置", Icons.Default.Settings)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = navController.currentBackStackEntryAsState().value?.destination
                items.take(5).forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, item.label) },
                        label = { Text(item.label) },
                        selected = current?.hierarchy?.any { it.route == item.route } == true,
                        onClick = { navController.navigate(item.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true } }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = Routes.HOME, Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(
                onNavigate = { navController.navigate(it) },
                extraActions = listOf("历史" to Routes.HISTORY, "数据库" to Routes.DATABASE, "设置" to Routes.SETTINGS)
            ) }
            composable(Routes.SCAN) { ScanScreen(vm, onStartScan, onStopScan) }
            composable(Routes.REVIEW) { ReviewScreen(vm, onDetail = { navController.navigate(Routes.detail(it)) }) }
            composable(Routes.STATS) { StatsScreen(vm) }
            composable(Routes.GRID) { GridScreen(vm) }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.DATABASE) { DatabaseScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.DETAIL) { backStackEntry ->
                InstanceDetailScreen(backStackEntry.arguments?.getString("instanceId") ?: "", vm)
            }
        }
    }
}
