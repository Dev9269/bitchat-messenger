package com.bitchat.mesh

object AdvertisePayload {

    fun encode(nodeId: String): ByteArray {
        val idBytes = nodeId.hexToBytes()
        return ByteArray(1 + idBytes.size).also {
            it[0] = MeshConstants.PROTOCOL_VERSION
            idBytes.copyInto(it, 1)
        }
    }

    fun decode(payload: ByteArray): String? {
        if (payload.size < 1 + MeshConstants.NODE_ID_LENGTH) return null
        if (payload[0] != MeshConstants.PROTOCOL_VERSION) return null
        return payload.copyOfRange(1, 1 + MeshConstants.NODE_ID_LENGTH).toHex()
    }
}

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
