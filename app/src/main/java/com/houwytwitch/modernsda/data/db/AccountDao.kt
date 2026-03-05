package com.houwytwitch.modernsda.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.houwytwitch.modernsda.data.model.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY addedAt ASC")
    fun getAllAccounts(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE steamId = :steamId")
    suspend fun getAccountById(steamId: Long): Account?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: Account)

    @Update
    suspend fun updateAccount(account: Account)

    @Delete
    suspend fun deleteAccount(account: Account)

    @Query("UPDATE accounts SET avatarUrl = :url WHERE steamId = :steamId")
    suspend fun updateAvatar(steamId: Long, url: String)

    @Query("UPDATE accounts SET sessionId = :sessionId, steamLoginSecure = :steamLoginSecure, webCookie = :webCookie, oAuthToken = :oAuthToken WHERE steamId = :steamId")
    suspend fun updateSession(steamId: Long, sessionId: String, steamLoginSecure: String, webCookie: String, oAuthToken: String)

    @Query("UPDATE accounts SET sessionId = :sessionId, steamLoginSecure = :steamLoginSecure, refreshToken = :refreshToken WHERE steamId = :steamId")
    suspend fun updateSessionTokens(steamId: Long, sessionId: String, steamLoginSecure: String, refreshToken: String)
}
