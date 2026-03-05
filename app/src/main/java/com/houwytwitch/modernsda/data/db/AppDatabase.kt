package com.houwytwitch.modernsda.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.houwytwitch.modernsda.data.model.Account

@Database(
    entities = [Account::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
}
