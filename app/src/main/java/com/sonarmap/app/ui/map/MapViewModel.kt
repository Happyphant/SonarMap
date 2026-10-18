package com.sonarmap.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonarmap.app.domain.geo.distanceMeters
import com.sonarmap.app.domain.location.LocationRepository
import com.sonarmap.app.domain.location.LocationUpdate
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.map.MapMarkerUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why location is not working right now; UI maps these to human text. */
enum class LocationNotice {
    PermissionDenied,
    PermissionPermanentlyDenied,
    ProviderDisabled,
    WaitingForFix,
}

/** Single source of truth for the map screen UI. */
data class MapScreenState(
    val markers: List<MapMarkerUi> = emptyList(),
    val userLocation: GeoPoint? = null,
    val selectedPin: MusicPin? = null,
    /** Great-circle meters from the current fix to the open pin; null when
     *  either is unknown. Drives proximity features (audio gain later). */
    val distanceToSelectedPin: Double? = null,
    /** One-shot camera flight request; null when nothing was requested. */
    val cameraTarget: GeoPoint? = null,
    /** Bumped on every flight request so repeats fire despite StateFlow dedup. */
    val cameraRequestId: Int = 0,
    val locationNotice: LocationNotice? = null,
)

/**
 * Keeps all pin business logic out of composables. Map interactions arrive
 * through repository calls; the map layer only consumes [MapScreenState].
 */
class MapViewModel(
    private val repository: MusicPinRepository,
    private val locationRepository: LocationRepository,
) : ViewModel() {

    private val selectedPinId = MutableStateFlow<String?>(null)
    private val userLocation = MutableStateFlow<GeoPoint?>(null)

    /** Pending camera flight: target + id. Null = nothing was requested. */
    private val cameraRequest = MutableStateFlow<CameraRequest?>(null)

    private data class CameraRequest(val target: GeoPoint, val id: Int)

    private val locationNotice = MutableStateFlow<LocationNotice?>(null)

    /** Whether the user has asked for positioning at least once. */
    private var locationRequested = false
    private var locationJob: Job? = null
    /** Set on a FAB press without a known position; consumed by the next fix. */
    private var pendingCameraFlight = false
    /** Monotonically increasing flight id — beats StateFlow value dedup. */
    private var cameraSeq = 0
    /** Camera centered on the user at least once this process lifetime. */
    private var hasFlown = false

    val uiState: StateFlow<MapScreenState> = combine(
        repository.pins,
        selectedPinId,
        userLocation,
        cameraRequest,
        locationNotice,
    ) { pins, selectedId, location, camera, notice ->
        val selected = pins.firstOrNull { it.id == selectedId }
        MapScreenState(
            markers = pins.map { MapMarkerUi(id = it.id, position = it.position, title = it.title) },
            userLocation = location,
            selectedPin = selected,
            distanceToSelectedPin = selected?.let { pin ->
                location?.let { fix -> distanceMeters(fix, pin.position) }
            },
            cameraTarget = camera?.target,
            cameraRequestId = camera?.id ?: 0,
            locationNotice = notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapScreenState(),
    )

    fun addPin(position: GeoPoint) {
        viewModelScope.launch { repository.addPin(position, defaultTitle = "") }
    }

    fun openPinEditor(markerId: String) {
        selectedPinId.value = markerId
    }

    fun closePinEditor() {
        selectedPinId.value = null
    }

    // --- location ------------------------------------------------------------

    /**
     * "Моё местоположение" (FAB): explicit request — always flies the camera
     * (recentering), then starts/keeps tracking.
     */
    fun onMyLocationClick() {
        locationRequested = true
        val known = userLocation.value
        if (known != null) {
            // Known position: fly right now; the next smoothed fix may refine
            // the dot but must not re-trigger the flight.
            cameraRequest.value = CameraRequest(known, ++cameraSeq)
            hasFlown = true
        } else {
            pendingCameraFlight = true // first fix carries the flight
        }
        startLocationTracking()
    }

    /**
     * Tracking resumed because the permission is already granted (screen
     * entry or the permission dialog's granted result). Shows the dot
     * without user action; the camera flies once per process lifetime on
     * the first fix ([hasFlown]) so every app open centers on the user
     * exactly once — later resumes never yank the camera.
     */
    fun onLocationGranted() {
        locationRequested = true
        startLocationTracking()
    }

    /** Compose permission flow told us the final denial state. */
    fun onLocationPermissionDenied(permanently: Boolean) {
        locationNotice.value = if (permanently) {
            LocationNotice.PermissionPermanentlyDenied
        } else {
            LocationNotice.PermissionDenied
        }
    }

    /** Screen became visible: resume tracking only if it was requested before. */
    fun startLocationTracking() {
        if (!locationRequested || locationJob != null) return
        locationJob = viewModelScope.launch {
            locationRepository.updates().collect { update -> handleLocationUpdate(update) }
        }.also { job -> job.invokeOnCompletion { if (locationJob == job) locationJob = null } }
    }

    /** Screen became invisible: no updates in background, ever. */
    fun stopLocationTracking() {
        (locationJob ?: return).cancel()
        locationJob = null
    }

    private fun handleLocationUpdate(update: LocationUpdate) {
        when (update) {
            is LocationUpdate.Fix -> {
                locationNotice.value = null
                userLocation.value = update.location.position
                if (pendingCameraFlight || !hasFlown) {
                    pendingCameraFlight = false
                    hasFlown = true
                    // Fly exactly once per request (plus the very first fix of
                    // the process); later smoothed fixes only move the dot so
                    // the user keeps panning freedom. The id bump lets a
                    // repeated FAB press fly to the same point.
                    cameraRequest.value =
                        CameraRequest(update.location.position, ++cameraSeq)
                }
            }

            LocationUpdate.WaitingForFix ->
                locationNotice.value = LocationNotice.WaitingForFix

            LocationUpdate.PermissionDenied -> {
                locationNotice.value = LocationNotice.PermissionDenied
                locationRequested = false
            }

            LocationUpdate.ProviderDisabled -> {
                locationNotice.value = LocationNotice.ProviderDisabled
                locationRequested = false
            }
        }
    }

    // --- pin editing ---------------------------------------------------------

    fun renamePin(title: String) {
        val id = selectedPinId.value ?: return
        viewModelScope.launch { repository.renamePin(id, title) }
    }

    fun setPinGain(gain: Double) {
        val id = selectedPinId.value ?: return
        viewModelScope.launch { repository.updateGain(id, gain) }
    }

    fun selectAudioFile(uri: String) {
        val id = selectedPinId.value ?: return
        viewModelScope.launch { repository.updateAudioAssetUri(id, uri) }
    }

    fun deleteSelectedPin() {
        val id = selectedPinId.value ?: return
        selectedPinId.value = null
        viewModelScope.launch { repository.removePin(id) }
    }
}
