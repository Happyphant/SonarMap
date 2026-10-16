package com.sonarmap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sonarmap.app.ui.SonarMapRoot
import com.sonarmap.app.ui.theme.SonarMapTheme

/**
 * Single-Activity entry point. All UI is Jetpack Compose; navigation happens
 * inside [SonarMapRoot] via Navigation Compose.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonarMapTheme {
                SonarMapRoot()
            }
        }
    }
}
