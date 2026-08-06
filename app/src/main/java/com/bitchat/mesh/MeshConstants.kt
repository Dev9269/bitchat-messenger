package com.bitchat.mesh

import java.util.UUID

object MeshConstants {

    val DISCOVERY_UUID: UUID = UUID.fromString("0000ffaa-0000-1000-8000-00805f9b34fb")
    const val PUBLIC_CHANNEL_ID = "public"
    const val PROTOCOL_VERSION: Byte = 1
    const val NODE_ID_LENGTH = 16
    const val PEER_TIMEOUT_MS = 15_000L
    const val PEER_PRUNE_INTERVAL_MS = 5_000L
}
