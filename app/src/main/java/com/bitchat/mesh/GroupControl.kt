package com.bitchat.mesh

import com.bitchat.crypto.CryptoEngine
import org.json.JSONObject

/**
 * Group control messages (create/info, delete, kick) are signed with the sender's ed25519 key
 * so a relay cannot rewrite the header's src to the creator's node id and have the command
 * accepted. The signature is also bound to that node id (see CryptoEngine.verifyBroadcast),
 * so only the key's true holder can act as a given sender.
 */
object GroupControl {

    fun sign(senderNode: String, json: JSONObject): ByteArray =
        CryptoEngine.signBroadcast(senderNode, json.toString().toByteArray(Charsets.UTF_8))

    /** Parses the payload iff it was signed by the key belonging to [senderNode]; else null. */
    fun parse(senderNode: String, payload: ByteArray): JSONObject? {
        val data = CryptoEngine.verifyBroadcast(senderNode, payload) ?: return null
        return try {
            JSONObject(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
