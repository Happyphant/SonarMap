package com.sonarmap.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sonarmap.app.data.AudioSourceSelectorProvider
import com.sonarmap.app.data.MusicPinRepositoryProvider
import com.sonarmap.app.data.RouteRepositoryProvider
import com.sonarmap.app.location.LocationRepositoryProvider
import com.sonarmap.app.ui.map.MapScreen
import com.sonarmap.app.ui.map.MapViewModel
import com.sonarmap.app.ui.routes.RouteListScreen
import com.sonarmap.app.ui.routes.RouteViewModel

/**
 * App shell: a Navigation Compose host. The map screen is the entry point;
 * the shared walking-audio menu (routes + pins) is the second destination.
 * BOTH ViewModels are created here, above the NavHost, and shared by both
 * destinations: editor-open state and the one active audio source must be
 * a single truth — "open from the list" lands as an open sheet on the map,
 * "activate from the list" arrives through the same ViewModel.
 */
@Composable
fun SonarMapRoot(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val mapViewModel = viewModel {
        MapViewModel(
            repository = MusicPinRepositoryProvider.repository,
            locationRepository = LocationRepositoryProvider.repository(context.applicationContext),
            sourceSelector = AudioSourceSelectorProvider.selector,
        )
    }
    val routeViewModel = viewModel {
        RouteViewModel(
            routeRepository = RouteRepositoryProvider.repository,
            pinRepository = MusicPinRepositoryProvider.repository,
            sourceSelector = AudioSourceSelectorProvider.selector,
        )
    }

    Surface(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Destination.Map.route,
        ) {
            composable(Destination.Map.route) {
                MapScreen(
                    mapViewModel = mapViewModel,
                    routeViewModel = routeViewModel,
                    onOpenRoutes = { navController.navigate(Destination.Routes.route) },
                )
            }
            composable(Destination.Routes.route) {
                RouteListScreen(
                    routeViewModel = routeViewModel,
                    mapViewModel = mapViewModel,
                    // "Open the editor": the list closes, the sheet opens
                    // over the map (the same shared ViewModel keeps it).
                    onOpenRouteEditor = { routeId ->
                        routeViewModel.openEditor(routeId)
                        navController.popBackStack()
                    },
                    onOpenPinEditor = { pinId ->
                        mapViewModel.openPinEditor(pinId)
                        navController.popBackStack()
                    },
                    // "Новый маршрут": the draft is already open in the
                    // repository; drawing continues by hand on the map.
                    onOpenMapToDraw = { navController.popBackStack() },
                    // "Новый пин": pins are placed by long-press; the map
                    // shows the placement hint once on arrival.
                    onOpenMapForPinRequest = {
                        mapViewModel.requestPinPlacementHint()
                        navController.popBackStack()
                    },
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
