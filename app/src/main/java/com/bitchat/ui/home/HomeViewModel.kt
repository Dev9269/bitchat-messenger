package com.bitchat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.data.Conversation
import com.bitchat.data.DataGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = DataGraph.repository.conversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
