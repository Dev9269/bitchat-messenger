package com.bitchat.mesh

import android.content.Context
import com.bitchat.crypto.Recovery
import java.security.SecureRandom

object NodeIdentity {

    private const val PREFS_NAME = "bitchat_identity"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_DISPLAY_NAME = "display_name"

    fun getNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_NODE_ID, null)?.let { return it }
        val seed = Recovery.getSeed(context)
        val id = if (seed != null) {
            Recovery.deriveNodeId(seed)
        } else {
            val bytes = ByteArray(MeshConstants.NODE_ID_LENGTH)
            SecureRandom().nextBytes(bytes)
            bytes.toHex()
        }
        prefs.edit().putString(KEY_NODE_ID, id).apply()
        return id
    }

    /** Drop the cached id so [getNodeId] re-derives it from the recovery seed. */
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
