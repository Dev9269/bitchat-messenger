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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.online.OnlineConfig
import com.bitchat.online.OnlineService
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var url by rememberSaveable { mutableStateOf(OnlineConfig.getProjectId(ctx)) }
    var key by rememberSaveable { mutableStateOf(OnlineConfig.getApiKey(ctx)) }
    val state by OnlineService.state.collectAsStateWithLifecycle()

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
                .padding(16.dp),
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