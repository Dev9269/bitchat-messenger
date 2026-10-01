package com.bitchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.data.Conversation
import com.bitchat.data.DataGraph
import com.bitchat.mesh.MeshManager
import com.bitchat.security.AccessControl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeMeshState(
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val livePeerCount: Int = 0,
    val statusError: String? = null,
) {
    val isRunning: Boolean
        get() = isScanning || isAdvertising
}

class HomeViewModel : ViewModel() {

    // Personal chats (DMs) are hidden until AccessControl unlocks them.
    val conversations: StateFlow<List<Conversation>> = combine(
        DataGraph.repository.conversations(),
        AccessControl.dmUnlocked
    ) { list, unlocked ->
        if (unlocked) list else list.filter { it.isGroup || it.isBroadcast }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val meshRuntimeState = combine(
        MeshManager.bluetoothEnabled,
        MeshManager.permissionsGranted,
        MeshManager.isScanning,
        MeshManager.isAdvertising,
        MeshManager.peers,
    ) { bluetoothEnabled, permissionsGranted, isScanning, isAdvertising, peers ->
        HomeMeshState(
            bluetoothEnabled = bluetoothEnabled,
            permissionsGranted = permissionsGranted,
            isScanning = isScanning,
            isAdvertising = isAdvertising,
            livePeerCount = peers.values.count { it.isOnline && !it.isSelf },
        )
    }

    val meshState: StateFlow<HomeMeshState> = combine(
        meshRuntimeState,
        MeshManager.statusError,
    ) { runtimeState, error ->
        runtimeState.copy(statusError = error)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeMeshState())
}
