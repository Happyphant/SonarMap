import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Both map API keys come from local.properties (gitignored). Never commit real keys.
val properties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// Google Maps API key -> manifest placeholder (used by GoogleMapsProvider).
val mapsApiKey: String = properties.getProperty("MAPS_API_KEY") ?: "MISSING_MAPS_API_KEY"
// MapKit API key -> BuildConfig field (used by YandexMapProvider), per the
// official "Getting started" recipe for keeping keys out of VCS.
val mapkitApiKey: String = properties.getProperty("MAPKIT_API_KEY") ?: "MISSING_MAPKIT_API_KEY"

android {
    namespace = "com.sonarmap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonarmap.app"
        minSdk = 26 // See README rationale: notification channels for the future foreground
        // location service, java.time without desugaring, no legacy permission state.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        // MapKit reads the key via BuildConfig in SonarMapApplication.onCreate.
        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Needed for BuildConfig.MAPKIT_API_KEY (AGP 8 disables it by default).
        buildConfig = true
    }
}

dependencies {
    // Core + Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose (versions come from the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Coroutines / Flow
    implementation(libs.kotlinx.coroutines.android)

    // Maps: only the map package (providers) may use these.
    // Yandex MapKit — default provider; Google Maps — kept working, optional.
    implementation(libs.yandex.mapkit)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    // Location: only the location package may use this.
    implementation(libs.play.services.location)

    // Debug tooling for previews
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pure JVM unit tests for the domain layer (no Android mockable classes needed)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
