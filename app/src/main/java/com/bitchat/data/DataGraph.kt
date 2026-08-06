package com.bitchat.data

import android.content.Context
import androidx.room.Room

object DataGraph {

    lateinit var database: AppDatabase
        private set

    val repository: Repository by lazy { Repository(database) }

    fun init(context: Context) {
        database = Room.databaseBuilder(context, AppDatabase::class.java, "bitchat.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}
