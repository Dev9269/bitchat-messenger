package com.bitchat.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.mesh.MeshManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MeshUiState(
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    val statusError: String? = null,
    val nodeId: String = "",
    val displayName: String = "",
)

class DiscoveryViewModel : ViewModel() {

    val meshState: StateFlow<MeshUiState> = combine(
        MeshManager.bluetoothEnabled,
        MeshManager.permissionsGranted,
        MeshManager.isScanning,
        MeshManager.isAdvertising,
        MeshManager.statusError,
    ) { bluetooth, permissions, scanning, advertising, error ->
        MeshUiState(
            bluetoothEnabled = bluetooth,
            permissionsGranted = permissions,
            scanning = scanning,
            advertising = advertising,
            statusError = error,
            nodeId = MeshManager.nodeId.value,
            displayName = MeshManager.displayName.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshUiState())
}
