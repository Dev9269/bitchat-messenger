package com.bitchat

import android.app.Application
import com.bitchat.crypto.CryptoEngine
import com.bitchat.crypto.Recovery
import com.bitchat.data.DataGraph
import com.bitchat.mesh.MeshManager
import com.bitchat.online.OnlineService
import com.bitchat.security.AccessControl
import com.bitchat.startup.StartupGate

class GhostwireApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataGraph.init(this)
        Recovery.init(this)
        try {
            CryptoEngine.init(this)
        } catch (_: CryptoEngine.IdentityUnavailableException) {
            StartupGate.block(
                "Ghostwire could not unlock your account keys. Your account is still on " +
                    "this device. Reinstall the app and restore it with your recovery key."
            )
        }
        MeshManager.init(this)
        OnlineService.init(this)
        AccessControl.init(this)
    }
}
