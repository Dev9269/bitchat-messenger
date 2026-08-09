package com.bitchat.ui.groups

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.mesh.Peer
import com.bitchat.data.PeerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    peers: List<Peer>,
    searchResults: List<PeerEntity>,
    onSearchQuery: (String) -> Unit,
    onBack: () -> Unit,
    onGroupCreated: (String) -> Unit,
) {
    val viewModel: GroupsViewModel = viewModel()
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResultsVisible by remember { mutableStateOf(false) }

    val selected = viewModel.selected.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group", fontWeight = FontWeight.SemiBold) },
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
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            var roomCode by rememberSaveable { mutableStateOf("") }
            var groupSecret by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = roomCode,
                onValueChange = { if (it.length <= 40) roomCode = it },
                label = { Text("Join a group — enter room code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = groupSecret,
                onValueChange = { if (it.length <= 40) groupSecret = it },
                label = { Text("Group secret (only if the group is protected)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            val joinMsg = viewModel.joinMessage.collectAsStateWithLifecycle().value
            if (joinMsg != null) {
                Text(
                    joinMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Button(
                onClick = {
                    viewModel.clearJoinMessage()
                    viewModel.joinByCode(roomCode, groupSecret)
                },
                enabled = roomCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Join with code")
            }
            Text(
                "One group at a time: joining a new group replaces the current one. Protected groups need their secret code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                "Add members",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { q ->
                    query = q
                    searchResultsVisible = q.isNotEmpty()
                    onSearchQuery(q)
                },
                label = { Text("Find by username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (searchResultsVisible && searchResults.isNotEmpty()) {
                Card(modifier = Modifier.padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        searchResults.forEach { peer ->
                            PickRow(
                                label = peer.displayName,
                                sub = "Seen before",
                                checked = selected.value.contains(peer.nodeId),
                                onClick = { viewModel.toggle(peer.nodeId, peer.displayName) },
                            )
                        }
                    }
                }
            }

            if (peers.isNotEmpty()) {
                Text(
                    "Nearby via Bluetooth",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(peers.filter { !it.isSelf }, key = { it.nodeId }) { peer ->
                        PickRow(
                            label = peer.displayName,
                            sub = "Nearby",
                            checked = selected.value.contains(peer.nodeId),
                            onClick = { viewModel.toggle(peer.nodeId, peer.displayName) },
                        )
                    }
                }
            } else {
                Text(
                    "Open the Nearby screen and start the mesh to discover people around you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Box(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val gid = viewModel.createGroup(name)
                    if (gid.isNotEmpty()) onGroupCreated(gid)
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create group with ${selected.value.size + 1} members")
            }
        }
    }
}

@Composable
private fun PickRow(label: String, sub: String, checked: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}