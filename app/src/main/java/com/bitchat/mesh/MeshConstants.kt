package com.bitchat.mesh

import java.util.UUID

object MeshConstants {

    val DISCOVERY_UUID: UUID = UUID.fromString("0000ffaa-0000-1000-8000-00805f9b34fb")
    const val PUBLIC_CHANNEL_ID = "public"
    const val PROTOCOL_VERSION: Byte = 1
    const val NODE_ID_LENGTH = 16
    const val PEER_TIMEOUT_MS = 15_000L
    const val PEER_PRUNE_INTERVAL_MS = 5_000L

    /** Legacy advertising permits at most 31 bytes of advertisement data. */
    const val LEGACY_AD_MAX_BYTES = 31

    /**
     * [android.bluetooth.le.AdvertiseData.Builder] defaults this to true, which appends a 3-byte
     * TX power level AD to every advertisement. We do not use it, and while it was on the
     * discovery payload came to 33 bytes — over the 31-byte legacy cap — so advertising was
     * rejected with ADVERTISE_FAILED_DATA_TOO_LARGE and no peer could ever see this device.
     */
    const val TX_POWER_AD_BYTES = 3
    const val INCLUDE_TX_POWER_LEVEL = false
}
