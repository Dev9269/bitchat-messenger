package com.bitchat.mesh

import android.content.Context
import com.bitchat.crypto.CryptoEngine

object NodeIdentity {

    private const val PREFS_NAME = "bitchat_identity"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    /**
     * The node id is derived from the ed25519 public key (see CryptoEngine.nodeId), so it is
     * recomputed each time rather than trusted from storage. A stale cached id from an older
     * scheme is overwritten.
     */
    fun getNodeId(context: Context): String {
        val id = CryptoEngine.nodeId()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NODE_ID, null) != id) {
            prefs.edit().putString(KEY_NODE_ID, id).apply()
        }
        return id
    }

    /** Drop the cached id; [getNodeId] recomputes it from the current keys. */
    fun clearNodeId(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NODE_ID)
            .apply()
    }

    fun getDisplayName(context: Context, nodeId: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISPLAY_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: displayName(nodeId)
    }

    fun setDisplayName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, name.trim().take(AdvertisePayload.MAX_NAME_BYTES))
            .apply()
    }

    fun displayName(nodeId: String): String = "Node-" + nodeId.take(4).uppercase()
}

fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun ByteArray.hex(): String = toHex()
