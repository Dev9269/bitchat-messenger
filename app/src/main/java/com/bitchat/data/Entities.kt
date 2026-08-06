package com.bitchat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val STATUS_PENDING = 0
const val STATUS_SENT = 1
const val STATUS_DELIVERED = 2
const val STATUS_FAILED = 3

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val x25519PubKey: ByteArray?,
    val lastSeen: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["msgId"], unique = true),
        Index(value = ["conversationId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val msgId: String,
    val conversationId: String,
    val srcNodeId: String,
    val dstNodeId: String,
    val text: String,
    val timestamp: Long,
    val outbound: Boolean,
    val deliveryStatus: Int,
    val broadcast: Boolean,
)
