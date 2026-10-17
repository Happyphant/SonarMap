package com.sonarmap.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonarmap.app.domain.model.GeoPoint
import com.sonarmap.app.domain.model.MusicPin
import com.sonarmap.app.domain.repository.MusicPinRepository
import com.sonarmap.app.map.MapMarkerUi
import com.sonarmap.app.map.TEST_USER_LOCATION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Single source of truth for the map screen UI. */
data class MapScreenState(
    val markers: List<MapMarkerUi> = emptyList(),
    val userLocation: GeoPoint? = null,
    val selectedPin: MusicPin? = null,
)

/**
 * Keeps all pin business logic out of composables. Map interactions arrive
 * through repository calls; the map layer only consumes [MapScreenState].
 */
class MapViewModel(
    private val repository: MusicPinRepository,
) : ViewModel() {

    private val selectedPinId = MutableStateFlow<String?>(null)
    private val userLocation = MutableStateFlow<GeoPoint?>(null)

    val uiState: StateFlow<MapScreenState> = combine(
        repository.pins,
        selectedPinId,
        userLocation,
    ) { pins, selectedId, location ->
        MapScreenState(
            markers = pins.map { MapMarkerUi(id = it.id, position = it.position, title = it.title) },
            userLocation = location,
            selectedPin = pins.firstOrNull { it.id == selectedId },
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

    /** Real geolocation is out of scope; show the fixed test point for now. */
    fun onMyLocationClick() {
        userLocation.value = TEST_USER_LOCATION
    }

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
