package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Authenticates via the Steam community website login endpoint (dologin).
 * This is the approach used by steampy and compatible SDA tools.
 *
 * Flow:
 *   1. GET getrsakey → RSA public key + timestamp
 *   2. RSA-encrypt password, generate TOTP code
 *   3. POST dologin → transfer_parameters.token_secure (JWT access token)
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val COMMUNITY = "https://steamcommunity.com"
    }

    data class LoginResult(
        val sessionId: String,
        val steamLoginSecure: String,
        val refreshToken: String,
        val accessToken: String,
    )

    fun login(
        accountName: String,
        password: String,
        sharedSecret: String,
        steamId: Long,
    ): LoginResult {
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName)
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)
        val totpCode = SteamTotp.generateCode(sharedSecret)
        val sessionId = generateSessionId()

        val (tokenSecure, serverSteamId) = doLogin(
            accountName = accountName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaTimestamp,
            totpCode = totpCode,
            sessionId = sessionId,
            steamId = steamId,
        )

        val actualSteamId = serverSteamId.takeIf { it != 0L } ?: steamId
        return LoginResult(
            sessionId = sessionId,
            steamLoginSecure = "$actualSteamId||$tokenSecure",
            refreshToken = "",   // dologin doesn't provide a separate refresh token
            accessToken = tokenSecure,
        )
    }

    /**
     * Exchange a refresh_token (from a previous IAuthenticationService login) for a new access token.
     * Only called if account.refreshToken is non-blank from a prior session.
     */
    fun refreshAccessToken(refreshToken: String, steamId: Long): String {
        val requestBody = FormBody.Builder()
            .add("input_json", JSONObject().apply {
                put("refresh_token", refreshToken)
                put("steamid", steamId.toString())
            }.toString())
            .build()

        val request = Request.Builder()
            .url("https://api.steampowered.com/IAuthenticationService/GenerateAccessTokenForApp/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty token refresh response")
        if (!response.isSuccessful) throw Exception("Token refresh failed: HTTP ${response.code}")

        val newAccessToken = JSONObject(body)
            .optJSONObject("response")
            ?.optString("access_token", "")
            ?: throw Exception("Invalid token refresh response: $body")

        if (newAccessToken.isBlank()) throw Exception("No access_token in refresh response: $body")
        return "$steamId||$newAccessToken"
    }

    // ── Step 1: RSA public key ────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String): RsaKey {
        val request = Request.Builder()
            .url("$COMMUNITY/login/getrsakey/?username=${accountName.encodeUrl()}&donotcache=${System.currentTimeMillis()}")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36")
            .header("Cookie", "mobileClientVersion=0 (3.0.0); mobileClient=android")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty RSA key response")
        if (!response.isSuccessful) throw Exception("RSA key fetch failed: HTTP ${response.code}")

        val json = JSONObject(body)
        if (!json.optBoolean("success", false)) throw Exception("RSA key request failed: $body")

        return RsaKey(
            mod = json.getString("publickey_mod"),
            exp = json.getString("publickey_exp"),
            timestamp = json.optString("timestamp", "0").toLongOrNull() ?: 0L,
        )
    }

    // ── Step 2: RSA-encrypt password ─────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modHex, 16), BigInteger(expHex, 16))
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ── Step 3: Community dologin ─────────────────────────────────────────────

    private fun doLogin(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
        totpCode: String,
        sessionId: String,
        steamId: Long,
    ): Pair<String, Long> {
        val requestBody = FormBody.Builder()
            .add("username", accountName)
            .add("password", encryptedPassword)
            .add("twofactorcode", totpCode)
            .add("emailauth", "")
            .add("loginfriendlyname", "")
            .add("captchagid", "-1")
            .add("captcha_text", "")
            .add("emailsteamid", steamId.toString())
            .add("rsatimestamp", rsaTimestamp.toString())
            .add("remember_login", "true")
            .add("tokentype", "-1")
            .add("donotcache", System.currentTimeMillis().toString())
            .build()

        val request = Request.Builder()
            .url("$COMMUNITY/login/dologin/")
            .post(requestBody)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            )
            .header(
                "Cookie",
                "sessionid=$sessionId; mobileClientVersion=0 (3.0.0); mobileClient=android",
            )
            .header(
                "Referer",
                "$COMMUNITY/mobilelogin?oauth_client_id=DE45CD61&oauth_scope=read_profile%20write_profile%20read_client%20write_client",
            )
            .header("X-Requested-With", "com.valvesoftware.android.steam.community")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty dologin response")
        if (!response.isSuccessful) throw Exception("dologin HTTP ${response.code}: $body")

        val json = JSONObject(body)

        if (!json.optBoolean("success", false)) {
            val message = json.optString("message", "")
            when {
                json.optBoolean("emailauth_needed", false) ->
                    throw Exception("Steam requires email confirmation. Check your email and try again.")
                json.optBoolean("captcha_needed", false) ->
                    throw Exception("Steam requires CAPTCHA. Try again in a few minutes.")
                message.isNotBlank() -> throw Exception("Steam login failed: $message")
                else -> throw Exception("Steam login failed (success=false): $body")
            }
        }

        if (!json.optBoolean("login_complete", false)) {
            throw Exception("Steam login incomplete: $body")
        }

        val transferParams = json.optJSONObject("transfer_parameters")
            ?: throw Exception("No transfer_parameters in login response: $body")

        val tokenSecure = transferParams.optString("token_secure", "")
        if (tokenSecure.isBlank()) throw Exception("No token_secure in login response: $body")

        val serverSteamId = transferParams.optString("steamid", "0").toLongOrNull() ?: 0L
        return Pair(tokenSecure, serverSteamId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
