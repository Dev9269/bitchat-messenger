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
        SELECT m.conversationId AS conversationId, m.text AS lastText, m.timestamp AS lastTs, p.displayName AS title
        FROM messages m
        LEFT JOIN peers p ON p.nodeId = m.conversationId
        WHERE m.id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)
        ORDER BY m.timestamp DESC
    """)
    fun conversationRows(): Flow<List<ConversationRow>>
}

data class ConversationRow(
    val conversationId: String,
    val lastText: String,
    val lastTs: Long,
    val title: String?,
)
