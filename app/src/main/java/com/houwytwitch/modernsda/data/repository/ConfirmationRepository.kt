package com.houwytwitch.modernsda.data.repository

import android.util.Base64
import com.houwytwitch.modernsda.data.db.AccountDao
import com.houwytwitch.modernsda.data.model.Account
import com.houwytwitch.modernsda.data.model.Confirmation
import com.houwytwitch.modernsda.data.model.ConfirmationResult
import com.houwytwitch.modernsda.domain.steam.SessionExpiredException
import com.houwytwitch.modernsda.domain.steam.SteamConfirmations
import com.houwytwitch.modernsda.domain.steam.SteamLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfirmationRepository @Inject constructor(
    private val steamConfirmations: SteamConfirmations,
    private val steamLogin: SteamLogin,
    private val accountDao: AccountDao,
) {
    suspend fun fetchConfirmations(account: Account): Result<List<Confirmation>> {
        return runCatching {
            val resolved = ensureValidSession(account)

            val result = steamConfirmations.fetchConfirmations(
                steamId = resolved.steamId,
                identitySecret = resolved.identitySecret,
                deviceId = resolved.deviceId,
                sessionId = resolved.sessionId,
                steamLoginSecure = resolved.steamLoginSecure,
            )

            // If session expired mid-flight, re-auth and retry once
            if (result.exceptionOrNull() is SessionExpiredException) {
                val refreshed = reAuthenticate(resolved)
                return@runCatching steamConfirmations.fetchConfirmations(
                    steamId = refreshed.steamId,
                    identitySecret = refreshed.identitySecret,
                    deviceId = refreshed.deviceId,
                    sessionId = refreshed.sessionId,
                    steamLoginSecure = refreshed.steamLoginSecure,
                ).getOrThrow()
            }

            result.getOrThrow()
        }
    }

    suspend fun acceptConfirmation(account: Account, confirmation: Confirmation): ConfirmationResult {
        return try {
            val resolved = ensureValidSession(account)
            steamConfirmations.respondToConfirmation(
                steamId = resolved.steamId,
                identitySecret = resolved.identitySecret,
                deviceId = resolved.deviceId,
                sessionId = resolved.sessionId,
                steamLoginSecure = resolved.steamLoginSecure,
                confirmation = confirmation,
                accept = true,
            )
        } catch (e: Exception) {
            ConfirmationResult.Error(e.message ?: "Failed to accept confirmation")
        }
    }

    suspend fun declineConfirmation(account: Account, confirmation: Confirmation): ConfirmationResult {
        return try {
            val resolved = ensureValidSession(account)
            steamConfirmations.respondToConfirmation(
                steamId = resolved.steamId,
                identitySecret = resolved.identitySecret,
                deviceId = resolved.deviceId,
                sessionId = resolved.sessionId,
                steamLoginSecure = resolved.steamLoginSecure,
                confirmation = confirmation,
                accept = false,
            )
        } catch (e: Exception) {
            ConfirmationResult.Error(e.message ?: "Failed to decline confirmation")
        }
    }

    suspend fun acceptAllConfirmations(account: Account, confirmations: List<Confirmation>): ConfirmationResult {
        return try {
            val resolved = ensureValidSession(account)
            steamConfirmations.acceptAllConfirmations(
                steamId = resolved.steamId,
                identitySecret = resolved.identitySecret,
                deviceId = resolved.deviceId,
                sessionId = resolved.sessionId,
                steamLoginSecure = resolved.steamLoginSecure,
                confirmations = confirmations,
            )
        } catch (e: Exception) {
            ConfirmationResult.Error(e.message ?: "Failed to accept all confirmations")
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    /**
     * Returns an account with valid session tokens, or throws with a descriptive message.
     *
     * Priority:
     * 1. Existing steamLoginSecure with non-expired JWT → use as-is
     * 2. Valid refreshToken → exchange for a new access_token
     * 3. Password set → full login via IAuthenticationService
     * 4. None of the above → throw explaining what to do
     */
    private suspend fun ensureValidSession(account: Account): Account {
        if (account.steamLoginSecure.isNotBlank() && !isAccessTokenExpired(account.steamLoginSecure)) {
            return account
        }

        if (account.refreshToken.isNotBlank()) {
            return tryRefreshToken(account)
        }

        if (account.password.isNotBlank()) {
            return performFullLogin(account)
        }

        throw Exception("No session available. Edit the account to add a password for auto-login.")
    }

    private suspend fun reAuthenticate(account: Account): Account {
        if (account.refreshToken.isNotBlank()) {
            return tryRefreshToken(account)
        }
        if (account.password.isNotBlank()) {
            return performFullLogin(account)
        }
        throw Exception("Session expired and could not re-authenticate.")
    }

    private suspend fun tryRefreshToken(account: Account): Account = withContext(Dispatchers.IO) {
        try {
            val newSteamLoginSecure = steamLogin.refreshAccessToken(account.refreshToken, account.steamId)
            val sessionId = account.sessionId.ifBlank { generateSessionId() }
            accountDao.updateSessionTokens(account.steamId, sessionId, newSteamLoginSecure, account.refreshToken)
            accountDao.getAccountById(account.steamId) ?: account.copy(
                sessionId = sessionId,
                steamLoginSecure = newSteamLoginSecure,
            )
        } catch (e: Exception) {
            // Refresh token invalid/expired – try full login if password is available
            if (account.password.isNotBlank()) {
                performFullLogin(account)
            } else {
                throw Exception("Session expired. Edit the account to add a password for auto-login.")
            }
        }
    }

    private suspend fun performFullLogin(account: Account): Account = withContext(Dispatchers.IO) {
        // SteamLogin.login() throws with a descriptive message on any failure
        val result = steamLogin.login(
            accountName = account.accountName,
            password = account.password,
            sharedSecret = account.sharedSecret,
            steamId = account.steamId,
        )
        accountDao.updateSessionTokens(
            steamId = account.steamId,
            sessionId = result.sessionId,
            steamLoginSecure = result.steamLoginSecure,
            refreshToken = result.refreshToken,
        )
        accountDao.getAccountById(account.steamId) ?: account.copy(
            sessionId = result.sessionId,
            steamLoginSecure = result.steamLoginSecure,
            refreshToken = result.refreshToken,
        )
    }

    // ── JWT helpers ───────────────────────────────────────────────────────────

    private fun isAccessTokenExpired(steamLoginSecure: String): Boolean {
        val jwt = steamLoginSecure.substringAfter("||", missingDelimiterValue = "")
        if (jwt.isBlank()) return true
        return isJwtExpired(jwt)
    }

    private fun isJwtExpired(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return true
            val payload = Base64.decode(
                parts[1].padEnd((parts[1].length + 3) / 4 * 4, '='),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            val json = JSONObject(String(payload, Charsets.UTF_8))
            val exp = json.optLong("exp", 0L)
            val now = System.currentTimeMillis() / 1000
            exp == 0L || now >= exp - 300   // expire 5 min early to avoid edge cases
        } catch (e: Exception) {
            true
        }
    }

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")
}

private val Random = kotlin.random.Random
