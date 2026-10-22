package com.sonarmap.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonarmap.app.data.AudioSourceSelector
import com.sonarmap.app.domain.geo.bearingDegrees
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
    /** All pins, newest last; the list screen renders them directly. */
    val pins: List<MusicPin> = emptyList(),
    val userLocation: GeoPoint? = null,
    /** The pin whose editor sheet is open (editing selection only — the
     *  AUDIO source is [activePin], independent of the sheet). */
    val selectedPin: MusicPin? = null,
    /** Great-circle meters from the current fix to the open pin; null when
     *  either is unknown. Drives the sheet's live distance line. */
    val distanceToSelectedPin: Double? = null,
    /** Compass bearing user->pin in [0, 360); null when either is unknown. */
    val bearingToSelectedPin: Double? = null,
    /** The active walking-audio pin (null when a route is active or none). */
    val activePin: MusicPin? = null,
    /** Raw id of the active pin (list-screen badges); null = none. */
    val activePinId: String? = null,
    /** One-shot camera flight request; null when nothing was requested. */
    val cameraTarget: GeoPoint? = null,
    /** Bumped on every flight request so repeats fire despite StateFlow dedup. */
    val cameraRequestId: Int = 0,
    val locationNotice: LocationNotice? = null,
    /** One-shot user hint requested from another screen; null = nothing. */
    val mapHint: String? = null,
)

/**
 * Keeps all pin business logic out of composables. Map interactions arrive
 * through repository calls; the map layer only consumes [MapScreenState].
 * Route activation crosses over through [AudioSourceSelector]: activating
 * a pin deactivates the active route, and every activation of a route
 * clears the active pin.
 */
class MapViewModel(
    private val repository: MusicPinRepository,
    private val locationRepository: LocationRepository,
    private val sourceSelector: AudioSourceSelector,
) : ViewModel() {

    private val selectedPinId = MutableStateFlow<String?>(null)
    private val userLocation = MutableStateFlow<GeoPoint?>(null)

    /** Pending camera flight: target + id. Null = nothing was requested. */
    private val cameraRequest = MutableStateFlow<CameraRequest?>(null)

    private data class CameraRequest(val target: GeoPoint, val id: Int)

    private val locationNotice = MutableStateFlow<LocationNotice?>(null)

    /** One-shot hint text for the map screen (e.g. how to drop a pin). */
    private val mapHint = MutableStateFlow<String?>(null)

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
        repository.activePinId,
        selectedPinId,
        userLocation,
        combine(cameraRequest, locationNotice, mapHint) { camera, notice, hint ->
            Triple(camera, notice, hint)
        },
    ) { pins, activeId, selectedId, location, extras ->
        val (camera, notice, hint) = extras
        val selected = pins.firstOrNull { it.id == selectedId }
        MapScreenState(
            markers = pins.map { MapMarkerUi(id = it.id, position = it.position, title = it.title) },
            pins = pins,
            userLocation = location,
            selectedPin = selected,
            distanceToSelectedPin = selected?.let { pin ->
                location?.let { fix -> distanceMeters(fix, pin.position) }
            },
            bearingToSelectedPin = selected?.let { pin ->
                location?.let { fix -> bearingDegrees(fix, pin.position) }
            },
            activePin = pins.firstOrNull { it.id == activeId },
            activePinId = activeId,
            cameraTarget = camera?.target,
            cameraRequestId = camera?.id ?: 0,
            locationNotice = notice,
            mapHint = hint,
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

    /**
     * Pin activation toggle (the pin audio plays by proximity while active):
     * a non-null id makes this pin THE active source and deactivates the
     * active route along with any impression the two could coexist.
     */
    fun setPinActive(pinId: String, active: Boolean) {
        viewModelScope.launch { sourceSelector.selectPin(if (active) pinId else null) }
    }

    /** Another screen asks the map to explain how a pin is placed. */
    fun requestPinPlacementHint() {
        mapHint.value = "Удерживайте карту, чтобы поставить метку"
    }

    fun consumeMapHint() {
        mapHint.value = null
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
