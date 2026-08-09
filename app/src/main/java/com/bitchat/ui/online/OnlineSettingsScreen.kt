package com.bitchat.ui.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.mesh.MeshManager
import com.bitchat.online.OnlineConfig
import com.bitchat.online.OnlineService
import com.bitchat.security.AccessControl
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var url by rememberSaveable { mutableStateOf(OnlineConfig.getProjectId(ctx)) }
    var key by rememberSaveable { mutableStateOf(OnlineConfig.getApiKey(ctx)) }
    val state by OnlineService.state.collectAsStateWithLifecycle()
    val dmUnlocked by AccessControl.dmUnlocked.collectAsStateWithLifecycle()

    var isOwner by remember { mutableStateOf(false) }
    var settingsExist by remember { mutableStateOf(false) }
    var masterSecret by rememberSaveable { mutableStateOf("") }
    var allowlistInput by rememberSaveable { mutableStateOf("") }
    var unlockInput by rememberSaveable { mutableStateOf("") }
    var accessMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connected = state.status == OnlineService.ConnectionStatus.CONNECTED

    LaunchedEffect(connected) {
        if (connected) {
            settingsExist = OnlineService.accessSettingsExists()
            isOwner = OnlineService.isAccessOwner()
        } else {
            settingsExist = false
            isOwner = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online mode") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Online mode sends encrypted messages over the internet (like WhatsApp/Telegram) at the same time as Bluetooth. No account or login - just your username.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Firebase project ID") },
                placeholder = { Text("my-bitchat-app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Firebase Web API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    OnlineConfig.setCredentials(ctx, url, key)
                    OnlineService.connect(com.bitchat.mesh.MeshManager.displayName.value)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & connect")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (state.status) {
                                OnlineService.ConnectionStatus.CONNECTED -> Color(0xFF22C55E)
                                OnlineService.ConnectionStatus.CONNECTING -> Color(0xFFFACC15)
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                Text(
                    "Status: ${state.status.name.lowercase()} ${state.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            HorizontalDivider()

            // -------------------------------------------------------------
            // Personal chat (DM) access gate
            // -------------------------------------------------------------
            Text(
                "Personal chats",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (dmUnlocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Text(
                        "Personal chat is unlocked on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Button(
                    onClick = { AccessControl.setDmUnlocked(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lock personal chats")
                }
            } else {
                Text(
                    "Private 1-to-1 chats are hidden. Unlock them with the personal secret the owner shared, or ask the owner to add your Node ID to the allowlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = unlockInput,
                    onValueChange = { unlockInput = it },
                    label = { Text("Personal chat secret") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                accessMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Locked")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            val ok = OnlineService.verifyPersonalSecret(unlockInput.trim())
                            if (ok) {
                                accessMsg = "Personal chats unlocked."
                                unlockInput = ""
                            } else {
                                accessMsg = "Wrong secret — access stays locked."
                            }
                        }
                    },
                    enabled = unlockInput.isNotBlank() && connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Unlock personal chats")
                }
            }

            if (connected) {
                if (isOwner) {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Owner controls",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(
                                "You are the access owner. Only people with the master secret — or on the allowlist — can use personal chats.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = masterSecret,
                                onValueChange = { masterSecret = it },
                                label = { Text("Master secret (share only with people you trust)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        OnlineService.setAccessSettings(masterSecret.trim().ifBlank { null }, null)
                                        accessMsg = if (masterSecret.isBlank()) {
                                            "Removed master secret (allowlist still works)."
                                        } else {
                                            "Master secret saved."
                                        }
                                        masterSecret = ""
                                    }
                                },
                                enabled = connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Save master secret")
                            }
                            OutlinedTextField(
                                value = allowlistInput,
                                onValueChange = { allowlistInput = it },
                                label = { Text("Grant personal chat to Node ID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        OnlineService.addAllowlistNode(allowlistInput.trim())
                                        accessMsg = "Node added to allowlist."
                                        allowlistInput = ""
                                    }
                                },
                                enabled = allowlistInput.isNotBlank() && connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Add to allowlist")
                            }
                            Text(
                                "Anyone in the allowlist unlocks personal chat automatically when they connect.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                            Text(
                                "Default group",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(
                                "Every new install lands in this public group automatically. Create it once as the owner — members who join later get the key automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        MeshManager.createDefaultGroup { ok, msg ->
                                            accessMsg = msg
                                        }
                                    }
                                },
                                enabled = connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Create default group")
                            }
                        }
                    }
                } else if (!settingsExist) {
                    Text(
                        "No personal-chat access settings exist yet — the first person to create the master secret becomes the access owner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "How to get these values:\n1. Create a free project at console.firebase.google.com\n2. Project settings -> General: copy the Project ID\n3. Project settings -> General -> Your apps: Web app -> copy the Web API key\nMessages are end-to-end encrypted before they reach the server, so Firebase only stores ciphertext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { OnlineService.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect")
            }
        }
    }
}