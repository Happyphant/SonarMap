package com.sonarmap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = OceanBlue,
    secondary = SonarCyan,
    background = SurfaceLight,
    surface = SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = SonarCyan,
    secondary = OceanBlue,
    background = SurfaceDark,
    surface = SurfaceDark,
)

/** Material 3 theme following system dark mode. */
@Composable
fun SonarMapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Brand palette is deterministic on purpose: map/audio screens render
    // on top of it, dynamic wallpaper-based colors would be too unpredictable.
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = SonarMapTypography,
        content = content,
    )
}
