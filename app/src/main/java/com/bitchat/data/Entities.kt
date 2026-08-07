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

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val createdAt: Long,
    val createdByNodeId: String,
    val secretB64: String = "",
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "nodeId"],
)
data class GroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val displayName: String,
    val addedAt: Long,
    val keyEnvB64: String = "",
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
    val isGroup: Boolean = false,
)
