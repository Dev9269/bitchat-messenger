package com.bitchat.mesh

object AdvertisePayload {

    const val MAX_NAME_BYTES = 6

    fun encode(nodeId: String, name: String): ByteArray {
        val idBytes = nodeId.hexToBytes()
        val nameBytes = name.toByteArray(Charsets.UTF_8).take(MAX_NAME_BYTES).toByteArray()
        return ByteArray(1 + idBytes.size + nameBytes.size).also {
            it[0] = MeshConstants.PROTOCOL_VERSION
            idBytes.copyInto(it, 1)
            nameBytes.copyInto(it, 1 + idBytes.size)
        }
    }

    fun decode(payload: ByteArray): Decoded? {
        if (payload.size < 1 + MeshConstants.NODE_ID_LENGTH) return null
        if (payload[0] != MeshConstants.PROTOCOL_VERSION) return null
        val id = payload.copyOfRange(1, 1 + MeshConstants.NODE_ID_LENGTH).toHex()
        var name: String? = null
        if (payload.size > 1 + MeshConstants.NODE_ID_LENGTH) {
            val n = payload.copyOfRange(1 + MeshConstants.NODE_ID_LENGTH, payload.size)
                .toString(Charsets.UTF_8)
                .trim()
            if (n.isNotEmpty()) name = n
        }
        return Decoded(id, name)
    }

    data class Decoded(val nodeId: String, val name: String?)
}

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
