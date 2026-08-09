package com.bitchat.ui.groups

import androidx.lifecycle.ViewModel
import com.bitchat.mesh.MeshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GroupsViewModel : ViewModel() {

    private val _selected = MutableStateFlow<Map<String, String>>(emptyMap())
    val selected: StateFlow<Map<String, String>> = _selected.asStateFlow()

    private val _joinMessage = MutableStateFlow<String?>(null)
    val joinMessage: StateFlow<String?> = _joinMessage.asStateFlow()

    fun clearJoinMessage() {
        _joinMessage.value = null
    }

    fun joinByCode(code: String, secret: String) {
        val groupId = code.trim()
        if (groupId.isEmpty()) {
            _joinMessage.value = "Enter a room code"
            return
        }
        MeshManager.joinGroupByCode(groupId, secret.trim().ifEmpty { null }) { ok, msg ->
            _joinMessage.value = msg
        }
    }

    fun toggle(nodeId: String, displayName: String) {
        val current = _selected.value.toMutableMap()
        if (current.containsKey(nodeId)) {
            current.remove(nodeId)
        } else {
            current[nodeId] = displayName
        }
        _selected.value = current
    }

    fun createGroup(name: String): String {
        val finalName = name.trim()
        if (finalName.isEmpty()) return ""
        return MeshManager.createGroup(finalName, _selected.value.keys.toList())
    }
}