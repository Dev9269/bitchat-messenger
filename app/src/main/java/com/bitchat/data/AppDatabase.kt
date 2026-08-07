package com.bitchat.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PeerEntity::class, MessageEntity::class, GroupEntity::class, GroupMemberEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
}
