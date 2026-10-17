package com.sonarmap.app.map

/**
 * The single place where the active map technology is chosen. Both providers
 * implement [MapProvider], so switching the default never touches the UI,
 * the ViewModel, or the domain layer — only this file changes.
 */
object MapProviders {

    /** Default for the MVP: Yandex MapKit (no Google Play services requirement). */
    val default: MapProvider by lazy { YandexMapProvider() }

    /** Optional alternative, kept fully working; requires Google Play services. */
    val google: MapProvider by lazy { GoogleMapsProvider() }
}
