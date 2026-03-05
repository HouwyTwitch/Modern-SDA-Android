package com.houwytwitch.modernsda.data.repository

import com.google.gson.Gson
import com.houwytwitch.modernsda.data.db.AccountDao
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.data.model.MafileData
import com.houwytwitch.modernsda.domain.steam.AvatarFetcher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class AddAccountResult {
    data object Success : AddAccountResult()
    data class Error(val message: String) : AddAccountResult()
}

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val avatarFetcher: AvatarFetcher,
    private val gson: Gson,
) {
    val accounts: Flow<List<Account>> = accountDao.getAllAccounts()

    suspend fun addAccountFromMafile(mafileJson: String, password: String = ""): AddAccountResult {
        return try {
            val mafile = gson.fromJson(mafileJson, MafileData::class.java)
                ?: return AddAccountResult.Error("Invalid .mafile format")

            // Validate required fields
            if (mafile.sharedSecret.isBlank()) {
                return AddAccountResult.Error("Missing shared_secret in .mafile")
            }
            if (mafile.identitySecret.isBlank()) {
                return AddAccountResult.Error("Missing identity_secret in .mafile")
            }
            if (mafile.accountName.isBlank()) {
                return AddAccountResult.Error("Missing account_name in .mafile")
            }
            if (mafile.deviceId.isBlank()) {
                return AddAccountResult.Error("Missing device_id in .mafile")
            }

            val steamId = mafile.session?.steamId ?: 0L
            if (steamId == 0L) {
                return AddAccountResult.Error("Missing Steam ID in .mafile Session")
            }

            // Check for duplicates
            if (accountDao.getAccountById(steamId) != null) {
                return AddAccountResult.Error("Account '${mafile.accountName}' is already added")
            }

            val account = Account(
                steamId = steamId,
                accountName = mafile.accountName,
                sharedSecret = mafile.sharedSecret,
                identitySecret = mafile.identitySecret,
                deviceId = mafile.deviceId,
                password = password,
                sessionId = mafile.session?.sessionId ?: "",
                steamLoginSecure = mafile.session?.steamLoginSecure ?: "",
                webCookie = mafile.session?.webCookie ?: "",
                oAuthToken = mafile.session?.oAuthToken ?: "",
            )

            accountDao.insertAccount(account)

            // Fetch avatar in background
            val avatarUrl = avatarFetcher.fetchAvatarUrl(steamId)
            if (!avatarUrl.isNullOrBlank()) {
                accountDao.updateAvatar(steamId, avatarUrl)
            }

            AddAccountResult.Success
        } catch (e: com.google.gson.JsonSyntaxException) {
            AddAccountResult.Error("Invalid JSON format in .mafile")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            AddAccountResult.Error("Account already exists")
        } catch (e: Exception) {
            AddAccountResult.Error(e.message ?: "Failed to add account")
        }
    }

    suspend fun removeAccount(account: Account) {
        accountDao.deleteAccount(account)
    }

    suspend fun updateAccount(account: Account) {
        accountDao.updateAccount(account)
    }

    suspend fun refreshAvatar(steamId: Long) {
        val avatarUrl = avatarFetcher.fetchAvatarUrl(steamId)
        if (!avatarUrl.isNullOrBlank()) {
            accountDao.updateAvatar(steamId, avatarUrl)
        }
    }

    suspend fun getAccountById(steamId: Long): Account? = accountDao.getAccountById(steamId)
}
