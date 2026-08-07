package com.example.areameasure.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.areameasure.ui.dashboard.DashboardScreen
import com.example.areameasure.ui.detail.DetailScreen
import com.example.areameasure.ui.history.HistoryScreen
import com.example.areameasure.ui.measure.MeasureScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val MEASURE = "measure"
    const val HISTORY = "history"
    const val DETAIL = "detail/{type}/{measurementId}"

    fun detailRoute(type: String, id: Long) = "detail/$type/$id"
}

@Composable
fun AreaMeasureNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onNavigateToMeasure = { navController.navigate(Routes.MEASURE) },
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) }
            )
        }

        composable(Routes.MEASURE) {
            MeasureScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) }
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onNavigateToDetail = { type, id -> navController.navigate(Routes.detailRoute(type, id)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("measurementId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("measurementId") ?: 0L
            DetailScreen(
                measurementId = id,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
