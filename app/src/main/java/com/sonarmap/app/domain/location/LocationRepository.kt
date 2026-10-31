package com.sonarmap.app.domain.location

import com.sonarmap.app.domain.model.UserLocation
import kotlinx.coroutines.flow.Flow

/**
 * What the location layer reports. A plain Flow<UserLocation> cannot carry
 * the four required failure modes (permission denied, provider disabled,
 * no fix yet), so successes and diagnostics share one sealed stream;
 * consumers switch on the kind exactly like on AudioPlaybackState.
 */
sealed interface LocationUpdate {

    /**
     * A usable position fix. [location] is already smoothed by the
     * repository — consumers must never smooth again downstream.
     * [speedMeters]/[bearingDegrees] are RAW pass-through of the underlying
     * fix (NOT smoothed): the fused-heading layer needs the device's own
     * motion direction, and smoothing a cyclic bearing through position
     * filters would corrupt it. null = the fix carries no such field
     * (hasBearing()/hasSpeed() false) — the fusion treats it as "no fix
     * course this tick".
     */
    data class Fix(
        val location: UserLocation,
        val speedMeters: Double? = null,
        val bearingDegrees: Double? = null,
    ) : LocationUpdate

    /** Tracking is on, but no satellite/clustered fix has arrived yet. */
    data object WaitingForFix : LocationUpdate

    /** Runtime location permission is missing; updates cannot be requested. */
    data object PermissionDenied : LocationUpdate

    /** Device-level location services (GPS/network) are switched off. */
    data object ProviderDisabled : LocationUpdate
}

/**
 * Storage/technology boundary for the user's position — the location-layer
 * counterpart of MusicPinRepository. The first implementation is
 * com.sonarmap.app.location.FusedLocationRepository (FusedLocationProviderClient);
 * a test/geocoding stub or another engine must fit the same shape.
 *
 * Contract:
 * - [updates] is a cold flow: the underlying hardware subscription is
 *     registered while collected and released on cancellation, so leaving
 *     the screen (or VM teardown) cancels updates — never run in background.
 * - Fix payloads are smoothed; diagnostics are emitted before any fix.
 */
interface LocationRepository {

    fun updates(): Flow<LocationUpdate>
}
