package com.bitchat

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.crypto.Recovery
import com.bitchat.mesh.MeshManager
import com.bitchat.mesh.MeshService
import com.bitchat.mesh.PermissionRequirements
import com.bitchat.ui.account.AccountGate
import com.bitchat.ui.chat.ChatScreen
import com.bitchat.ui.discovery.DiscoveryScreen
import com.bitchat.ui.discovery.DiscoveryViewModel
import com.bitchat.ui.groups.GroupsScreen
import com.bitchat.ui.home.HomeScreen
import com.bitchat.ui.home.HomeViewModel
import com.bitchat.ui.lock.AppLock
import com.bitchat.ui.lock.LockDialog
import com.bitchat.ui.lock.LockScreen
import com.bitchat.ui.online.OnlineSettingsScreen
import com.bitchat.ui.theme.GhostwireTheme

sealed interface Screen {
    data object Home : Screen
    data object Nearby : Screen
    data object CreateGroup : Screen
    data object OnlineSettings : Screen
    data class Chat(val conversationId: String) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var autoRequested = false
    private var meshAutoStarted = false
    private var isLocked by mutableStateOf(true)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                MeshManager.refreshRuntimeState(applicationContext)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            MeshManager.refreshRuntimeState(applicationContext)
            maybeAutoStartMesh()
        }

        isLocked = AppLock.isEnabled(applicationContext) && !AppLock.isUnlocked()

        setContent {
            GhostwireTheme {
                AppContent()
            }
        }
    }

    @Composable
    private fun AppContent() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var showLockDialog by remember { mutableStateOf(false) }
        var showAccountGate by remember {
            mutableStateOf(
                !Recovery.introDone(applicationContext) &&
                    Recovery.getSeed(applicationContext) != null
            )
        }
        BackHandler(enabled = screen !is Screen.Home || isLocked) { screen = Screen.Home }

        if (isLocked) {
            LockScreen(onUnlocked = { isLocked = false })
            return
        }

        if (showAccountGate) {
            AccountGate(onDone = { showAccountGate = false })
            return
        }

        when (val current = screen) {
            is Screen.Home -> {
                val homeViewModel: HomeViewModel = viewModel()
                val conversations by homeViewModel.conversations.collectAsStateWithLifecycle()
                HomeScreen(
                    conversations = conversations,
                    onOpenChat = { screen = Screen.Chat(it) },
                    onOpenNearby = { screen = Screen.Nearby },
                    onCreateGroup = { screen = Screen.CreateGroup },
                    onOpenOnline = { screen = Screen.OnlineSettings },
                    onManageLock = { showLockDialog = true },
                )
            }

            is Screen.OnlineSettings -> {
                OnlineSettingsScreen(onBack = { screen = Screen.Home })
            }

            is Screen.CreateGroup -> {
                val discoveryViewModel: DiscoveryViewModel = viewModel()
                val peers by MeshManager.peers.collectAsStateWithLifecycle()
                val searchResults by discoveryViewModel.searchResults.collectAsStateWithLifecycle()
                GroupsScreen(
                    peers = peers.values.toList(),
                    searchResults = searchResults,
                    onSearchQuery = { discoveryViewModel.searchByName(it) },
                    onBack = { screen = Screen.Home },
                    onGroupCreated = { screen = Screen.Chat(it) },
                )
            }

            is Screen.Nearby -> {
                val discoveryViewModel: DiscoveryViewModel = viewModel()
                val meshState by discoveryViewModel.meshState.collectAsStateWithLifecycle()
                val peers by MeshManager.peers.collectAsStateWithLifecycle()
                val searchResults by discoveryViewModel.searchResults.collectAsStateWithLifecycle()
                val nameError by discoveryViewModel.nameError.collectAsStateWithLifecycle()
                val dmUnlocked by com.bitchat.security.AccessControl.dmUnlocked.collectAsStateWithLifecycle()
                DiscoveryScreen(
                    state = meshState,
                    peers = peers.values.toList(),
                    foundPeers = searchResults,
                    nameError = nameError,
                    personalChatUnlocked = dmUnlocked,
                    onOpenChat = { screen = Screen.Chat(it) },
                    onSearchQuery = { discoveryViewModel.searchByName(it) },
                    onSetDisplayName = { discoveryViewModel.saveDisplayName(it) },
                    onRequestPermissions = ::requestMissingPermissions,
                    onOpenBluetoothSettings = {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    onStartMesh = { MeshService.start(applicationContext) },
                    onStopMesh = { MeshService.stop(applicationContext) },
                    onBack = { screen = Screen.Home },
                )
            }

            is Screen.Chat -> {
                ChatScreen(
                    conversationId = current.conversationId,
                    onBack = { screen = Screen.Home },
                    onDeleted = { screen = Screen.Home },
                )
            }
        }

        if (showLockDialog) {
            LockDialog(onDismiss = { showLockDialog = false })
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && AppLock.isEnabled(applicationContext)) {
            AppLock.lock()
            isLocked = true
        }
    }

    override fun onResume() {
        super.onResume()
        MeshManager.refreshRuntimeState(this)
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (!autoRequested) {
            autoRequested = true
            requestMissingPermissions()
        }
    }

    override fun onPause() {
        unregisterReceiver(bluetoothStateReceiver)
        super.onPause()
    }

    private fun requestMissingPermissions() {
        val missing = PermissionRequirements.missingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            maybeAutoStartMesh()
        }
    }

    private fun maybeAutoStartMesh() {
        if (meshAutoStarted) return
        if (!PermissionRequirements.allGranted(this)) return
        if (!MeshManager.bluetoothEnabled.value) return
        meshAutoStarted = true
        MeshService.start(applicationContext)
    }
}
