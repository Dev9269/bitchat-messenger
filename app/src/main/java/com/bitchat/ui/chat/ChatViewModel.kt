package com.bitchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bitchat.data.ChatMessage
import com.bitchat.data.DataGraph
import com.bitchat.mesh.MeshConstants
import com.bitchat.mesh.MeshManager
import com.bitchat.online.OnlineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val conversationId: String) : ViewModel() {

    private val repository = DataGraph.repository

    val isBroadcast = conversationId == MeshConstants.PUBLIC_CHANNEL_ID

    val ownNodeId: String = MeshManager.nodeId.value

    val group: StateFlow<com.bitchat.data.GroupEntity?> =
        if (isBroadcast) {
            MutableStateFlow(null)
        } else {
            repository.groupFlow(conversationId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    val isGroup: StateFlow<Boolean> = group.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isAdmin: StateFlow<Boolean> = group.map { it?.createdByNodeId == MeshManager.nodeId.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val members: StateFlow<List<com.bitchat.data.GroupMemberEntity>> =
        if (isBroadcast) {
            MutableStateFlow(emptyList())
        } else {
            repository.groupMembers(conversationId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    val title: StateFlow<String> =
        if (isBroadcast) {
            MutableStateFlow("Public channel")
        } else {
            combine(group, repository.peerFlow(conversationId)) { g, p ->
                g?.name ?: p?.displayName ?: "Unknown"
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Unknown")
        }

    val peerOnline: StateFlow<Boolean> =
        if (isBroadcast) {
            MutableStateFlow(false)
        } else {
            combine(MeshManager.peers, isGroup) { peers, isGrp ->
                !isGrp && peers.containsKey(conversationId)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
        }

    val messages: StateFlow<List<ChatMessage>> = repository.messages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(text: String) {
        if (text.isBlank()) return
        when {
            isBroadcast -> MeshManager.sendBroadcast(text)
            group.value != null -> MeshManager.sendGroupText(conversationId, text)
            else -> MeshManager.sendText(conversationId, text)
        }
    }

    fun editMessage(msgId: String, newText: String) {
        if (newText.isBlank()) return
        if (group.value != null) {
            MeshManager.editGroupMessage(conversationId, msgId, newText)
        } else {
            viewModelScope.launch { repository.updateMessageText(msgId, newText) }
        }
    }

    fun deleteMessage(msgId: String) {
        viewModelScope.launch {
            repository.deleteMessage(msgId)
        }
    }

    fun deleteConversation() {
        viewModelScope.launch {
            repository.deleteConversation(conversationId)
        }
    }

    fun deleteGroup(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            MeshManager.deleteGroup(conversationId)
            onDone()
        }
    }

    fun removeMember(nodeId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        MeshManager.removeGroupMember(conversationId, nodeId, onResult)
    }

    fun setGroupSecret(secret: String) {
        OnlineService.setGroupSecret(conversationId, secret.trim().ifEmpty { null })
    }

    fun clearGroupSecret() {
        OnlineService.setGroupSecret(conversationId, null)
    }

    companion object {
        fun factory(conversationId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(conversationId) }
        }
    }
}
