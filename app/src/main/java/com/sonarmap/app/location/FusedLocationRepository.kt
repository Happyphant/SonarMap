package com.sonarmap.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sonarmap.app.domain.location.KalmanLocationSmoother
import com.sonarmap.app.domain.location.LocationRepository
import com.sonarmap.app.domain.location.LocationSmoother
import com.sonarmap.app.domain.location.LocationUpdate
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.UserLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * FusedLocationProviderClient-backed [LocationRepository].
 * The only place in the app that knows about `com.google.android.gms.location`.
 *
 * Power policy (skill rules): while-in-use updates only — the subscription
 * lives exactly as long as the flow is collected, so it dies when the map
 * screen leaves composition, stops on ON_STOP (through the ViewModel's
 * stop call) and on process death. No foreground service at this stage.
 *
 * Battery-aware accuracy: `Priority.PRIORITY_BALANCED_POWER_ACCURACY`
 * (good to ~10 m) is enough for a map dot and pin distances; HIGH_ACCURACY
 * would burn antennas for numbers the user cannot perceive.
 */
class FusedLocationRepository(
    context: Context,
    private val smoother: LocationSmoother = KalmanLocationSmoother(),
) : LocationRepository {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    override fun updates(): Flow<LocationUpdate> = callbackFlow {
        // 1. Permission gate — checked, never asked (the request UX lives in
        //    the UI layer where the user's intent and rationale belong).
        val fineGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fineGranted && !coarseGranted) {
            trySend(LocationUpdate.PermissionDenied)
            close()
            return@callbackFlow
        }

        // 2. Device-level location gate (master switch: airplane/off).
        // isLocationEnabled() is the master switch itself; per-provider
        // checks lie on some OEMs (Samsung keeps providers "enabled" while
        // geodata is globally off).
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (!locationManager.isLocationEnabled) {
            trySend(LocationUpdate.ProviderDisabled)
            close()
            return@callbackFlow
        }

        // 3. "No fix" watchdog if the device never reports anything.
        val noFixWatchdog = launch {
            delay(NO_FIX_TIMEOUT_MS)
            trySend(LocationUpdate.WaitingForFix)
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // First real fix disarms the watchdog permanently.
                noFixWatchdog.cancel()
                val fix = result.lastLocation ?: return
                val raw = UserLocation(
                    position = GeoPoint(fix.latitude, fix.longitude),
                    // Fused fix heading is unreliable without constant speed
                    // sampling; audio bearing logic comes in a later stage.
                    headingDegrees = 0.0,
                    accuracyMeters = fix.accuracy.toDouble(),
                    timestamp = fix.time,
                )
                trySend(LocationUpdate.Fix(smoother.smooth(raw)))
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        try {
            // Lint wants the manifest permission as a hard prerequisite, and
            // the gate above may race a revoke; both are handled here.
            @SuppressLint("MissingPermission")
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            noFixWatchdog.cancel()
            trySend(LocationUpdate.PermissionDenied)
            close()
            return@callbackFlow
        }

        awaitClose {
            noFixWatchdog.cancel()
            fusedClient.removeLocationUpdates(callback)
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UPDATE_INTERVAL_MS = 2_000L
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
        const val NO_FIX_TIMEOUT_MS = 15_000L
    }
}

/**
 * Process-wide singleton so the repository (and thus the smoother state)
 * survives ViewModel recreation and back-stack changes.
 */
object LocationRepositoryProvider {

    @Volatile
    private var instance: LocationRepository? = null

    fun repository(context: Context): LocationRepository =
        instance ?: synchronized(this) {
            instance ?: FusedLocationRepository(context).also { instance = it }
        }
}
