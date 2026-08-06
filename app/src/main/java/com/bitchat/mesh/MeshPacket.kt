package com.bitchat.mesh

import java.security.SecureRandom

object MeshPacket {

    const val MAGIC_0: Byte = 0x42
    const val MAGIC_1: Byte = 0x4D
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 55

    const val TYPE_DIRECT = 1
    const val TYPE_BROADCAST = 2
    const val TYPE_HANDSHAKE = 3
    const val TYPE_ACK = 4

    const val DEFAULT_TTL = 5
    const val MAX_PACKET_BYTES = 620
    const val FRAGMENT_PAYLOAD_SIZE = 120

    val BROADCAST_NODE_HEX = "00".repeat(16)

    private val random = SecureRandom()

    data class Packet(
        val type: Int,
        val msgId: ByteArray,
        val src: String,
        val dst: String,
        val ttl: Int,
        val payload: ByteArray,
    )

    fun newMsgId(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun encode(packet: Packet): ByteArray {
        val srcBytes = packet.src.hexToBytes()
        val dstBytes = packet.dst.hexToBytes()
        val out = ByteArray(HEADER_SIZE + packet.payload.size)
        var i = 0
        out[i++] = MAGIC_0
        out[i++] = MAGIC_1
        out[i++] = VERSION
        out[i++] = packet.type.toByte()
        out[i++] = packet.ttl.toByte()
        packet.msgId.copyInto(out, i)
        i += 16
        srcBytes.copyInto(out, i)
        i += 16
        dstBytes.copyInto(out, i)
        i += 16
        out[i++] = ((packet.payload.size shr 8) and 0xFF).toByte()
        out[i++] = (packet.payload.size and 0xFF).toByte()
        packet.payload.copyInto(out, i)
        return out
    }

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
        if (bytes[2] != VERSION) return null
        if (bytes.size > MAX_PACKET_BYTES) return null
        val len = ((bytes[53].toInt() and 0xFF) shl 8) or (bytes[54].toInt() and 0xFF)
        if (bytes.size != HEADER_SIZE + len) return null
        return Packet(
            type = bytes[3].toInt() and 0xFF,
            msgId = bytes.copyOfRange(5, 21),
            src = bytes.copyOfRange(21, 37).toHex(),
            dst = bytes.copyOfRange(37, 53).toHex(),
            ttl = bytes[4].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size),
        )
    }
}

object Fragmentation {

    fun split(data: ByteArray, chunkSize: Int): List<ByteArray> {
        val total = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        return (0 until total).map { idx ->
            val from = idx * chunkSize
            val to = minOf(from + chunkSize, data.size)
            ByteArray(2 + (to - from)).also { out ->
                out[0] = total.toByte()
                out[1] = idx.toByte()
                data.copyInto(out, 2, from, to)
            }
        }
    }
}
