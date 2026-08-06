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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ChatViewModel(private val conversationId: String) : ViewModel() {

    private val repository = DataGraph.repository

    val isBroadcast = conversationId == MeshConstants.PUBLIC_CHANNEL_ID

    val title: StateFlow<String> =
        if (isBroadcast) {
            MutableStateFlow("Public channel")
        } else {
            repository.peerFlow(conversationId)
                .map { it?.displayName ?: "Unknown" }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Unknown")
        }

    val peerOnline: StateFlow<Boolean> =
        if (isBroadcast) {
            MutableStateFlow(false)
        } else {
            MeshManager.peers.map { it.containsKey(conversationId) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
        }

    val messages: StateFlow<List<ChatMessage>> = repository.messages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(text: String) {
        if (text.isBlank()) return
        MeshManager.sendText(conversationId, text)
    }

    companion object {
        fun factory(conversationId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(conversationId) }
        }
    }
}
