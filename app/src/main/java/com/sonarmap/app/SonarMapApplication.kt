package com.sonarmap.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory

/**
 * Process-wide SDK initialisation.
 *
 * MapKitFactory.initialize(Context) must finish before the first MapView is
 * created. Application.onCreate() guarantees the ordering: it always completes
 * before any Activity (and therefore any composition hosting a MapView).
 */
class SonarMapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Locale must be set before initialize(); the key comes from
        // BuildConfig, which is generated from the gitignored local.properties
        // (MAPKIT_API_KEY=...) — the key itself never enters version control.
        MapKitFactory.setLocale("ru_RU")
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        MapKitFactory.initialize(this)
    }
}
