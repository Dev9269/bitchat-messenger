package com.bitchat

import android.app.Application
import com.bitchat.crypto.CryptoEngine
import com.bitchat.data.DataGraph
import com.bitchat.mesh.MeshManager

class BitchatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataGraph.init(this)
        CryptoEngine.init(this)
        MeshManager.init(this)
    }
}
