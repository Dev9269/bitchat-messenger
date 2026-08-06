package com.bitchat.data

import android.content.Context
import androidx.room.Room
import com.bitchat.mesh.MeshConstants
import com.bitchat.mesh.MeshManager
import com.bitchat.mesh.NodeIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class Conversation(
    val conversationId: String,
    val title: String,
    val lastText: String,
    val lastTs: Long,
    val isBroadcast: Boolean,
    val online: Boolean,
)

data class ChatMessage(
    val id: Long,
    val msgId: String,
    val srcNodeId: String,
    val text: String,
    val timestamp: Long,
    val outbound: Boolean,
    val deliveryStatus: Int,
    val broadcast: Boolean,
)

fun MessageEntity.toChatMessage() = ChatMessage(
    id = id,
    msgId = msgId,
    srcNodeId = srcNodeId,
    text = text,
    timestamp = timestamp,
    outbound = outbound,
    deliveryStatus = deliveryStatus,
    broadcast = broadcast,
)

class Repository(private val db: AppDatabase) {

    private val peerDao = db.peerDao()
    private val messageDao = db.messageDao()

    fun conversations(): Flow<List<Conversation>> = combine(
        messageDao.conversationRows(),
        MeshManager.peers,
    ) { rows, livePeers ->
        rows.map { row ->
            Conversation(
                conversationId = row.conversationId,
                title = row.title ?: "Public channel",
                lastText = row.lastText,
                lastTs = row.lastTs,
                isBroadcast = row.conversationId == MeshConstants.PUBLIC_CHANNEL_ID,
                online = row.conversationId != MeshConstants.PUBLIC_CHANNEL_ID &&
                    livePeers.containsKey(row.conversationId),
            )
        }
    }

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.flowMessages(conversationId).map { list ->
            list.map { it.toChatMessage() }
        }

    suspend fun peer(nodeId: String): PeerEntity? = peerDao.get(nodeId)

    fun peerFlow(nodeId: String): Flow<PeerEntity?> = peerDao.flow(nodeId)

    suspend fun hasPeer(nodeId: String): Boolean = peerDao.get(nodeId) != null

    suspend fun searchPeersByName(name: String): List<PeerEntity> = peerDao.searchByName(name)

    suspend fun upsertPeerFromScan(nodeId: String, name: String) {
        val ts = System.currentTimeMillis()
        val existing = peerDao.get(nodeId)
        if (existing == null) {
            peerDao.insert(PeerEntity(nodeId, name, null, ts, ts))
        } else if (existing.displayName != name) {
            peerDao.updateName(nodeId, name, ts)
        }
    }

    suspend fun setPeerKey(nodeId: String, key: ByteArray) {
        val ts = System.currentTimeMillis()
        val existing = peerDao.get(nodeId)
        if (existing == null) {
            peerDao.insert(PeerEntity(nodeId, NodeIdentity.displayName(nodeId), key, ts, ts))
        } else {
            peerDao.setKey(nodeId, key, ts)
        }
    }

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insert(message)
    }

    suspend fun setStatus(msgId: String, status: Int) {
        messageDao.setStatus(msgId, status)
    }

    suspend fun allPending(): List<MessageEntity> = messageDao.allPending()
}
