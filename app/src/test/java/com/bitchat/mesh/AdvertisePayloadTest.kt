package com.bitchat.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisePayloadTest {

    private val nodeId = "00112233445566778899aabbccddeeff"

    @Test
    fun encode_maxName_is17BytesPlusName() {
        val payload = AdvertisePayload.encode(nodeId, "ABCDEF")
        assertEquals(1 + MeshConstants.NODE_ID_LENGTH + AdvertisePayload.MAX_NAME_BYTES, payload.size)
    }

    @Test
    fun encode_longName_isTruncatedToTheCap() {
        val payload = AdvertisePayload.encode(nodeId, "a very long display name")
        assertEquals(1 + MeshConstants.NODE_ID_LENGTH + AdvertisePayload.MAX_NAME_BYTES, payload.size)
    }

    @Test
    fun encode_zeroLengthName_stillCarriesHeaderAndId() {
        val payload = AdvertisePayload.encode(nodeId, "")
        assertEquals(1 + MeshConstants.NODE_ID_LENGTH, payload.size)
    }

    @Test
    fun advertisement_fitsWithinLegacy31ByteCap() {
        val payload = AdvertisePayload.encode(nodeId, "ABCDEF")
        val bytes = AdvertisePayload.advertisementBytes(
            payload.size,
            MeshConstants.INCLUDE_TX_POWER_LEVEL
        )
        assertTrue(
            "discovery advertisement is $bytes bytes but the legacy cap is " +
                "${MeshConstants.LEGACY_AD_MAX_BYTES}; peers cannot discover this device. " +
                "Shrink the payload or stop including the TX power level AD.",
            bytes <= MeshConstants.LEGACY_AD_MAX_BYTES
        )
    }
}
