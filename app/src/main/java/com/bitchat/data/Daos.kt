package com.bitchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun get(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun flow(nodeId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers")
    suspend fun all(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE displayName = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByNameExact(name: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE displayName LIKE '%' || :name || '%' COLLATE NOCASE")
    suspend fun searchByName(name: String): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @Query("UPDATE peers SET displayName = :name, lastSeen = :lastSeen WHERE nodeId = :nodeId")
    suspend fun updateName(nodeId: String, name: String, lastSeen: Long)

    @Query("UPDATE peers SET x25519PubKey = :key, lastSeen = :lastSeen WHERE nodeId = :nodeId")
    suspend fun setKey(nodeId: String, key: ByteArray, lastSeen: Long)
}

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun flowMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET deliveryStatus = :status WHERE msgId = :msgId AND outbound = 1")
    suspend fun setStatus(msgId: String, status: Int)

    @Query("SELECT * FROM messages WHERE outbound = 1 AND deliveryStatus IN (0, 1)")
    suspend fun allPending(): List<MessageEntity>

    @Query("""
        SELECT m.conversationId AS conversationId, m.text AS lastText, m.timestamp AS lastTs,
               p.displayName AS peerTitle, g.name AS groupTitle, m.broadcast AS broadcast
        FROM messages m
        LEFT JOIN peers p ON p.nodeId = m.conversationId
        LEFT JOIN groups g ON g.groupId = m.conversationId
        WHERE m.id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)
        ORDER BY m.timestamp DESC
    """)
    fun conversationRows(): Flow<List<ConversationRow>>
}

data class ConversationRow(
    val conversationId: String,
    val lastText: String,
    val lastTs: Long,
    val peerTitle: String?,
    val groupTitle: String?,
    val broadcast: Boolean,
)

@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun flowGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    suspend fun getGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun flowGroup(groupId: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY addedAt ASC")
    fun flowMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId LIMIT 1")
    suspend fun isMember(groupId: String, nodeId: String): GroupMemberEntity?

    @Query("SELECT nodeId FROM group_members WHERE groupId = :groupId")
    suspend fun memberNodeIds(groupId: String): List<String>

    @Query("UPDATE groups SET secretB64 = :secretB64 WHERE groupId = :groupId")
    suspend fun setSecret(groupId: String, secretB64: String)

    @Query("SELECT secretB64 FROM groups WHERE groupId = :groupId")
    suspend fun secret(groupId: String): String

    @Query("UPDATE group_members SET keyEnvB64 = :keyEnvB64 WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun setMemberKey(groupId: String, nodeId: String, keyEnvB64: String)
}
