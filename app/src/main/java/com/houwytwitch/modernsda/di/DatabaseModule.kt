package com.houwytwitch.modernsda.di

import android.content.Context
import androidx.room.Room
import com.houwytwitch.modernsda.data.db.AccountDao
import com.houwytwitch.modernsda.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "modernsda.db",
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

    @Provides
    fun provideAccountDao(database: AppDatabase): AccountDao = database.accountDao()
}
