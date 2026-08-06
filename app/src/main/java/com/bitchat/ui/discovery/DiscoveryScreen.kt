package com.bitchat.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.data.PeerEntity
import com.bitchat.mesh.AdvertisePayload
import com.bitchat.mesh.Peer

private val OnlineGreen = Color(0xFF22C55E)
private val OfflineGray = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    state: MeshUiState,
    peers: List<Peer>,
    foundPeers: List<PeerEntity>,
    nameError: String?,
    onSearchQuery: (String) -> Unit,
    onOpenFoundChat: (String) -> Unit,
    onSetDisplayName: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Nearby devices",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        val sortedPeers = remember(peers) { peers.sortedByDescending { it.lastSeen } }
        val q = query.trim()
        val visiblePeers = if (q.isEmpty()) sortedPeers
        else sortedPeers.filter {
            it.displayName.contains(q, ignoreCase = true) || it.nodeId.contains(q, ignoreCase = true)
        }
        val visibleIds = visiblePeers.map { it.nodeId }.toSet()
        val historyMatches = foundPeers.filterNot { it.nodeId in visibleIds }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                IdentityCard(state, onSetDisplayName, nameError)
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.take(24)
                        onSearchQuery(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Find by username") },
                    placeholder = { Text("Type a name (max 6 letters)") },
                )
            }
            item {
                MeshControlsCard(
                    state = state,
                    onRequestPermissions = onRequestPermissions,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onStartMesh = onStartMesh,
                    onStopMesh = onStopMesh,
                )
            }
            if (q.isNotEmpty() && historyMatches.isNotEmpty()) {
                item {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Found in history",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "This device was seen before. Messages queue up and deliver when you meet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            historyMatches.forEach { peer ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            peer.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            peer.nodeId,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(onClick = { onOpenFoundChat(peer.nodeId) }) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                        Text("Message")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "${visiblePeers.size} nearby device${if (visiblePeers.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (q.isEmpty() && visiblePeers.any { it.isSelf }) {
                item {
                    Card {
                        Text(
                            "Self-test OK: you can see your own advertisement, so advertising is working. " +
                                "Other devices will appear here when they run Bitchat with the mesh started and are within range.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (visiblePeers.isEmpty()) {
                item {
                    EmptyStateCard()
                }
            } else {
                items(visiblePeers, key = { it.nodeId }) { peer ->
                    PeerCard(peer, onMessage = { onOpenChat(peer.nodeId) })
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(state: MeshUiState, onSetDisplayName: (String) -> Unit, nameError: String?) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(state.displayName) }
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "You are ${state.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    draft = state.displayName
                    editing = !editing
                }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit username",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(AdvertisePayload.MAX_NAME_BYTES) },
                    singleLine = true,
                    isError = nameError != null,
                    label = { Text("Username (max ${AdvertisePayload.MAX_NAME_BYTES} letters)") },
                )
                nameError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { onSetDisplayName(draft) },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("Save username")
                }
                Text(
                    "Usernames are unique: the first person to take a name keeps it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Node ID: ${state.nodeId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Other devices see you as ${state.displayName}. The node ID is your stable mesh identity, kept in local storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MeshControlsCard(
    state: MeshUiState,
    onRequestPermissions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusRow(
                label = "Bluetooth",
                value = if (state.bluetoothEnabled) "On" else "Off",
                ok = state.bluetoothEnabled,
            )
            if (!state.bluetoothEnabled) {
                Button(onClick = onOpenBluetoothSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Text("Enable Bluetooth")
                }
            }

            StatusRow(
                label = "Permissions",
                value = if (state.permissionsGranted) "Granted" else "Required",
                ok = state.permissionsGranted,
            )
            if (!state.permissionsGranted) {
                Button(onClick = onRequestPermissions) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text("Grant permissions")
                }
            }

            if (state.bluetoothEnabled && state.permissionsGranted) {
                StatusRow(
                    label = "Advertising",
                    value = if (state.advertising) "Active" else "Off",
                    ok = state.advertising,
                )
                StatusRow(
                    label = "Scanning",
                    value = if (state.scanning) "Active" else "Off",
                    ok = state.scanning,
                )

                state.statusError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (state.advertising) {
                    Button(onClick = onStopMesh) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("Stop mesh")
                    }
                } else {
                    Button(onClick = onStartMesh) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Start mesh")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (ok) OnlineGreen else OfflineGray)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) OnlineGreen else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PeerCard(peer: Peer, onMessage: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (peer.isOnline) OnlineGreen else OfflineGray)
                        )
                    }
                    Text(
                        peer.nodeId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (peer.isSelf) "This device (self-test)" else peer.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${peer.rssi} dBm",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (peer.isOnline) "online" else "lost",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (peer.isOnline) OnlineGreen else OfflineGray,
                    )
                }
            }
            if (!peer.isSelf) {
                FilledTonalButton(
                    onClick = onMessage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Text("Message")
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Radar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("No nearby devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start the mesh on another device running Bitchat and keep it in range (roughly 10-30 m indoors).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = OnlineGreen,
                )
                Text(
                    " BLE filter is on: only Bitchat nodes appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
