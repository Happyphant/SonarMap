package com.sonarmap.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sonarmap.app.ui.map.MapScreen

/**
 * App shell: a Navigation Compose host. The map screen is the entry point;
 * future screens (routes, audio mixer) plug in as new destinations.
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
                MapScreen()
            }
        }
    }
}

/** Type-safe route names for the (currently small) graph. */
internal enum class Destination(val route: String) {
    Map("map"),
}
