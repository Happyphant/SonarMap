package com.sonarmap.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sonarmap.app.domain.model.GeoPoint
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapObject
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider

/** Conversions live here and nowhere else: GeoPoint/Point never leak. */
private fun GeoPoint.toYandexPoint(): Point = Point(latitude, longitude)
private fun Point.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

private const val PIN_COLOR_ARGB = 0xFF3FD2C7.toInt() // same teal as the Google provider
private const val USER_DOT_COLOR_ARGB = 0xFF4C8BF5.toInt()
private const val INITIAL_ZOOM = 13f
private const val MY_LOCATION_ZOOM = 16f

/**
 * Yandex MapKit Mobile SDK implementation of [MapProvider].
 * The only place in the app that knows about `com.yandex.mapkit`.
 * Init of MapKitFactory happens earlier, in [com.sonarmap.app.SonarMapApplication].
 */
class YandexMapProvider : MapProvider {

    @Composable
    override fun Content(
        state: MapUiState,
        callbacks: MapCallbacks,
        modifier: Modifier,
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val mapView = remember { MapView(context) }
        val currentCallbacks by rememberUpdatedState(callbacks)

        // MapKit lifecycle recipe from the official docs (Activity onStart/onStop
        // example), expressed as a Compose-agnostic lifecycle observer.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        MapKitFactory.getInstance().onStart()
                        mapView.onStart()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        mapView.onStop()
                        MapKitFactory.getInstance().onStop()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Done once per view: long tap places a pin, as everywhere in SonarMap.
        // (v4.x listener APIs take java.lang.ref.WeakReference; the listener is
        // kept in a remembered strong reference so it is not garbage collected.)
        val inputListener = remember(mapView) {
            object : InputListener {
                override fun onMapTap(map: Map, point: Point) = Unit

                override fun onMapLongTap(map: Map, point: Point) {
                    currentCallbacks.onMapLongClick(point.toGeoPoint())
                }
            }
        }
        LaunchedEffect(mapView, inputListener) {
            mapView.mapWindow.map.addInputListener(java.lang.ref.WeakReference(inputListener))
        }

        // Initial camera position: the fixed test point (no geolocation yet).
        LaunchedEffect(mapView) {
            mapView.mapWindow.map.move(cameraPosition(TEST_USER_LOCATION, INITIAL_ZOOM))
        }

        // One-shot camera flight; the dot itself moves without camera reaction.
        // Keyed on the request id, not the target: a repeated FAB press to the
        // same point must still fly (StateFlow dedups equal values).
        LaunchedEffect(state.cameraRequestId) {
            val target = state.cameraTarget
            if (state.cameraRequestId != 0 && target != null) {
                mapView.mapWindow.map.move(cameraPosition(target, MY_LOCATION_ZOOM))
            }
        }

        // Icons are rendered as small bitmaps once and reused by every placemark.
        val density = context.resources.displayMetrics.density
        val pinIcon = remember(mapView) {
            ImageProvider.fromBitmap(circleBitmap((22 * density).toInt(), PIN_COLOR_ARGB))
        }
        val userDotIcon = remember(mapView) {
            ImageProvider.fromBitmap(circleBitmap((14 * density).toInt(), USER_DOT_COLOR_ARGB))
        }

        val pinTapListener = remember(mapView) {
            object : MapObjectTapListener {
                override fun onMapObjectTap(mapObject: MapObject, point: Point): Boolean {
                    // userData carries the domain pin id; the position may not be unique.
                    (mapObject.userData as? String)?.let(currentCallbacks::onMarkerClick)
                    return true // handled: the event must not reach the plain map handler
                }
            }
        }
        val pinTapRef = remember(mapView, pinTapListener) {
            java.lang.ref.WeakReference<MapObjectTapListener>(pinTapListener)
        }

        // Full resync of map objects on every state change — the marker count for
        // the MVP is tiny, so rebuilding is simpler and has no stale-object races.
        LaunchedEffect(state.markers, state.userLocation, pinIcon, userDotIcon) {
            val mapObjects = mapView.mapWindow.map.mapObjects
            mapObjects.clear()
            state.markers.forEach { marker ->
                val placemark = mapObjects.addPlacemark(marker.position.toYandexPoint())
                placemark.userData = marker.id
                placemark.setIcon(pinIcon)
                placemark.addTapListener(pinTapRef)
            }
            // Real user dot from the location layer (FusedLocationRepository);
            // rendering unchanged since the static-test-point stage.
            state.userLocation?.let { location ->
                mapObjects.addPlacemark(location.toYandexPoint()).setIcon(userDotIcon)
            }
        }

        AndroidView(
            factory = { mapView },
            modifier = modifier,
        )
    }

    private fun cameraPosition(point: GeoPoint, zoom: Float) = CameraPosition(
        point.toYandexPoint(),
        zoom,
        0f, // azimuth
        0f, // tilt
    )

    /**
     * Solid filled circle with a white ring — enough to be visible on both
     * map styles; keep it simple (no author-selected visuals beyond color).
     */
    private fun circleBitmap(sizePx: Int, colorArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = sizePx / 2f
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, sizePx * 0.42f, ringPaint)
        canvas.drawCircle(center, center, sizePx * 0.30f, fillPaint)
        return bitmap
    }
}
