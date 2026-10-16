package com.sonarmap.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sonarmap.app.ui.theme.SonarMapTheme

/**
 * App shell: a Navigation Compose host with a single placeholder destination.
 * Future screens (map, audio, location) plug in here as new destinations.
 */
@Composable
fun SonarMapRoot(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    SonarMapTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
            ) {
                composable(Destination.Home.route) {
                    SonarPlaceholderScreen()
                }
            }
        }
    }
}

/** Type-safe route names for the (currently small) graph. */
internal enum class Destination(val route: String) {
    Home("home"),
}
