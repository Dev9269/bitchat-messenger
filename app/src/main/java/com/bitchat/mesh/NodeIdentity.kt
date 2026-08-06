package com.bitchat.mesh

import android.content.Context
import java.security.SecureRandom

object NodeIdentity {

    private const val PREFS_NAME = "bitchat_identity"
    private const val KEY_NODE_ID = "node_id"

    fun getNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_NODE_ID, null)?.let { return it }
        val bytes = ByteArray(MeshConstants.NODE_ID_LENGTH)
        SecureRandom().nextBytes(bytes)
        val id = bytes.toHex()
        prefs.edit().putString(KEY_NODE_ID, id).apply()
        return id
    }

    fun displayName(nodeId: String): String = "Node-" + nodeId.take(4).uppercase()
}

fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun ByteArray.hex(): String = toHex()
