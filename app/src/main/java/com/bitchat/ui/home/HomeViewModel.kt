package com.bitchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.data.Conversation
import com.bitchat.data.DataGraph
import com.bitchat.security.AccessControl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel : ViewModel() {

    // Personal chats (DMs) are hidden until AccessControl unlocks them.
    val conversations: StateFlow<List<Conversation>> = combine(
        DataGraph.repository.conversations(),
        AccessControl.dmUnlocked
    ) { list, unlocked ->
        if (unlocked) list else list.filter { it.isGroup || it.isBroadcast }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
