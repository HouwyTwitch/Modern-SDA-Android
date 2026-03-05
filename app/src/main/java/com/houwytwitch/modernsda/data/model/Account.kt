package com.houwytwitch.modernsda.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted account data stored in Room database.
 * Sensitive secrets are stored encrypted via EncryptedSharedPreferences
 * or as-is (user responsibility for device security).
 */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey
    val steamId: Long,
    val accountName: String,
    val sharedSecret: String,
    val identitySecret: String,
    val deviceId: String,
    val password: String = "",
    val sessionId: String = "",
    val steamLoginSecure: String = "",
    val webCookie: String = "",
    val oAuthToken: String = "",
    val avatarUrl: String = "",
    val proxyUrl: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Domain model combining account with live TOTP state.
 */
data class AccountWithCode(
    val account: Account,
    val totpCode: String = "",
    val timeRemaining: Int = 30,
)
