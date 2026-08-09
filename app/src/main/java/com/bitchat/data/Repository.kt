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
    val isGroup: Boolean,
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
    val isGroup: Boolean,
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
    isGroup = isGroup,
)

class Repository(private val db: AppDatabase) {

    private val peerDao = db.peerDao()
    private val messageDao = db.messageDao()
    private val groupDao = db.groupDao()

    fun conversations(): Flow<List<Conversation>> = combine(
        messageDao.conversationRows(),
        MeshManager.peers,
    ) { rows, livePeers ->
        rows.map { row ->
            val isGroup = row.groupTitle != null
            Conversation(
                conversationId = row.conversationId,
                title = row.groupTitle ?: row.peerTitle ?: "Public channel",
                lastText = row.lastText,
                lastTs = row.lastTs,
                isBroadcast = row.broadcast,
                isGroup = isGroup,
                online = !isGroup && !row.broadcast && livePeers.containsKey(row.conversationId),
            )
        }
    }

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.flowMessages(conversationId).map { list ->
            list.map { it.toChatMessage() }
        }

    fun groups(): Flow<List<GroupEntity>> = groupDao.flowGroups()

    suspend fun allGroups(): List<GroupEntity> = groupDao.getGroups()

    fun groupFlow(groupId: String): Flow<GroupEntity?> = groupDao.flowGroup(groupId)

    fun groupMembers(groupId: String): Flow<List<GroupMemberEntity>> = groupDao.flowMembers(groupId)

    suspend fun isGroupMember(groupId: String, nodeId: String): Boolean =
        groupDao.isMember(groupId, nodeId) != null

    suspend fun allGroupMemberIds(groupId: String): List<String> =
        groupDao.memberNodeIds(groupId)

    suspend fun group(groupId: String): GroupEntity? = groupDao.getGroup(groupId)

    suspend fun createGroup(groupId: String, name: String, createdBy: String) {
        groupDao.insertGroup(
            GroupEntity(
                groupId = groupId,
                name = name,
                createdAt = System.currentTimeMillis(),
                createdByNodeId = createdBy,
            )
        )
    }

    suspend fun upsertGroup(group: GroupEntity) {
        groupDao.insertGroup(group)
    }

    suspend fun addGroupMember(groupId: String, nodeId: String, displayName: String) {
        groupDao.insertMember(
            GroupMemberEntity(
                groupId = groupId,
                nodeId = nodeId,
                displayName = displayName,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun addGroupMembers(groupId: String, members: List<Pair<String, String>>) {
        val ts = System.currentTimeMillis()
        groupDao.insertMembers(
            members.map { (nodeId, name) ->
                GroupMemberEntity(groupId, nodeId, name, ts)
            }
        )
    }

    suspend fun setGroupSecret(groupId: String, secretB64: String) {
        groupDao.setSecret(groupId, secretB64)
    }

    suspend fun groupSecret(groupId: String): String? = groupDao.secret(groupId).ifEmpty { null }

    suspend fun setGroupMemberKey(groupId: String, nodeId: String, keyEnvB64: String) {
        groupDao.setMemberKey(groupId, nodeId, keyEnvB64)
    }

    fun memberNamesFlow(groupId: String): Flow<List<GroupMemberEntity>> = groupDao.flowMembers(groupId)

    suspend fun peer(nodeId: String): PeerEntity? = peerDao.get(nodeId)

    fun peerFlow(nodeId: String): Flow<PeerEntity?> = peerDao.flow(nodeId)

    suspend fun hasPeer(nodeId: String): Boolean = peerDao.get(nodeId) != null

    suspend fun searchPeersByName(name: String): List<PeerEntity> = peerDao.searchByName(name)

    suspend fun findPeerByNameExact(name: String): PeerEntity? = peerDao.findByNameExact(name)

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

    suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteConversation(conversationId)
    }

    suspend fun deleteMessage(msgId: String) {
        messageDao.deleteMessage(msgId)
    }

    suspend fun updateMessageText(msgId: String, text: String) {
        messageDao.updateText(msgId, text)
    }

    suspend fun deleteGroup(groupId: String) {
        groupDao.deleteGroup(groupId)
        groupDao.deleteGroupMembers(groupId)
        messageDao.deleteConversation(groupId)
    }

    suspend fun removeGroupMember(groupId: String, nodeId: String) {
        groupDao.deleteMember(groupId, nodeId)
    }
}
