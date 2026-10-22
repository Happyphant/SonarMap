package com.sonarmap.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sonarmap.app.data.RouteRepositoryProvider
import com.sonarmap.app.ui.map.MapScreen
import com.sonarmap.app.ui.routes.RouteListScreen
import com.sonarmap.app.ui.routes.RouteViewModel

/**
 * App shell: a Navigation Compose host. The map screen is the entry point;
 * the routes list plugs in as a second destination.
 */
@Composable
fun SonarMapRoot(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    Surface(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Destination.Map.route,
        ) {
            composable(Destination.Map.route) {
                MapScreen(
                    onOpenRoutes = { navController.navigate(Destination.Routes.route) },
                )
            }
            composable(Destination.Routes.route) {
                val routeViewModel = viewModel {
                    RouteViewModel(repository = RouteRepositoryProvider.repository)
                }
                RouteListScreen(
                    routeViewModel = routeViewModel,
                    // "Новый маршрут": the draft is already open in the
                    // repository; drawing continues by hand on the map.
                    onOpenMapToDraw = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Type-safe route names for the (currently small) graph. */
internal enum class Destination(val route: String) {
    Map("map"),
    Routes("routes"),
}
