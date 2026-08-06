package com.bitchat.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.data.DataGraph
import com.bitchat.data.PeerEntity
import com.bitchat.mesh.MeshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _searchResults = MutableStateFlow<List<PeerEntity>>(emptyList())
    val searchResults: StateFlow<List<PeerEntity>> = _searchResults.asStateFlow()

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

    fun searchByName(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searchResults.value = DataGraph.repository.searchPeersByName(q)
        }
    }
}
