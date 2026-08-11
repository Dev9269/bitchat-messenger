package com.bitchat.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.crypto.Recovery
import com.bitchat.mesh.MeshManager
import com.bitchat.mesh.MeshService

/**
 * Shown once on first launch (and after a restore): hands the user their
 * recovery key (never re-shown) or lets them restore an identity from a key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountGate(onDone: () -> Unit) {
    val context = LocalContext.current
    val seed = remember { Recovery.getSeed(context) }

    // Legacy 0.3.x installs have no seed: nothing to show, gate auto-closes.
    if (seed == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val key = remember { Recovery.withChecksum(Recovery.encode(seed)) }
    var tab by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    var restoreInput by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Account Recovery Key",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "This key restores your identity (mesh address, encryption keys and history) " +
                "on this or any other phone. Save it in a password manager or on paper — " +
                "it is shown exactly once and never again. Anyone with the key owns the identity."
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("I'm new") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Restore") })
        }

        when (tab) {
            0 -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    SelectionContainer {
                        Text(
                            text = Recovery.format(key),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 20.sp,
                                letterSpacing = 1.sp
                            ),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                    Text("Copy recovery key")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text("I saved my recovery key")
                }
                Button(
                    enabled = saved,
                    onClick = {
                        Recovery.confirmIntro(context)
                        onDone()
                    }
                ) {
                    Text("Continue")
                }
            }

            1 -> {
                OutlinedTextField(
                    value = restoreInput,
                    onValueChange = {
                        restoreInput = it
                        restoreError = false
                    },
                    label = { Text("Recovery key") },
                    supportingText = { Text("30 characters, dashes and spaces optional") },
                    isError = restoreError,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                if (restoreError) {
                    Text(
                        "That key is invalid. Check for typos (no 0, O, 1 or I in the alphabet).",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    enabled = restoreInput.isNotBlank(),
                    onClick = {
                        val ok = Recovery.restoreFromKey(context, restoreInput)
                        if (!ok) {
                            restoreError = true
                        } else {
                            MeshService.stop(context)
                            MeshManager.refreshIdentity(context)
                            onDone()
                        }
                    }
                ) {
                    Text("Restore identity")
                }
                Text(
                    "Restoring brings back your mesh address, encryption keys and local " +
                        "history. Online usernames are bound to the original install, so " +
                        "pick a fresh username when going online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}