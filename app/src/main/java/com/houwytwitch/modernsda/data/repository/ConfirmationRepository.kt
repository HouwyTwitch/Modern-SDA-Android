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
        val resolved = ensureValidSession(account)
            ?: return Result.failure(Exception("Could not authenticate. Add a password to your account to enable auto-login."))

        val result = steamConfirmations.fetchConfirmations(
            steamId = resolved.steamId,
            identitySecret = resolved.identitySecret,
            deviceId = resolved.deviceId,
            sessionId = resolved.sessionId,
            steamLoginSecure = resolved.steamLoginSecure,
        )

        // If session expired mid-flight, try once more after re-auth
        if (result.exceptionOrNull() is SessionExpiredException) {
            val refreshed = reAuthenticate(resolved) ?: return Result.failure(
                Exception("Session expired and re-authentication failed.")
            )
            return steamConfirmations.fetchConfirmations(
                steamId = refreshed.steamId,
                identitySecret = refreshed.identitySecret,
                deviceId = refreshed.deviceId,
                sessionId = refreshed.sessionId,
                steamLoginSecure = refreshed.steamLoginSecure,
            )
        }

        return result
    }

    suspend fun acceptConfirmation(
        account: Account,
        confirmation: Confirmation,
    ): ConfirmationResult {
        val resolved = ensureValidSession(account) ?: return ConfirmationResult.Error(
            "Could not authenticate. Add a password to your account to enable auto-login."
        )
        return steamConfirmations.respondToConfirmation(
            steamId = resolved.steamId,
            identitySecret = resolved.identitySecret,
            deviceId = resolved.deviceId,
            sessionId = resolved.sessionId,
            steamLoginSecure = resolved.steamLoginSecure,
            confirmation = confirmation,
            accept = true,
        )
    }

    suspend fun declineConfirmation(
        account: Account,
        confirmation: Confirmation,
    ): ConfirmationResult {
        val resolved = ensureValidSession(account) ?: return ConfirmationResult.Error(
            "Could not authenticate. Add a password to your account to enable auto-login."
        )
        return steamConfirmations.respondToConfirmation(
            steamId = resolved.steamId,
            identitySecret = resolved.identitySecret,
            deviceId = resolved.deviceId,
            sessionId = resolved.sessionId,
            steamLoginSecure = resolved.steamLoginSecure,
            confirmation = confirmation,
            accept = false,
        )
    }

    suspend fun acceptAllConfirmations(
        account: Account,
        confirmations: List<Confirmation>,
    ): ConfirmationResult {
        val resolved = ensureValidSession(account) ?: return ConfirmationResult.Error(
            "Could not authenticate. Add a password to your account to enable auto-login."
        )
        return steamConfirmations.acceptAllConfirmations(
            steamId = resolved.steamId,
            identitySecret = resolved.identitySecret,
            deviceId = resolved.deviceId,
            sessionId = resolved.sessionId,
            steamLoginSecure = resolved.steamLoginSecure,
            confirmations = confirmations,
        )
    }

    // ── Session management ────────────────────────────────────────────────────

    /**
     * Returns a valid account (possibly with refreshed tokens), or null if auth is impossible.
     *
     * Priority:
     * 1. Existing steamLoginSecure with a non-expired access token → use as-is
     * 2. Valid refreshToken → exchange for a new access token (fast path)
     * 3. Password available → full login via IAuthenticationService
     */
    private suspend fun ensureValidSession(account: Account): Account? {
        // 1. Check existing session
        if (account.steamLoginSecure.isNotBlank() && !isAccessTokenExpired(account.steamLoginSecure)) {
            return account
        }

        // 2. Try token refresh
        if (account.refreshToken.isNotBlank() && !isJwtExpired(account.refreshToken)) {
            return tryRefreshToken(account)
        }

        // 3. Full login
        if (account.password.isNotBlank()) {
            return tryFullLogin(account)
        }

        return null
    }

    private suspend fun reAuthenticate(account: Account): Account? {
        if (account.refreshToken.isNotBlank() && !isJwtExpired(account.refreshToken)) {
            return tryRefreshToken(account)
        }
        if (account.password.isNotBlank()) {
            return tryFullLogin(account)
        }
        return null
    }

    private suspend fun tryRefreshToken(account: Account): Account? = withContext(Dispatchers.IO) {
        return@withContext try {
            val newSteamLoginSecure = steamLogin.refreshAccessToken(account.refreshToken, account.steamId)
            accountDao.updateSessionTokens(
                steamId = account.steamId,
                sessionId = account.sessionId.ifBlank { generateSessionId() },
                steamLoginSecure = newSteamLoginSecure,
                refreshToken = account.refreshToken,
            )
            accountDao.getAccountById(account.steamId)
        } catch (e: Exception) {
            // Refresh token invalid/expired → fall through to full login
            if (account.password.isNotBlank()) tryFullLogin(account) else null
        }
    }

    private suspend fun tryFullLogin(account: Account): Account? = withContext(Dispatchers.IO) {
        return@withContext try {
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
            accountDao.getAccountById(account.steamId)
        } catch (e: Exception) {
            null
        }
    }

    // ── JWT helpers ───────────────────────────────────────────────────────────

    /**
     * Checks whether the JWT embedded in a steamLoginSecure value is expired.
     * steamLoginSecure format: "<steamid>||<jwt>"
     */
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
            // Treat token as expired 5 minutes early to avoid edge cases
            exp == 0L || now >= exp - 300
        } catch (e: Exception) {
            true
        }
    }

    private fun generateSessionId(): String =
        (1..24).map { (0..15).random().toString(16) }.joinToString("")
}
