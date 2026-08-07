package com.bitchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.data.ChatMessage
import com.bitchat.data.STATUS_DELIVERED
import com.bitchat.data.STATUS_PENDING
import com.bitchat.data.STATUS_SENT
import com.bitchat.ui.formatTime

private val OnlineGreen = Color(0xFF22C55E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversationId: String, onBack: () -> Unit) {
    val viewModel: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(conversationId)
    )
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val online by viewModel.peerOnline.collectAsStateWithLifecycle()
    val isGroup by viewModel.isGroup.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        if (!viewModel.isBroadcast && !isGroup) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (online) OnlineGreen else MaterialTheme.colorScheme.outlineVariant)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        showSender = viewModel.isBroadcast || isGroup,
                    )
                }
                if (viewModel.isBroadcast) {
                    item {
                        Card {
                            Text(
                                "Public channel: sent to every node in range, relayed through the mesh. Signed, not encrypted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                if (isGroup) {
                    item {
                        Card {
                            Text(
                                "Group chat: signed and relayed through the mesh to all members.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        if (it.length <= 1500) input = it
                    },
                    placeholder = { Text(if (viewModel.isBroadcast) "Broadcast message" else "Message") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        viewModel.send(input.trim())
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showSender: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outbound) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (message.outbound) Alignment.End else Alignment.Start,
        ) {
            if (!message.outbound && showSender) {
                Text(
                    "Node-" + message.srcNodeId.take(4).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp, start = 12.dp),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        color = if (message.outbound) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = if (message.outbound) 12.dp else 4.dp,
                            bottomEnd = if (message.outbound) 4.dp else 12.dp,
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (message.outbound) {
                    formatTime(message.timestamp) + " · " + statusLabel(message.deliveryStatus)
                } else {
                    formatTime(message.timestamp)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

private fun statusLabel(status: Int): String = when (status) {
    STATUS_PENDING -> "pending"
    STATUS_SENT -> "sent"
    STATUS_DELIVERED -> "delivered"
    else -> "failed"
}
