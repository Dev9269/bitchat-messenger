package com.bitchat.mesh

data class Peer(
    val address: String,
    val nodeId: String,
    val displayName: String,
    val rssi: Int,
    val lastSeen: Long,
    val isSelf: Boolean = false,
) {
    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastSeen <= MeshConstants.PEER_TIMEOUT_MS
}
