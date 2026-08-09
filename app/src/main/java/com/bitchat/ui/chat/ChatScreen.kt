package com.bitchat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(conversationId: String, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val viewModel: ChatViewModel = viewModel(
        key = conversationId,
        factory = ChatViewModel.factory(conversationId)
    )
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val online by viewModel.peerOnline.collectAsStateWithLifecycle()
    val isGroup by viewModel.isGroup.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteChat by remember { mutableStateOf(false) }
    var showDeleteGroup by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showGroupSecret by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var deletingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
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
                        if (isGroup) {
                            Text(
                                "Room code: $conversationId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isGroup) {
                                if (isAdmin) {
                                    DropdownMenuItem(
                                        text = { Text("Members") },
                                        onClick = {
                                            menuOpen = false
                                            showMembers = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Group secret") },
                                        onClick = {
                                            menuOpen = false
                                            showGroupSecret = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete group") },
                                        onClick = {
                                            menuOpen = false
                                            showDeleteGroup = true
                                        }
                                    )
                                }
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Delete chat") },
                                    onClick = {
                                        menuOpen = false
                                        showDeleteChat = true
                                    }
                                )
                            }
                        }
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
                        onClick = {
                            if (message.outbound) {
                                editDraft = message.text
                                editingMessage = message
                            }
                        },
                        onLongClick = {
                            if (message.outbound) deletingMessage = message
                        },
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
                                "Group chat: signed and relayed through the mesh to all members. Long-press your message to edit.",
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

    if (showDeleteChat) {
        AlertDialog(
            onDismissRequest = { showDeleteChat = false },
            title = { Text("Delete this chat?") },
            text = { Text("All messages in this chat will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteChat = false
                    viewModel.deleteConversation()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChat = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteGroup) {
        AlertDialog(
            onDismissRequest = { showDeleteGroup = false },
            title = { Text("Delete this group?") },
            text = { Text("The group will be removed for all members.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteGroup = false
                    viewModel.deleteGroup(onDone = onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroup = false }) { Text("Cancel") }
            }
        )
    }

    if (showGroupSecret) {
        var secretDraft by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGroupSecret = false },
            title = { Text("Group secret") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Only people with this code can switch into this group. " +
                            "It is stored as a SHA-256 hash only. Leave blank to keep the current code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = secretDraft,
                        onValueChange = { if (it.length <= 40) secretDraft = it },
                        label = { Text("New secret code") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGroupSecret = false
                        viewModel.setGroupSecret(secretDraft)
                    },
                    enabled = secretDraft.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        showGroupSecret = false
                        viewModel.clearGroupSecret()
                    }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showGroupSecret = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (showMembers) {
        MembersDialog(
            members = members,
            ownId = viewModel.ownNodeId,
            onRemove = { nodeId ->
                viewModel.removeMember(nodeId) { ok, msg ->
                    if (ok) showMembers = false
                }
            },
            onDismiss = { showMembers = false },
        )
    }

    editingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { if (it.length <= 1500) editDraft = it },
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editMessage(message.msgId, editDraft.trim())
                        editingMessage = null
                    },
                    enabled = editDraft.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
            }
        )
    }

    deletingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { deletingMessage = null },
            title = { Text("Delete message?") },
            text = { Text("This message will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(message.msgId)
                    deletingMessage = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingMessage = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MembersDialog(
    members: List<com.bitchat.data.GroupMemberEntity>,
    ownId: String,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            member.displayName + if (member.nodeId == ownId) " (you)" else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (member.nodeId != ownId) {
                            TextButton(onClick = { onRemove(member.nodeId) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    showSender: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
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
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
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