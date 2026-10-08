package com.bitchat.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshPacketTtlTest {

    private fun packet(ttl: Int) = MeshPacket.Packet(
        type = MeshPacket.TYPE_BROADCAST,
        msgId = ByteArray(16) { 1 },
        src = "11".repeat(16),
        dst = MeshPacket.BROADCAST_NODE_HEX,
        ttl = ttl,
        payload = "hi".toByteArray(),
    )

    @Test
    fun decode_clampsAnOverlargeTtlToTheProtocolMaximum() {
        val bytes = MeshPacket.encode(packet(MeshPacket.DEFAULT_TTL))
        bytes[4] = 0xFF.toByte() // a peer can put anything in the TTL byte

        val decoded = MeshPacket.decode(bytes)
        assertNotNull(decoded)
        assertEquals(
            "an untrusted TTL must not exceed the protocol maximum",
            MeshPacket.DEFAULT_TTL,
            decoded!!.ttl
        )
    }

    @Test
    fun decode_preservesInRangeTtls() {
        assertEquals(0, MeshPacket.decode(MeshPacket.encode(packet(0)))!!.ttl)
        assertEquals(3, MeshPacket.decode(MeshPacket.encode(packet(3)))!!.ttl)
        assertEquals(5, MeshPacket.decode(MeshPacket.encode(packet(5)))!!.ttl)
    }

    @Test
    fun encode_neverEmitsAnOverlargeTtl() {
        val bytes = MeshPacket.encode(packet(255))
        assertEquals(MeshPacket.DEFAULT_TTL, bytes[4].toInt() and 0xFF)
    }
}
