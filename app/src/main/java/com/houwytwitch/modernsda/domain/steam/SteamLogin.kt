package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Implements the Steam IAuthenticationService login flow.
 *
 * Steps:
 * 1. GetPasswordRSAPublicKey  – fetch RSA public key for password encryption
 * 2. BeginAuthSessionViaCredentials – start auth session (protobuf)
 * 3. UpdateAuthSessionWithSteamGuardCode – submit TOTP from shared_secret (protobuf)
 * 4. PollAuthSessionStatus – retrieve refresh_token + access_token (protobuf)
 * 5. Construct steamLoginSecure cookie value from steamId + access_token
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val API_BASE = "https://api.steampowered.com/IAuthenticationService"
        private const val LOGIN_BASE = "https://login.steampowered.com"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_ATTEMPTS = 20
    }

    data class LoginResult(
        val sessionId: String,
        val steamLoginSecure: String,
        val refreshToken: String,
        val accessToken: String,
    )

    /**
     * Full login flow. Returns [LoginResult] on success.
     * @param sharedSecret Base64 shared_secret for TOTP generation.
     */
    suspend fun login(
        accountName: String,
        password: String,
        sharedSecret: String,
        steamId: Long,
    ): LoginResult {
        // 1. Get RSA public key
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName)

        // 2. Encrypt password
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)

        // 3. Begin auth session
        val (clientId, requestId, _) = beginAuthSession(accountName, encryptedPassword, rsaTimestamp)

        // 4. Submit TOTP
        val totpCode = SteamTotp.generateCode(sharedSecret)
        submitSteamGuardCode(clientId, steamId, totpCode)

        // 5. Poll for tokens
        val (refreshToken, accessToken) = pollForTokens(clientId, requestId)

        // 6. Construct session
        val sessionId = generateSessionId()
        val steamLoginSecure = "$steamId||$accessToken"

        return LoginResult(
            sessionId = sessionId,
            steamLoginSecure = steamLoginSecure,
            refreshToken = refreshToken,
            accessToken = accessToken,
        )
    }

    /**
     * Uses the stored refresh_token to obtain a new access_token.
     * Returns a new steamLoginSecure value.
     */
    fun refreshAccessToken(refreshToken: String, steamId: Long): String {
        val body = FormBody.Builder()
            .add("nonce", refreshToken)
            .add("redir", "https://steamcommunity.com/login/home/?goto=")
            .build()

        val request = Request.Builder()
            .url("$LOGIN_BASE/jwt/refresh")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty token refresh response")

        if (!response.isSuccessful) {
            throw Exception("Token refresh failed: HTTP ${response.code}")
        }

        val refreshResponse = gson.fromJson(responseBody, TokenRefreshResponse::class.java)
        val newAccessToken = refreshResponse.response?.accessToken
            ?: throw Exception("No access_token in refresh response")

        return "$steamId||$newAccessToken"
    }

    // ── Step 1: RSA key ──────────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String): RsaKey {
        val request = Request.Builder()
            .url("$API_BASE/GetPasswordRSAPublicKey/v1?account_name=$accountName")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty RSA key response")
        if (!response.isSuccessful) throw Exception("RSA key fetch failed: HTTP ${response.code}")

        val parsed = gson.fromJson(body, RsaKeyResponse::class.java)
        val inner = parsed.response ?: throw Exception("Missing RSA key response body")

        return RsaKey(
            mod = inner.publickeyMod ?: throw Exception("Missing publickey_mod"),
            exp = inner.publickeyExp ?: throw Exception("Missing publickey_exp"),
            timestamp = inner.timestamp?.toLongOrNull() ?: 0L,
        )
    }

    // ── Step 2: RSA encrypt password ─────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val modulus = BigInteger(modHex, 16)
        val exponent = BigInteger(expHex, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    // ── Step 3: Begin auth session ────────────────────────────────────────────

    private data class AuthSession(val clientId: Long, val requestId: ByteArray, val sessionSteamId: Long)

    private fun beginAuthSession(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
    ): AuthSession {
        // Build device_details embedded message
        val deviceDetails = ProtoUtils.concat(
            ProtoUtils.encodeString(1, "Android"),           // device_friendly_name
            ProtoUtils.encodeVarintField(2, 3L),             // platform_type = MobileApp
        )

        // Build request message
        val proto = ProtoUtils.concat(
            ProtoUtils.encodeString(1, accountName),          // account_name
            ProtoUtils.encodeString(2, encryptedPassword),    // encrypted_password
            ProtoUtils.encodeVarintField(3, rsaTimestamp),    // encryption_timestamp
            ProtoUtils.encodeVarintField(5, 1L),              // remember_login = true
            ProtoUtils.encodeVarintField(8, 1L),              // persistence = persistent
            ProtoUtils.encodeString(9, "Mobile"),             // website_id
            ProtoUtils.encodeMessage(10, deviceDetails),      // device_details
        )

        val requestBody = FormBody.Builder()
            .add("input_protobuf_encoded", Base64.encodeToString(proto, Base64.NO_WRAP))
            .build()

        val request = Request.Builder()
            .url("$API_BASE/BeginAuthSessionViaCredentials/v1")
            .post(requestBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBytes = response.body?.bytes() ?: throw Exception("Empty BeginAuthSession response")
        if (!response.isSuccessful) throw Exception("BeginAuthSession failed: HTTP ${response.code}")

        // Parse protobuf response: client_id(1,uint64) request_id(2,bytes) steamid(5,uint64)
        val reader = ProtoUtils.Reader(responseBytes)
        var clientId = 0L
        var requestId = ByteArray(0)
        var sessionSteamId = 0L

        while (reader.hasMore()) {
            val (field, wire) = reader.nextTag() ?: break
            when (field) {
                1 -> clientId = reader.readVarint()      // client_id
                2 -> requestId = reader.readBytes()      // request_id
                5 -> sessionSteamId = reader.readVarint() // steamid
                else -> reader.skip(wire)
            }
        }

        if (clientId == 0L) throw Exception("Invalid BeginAuthSession response: missing client_id")
        return AuthSession(clientId, requestId, sessionSteamId)
    }

    // ── Step 4: Submit Steam Guard TOTP code ──────────────────────────────────

    private fun submitSteamGuardCode(clientId: Long, steamId: Long, code: String) {
        val proto = ProtoUtils.concat(
            ProtoUtils.encodeVarintField(1, clientId),     // client_id
            ProtoUtils.encodeVarintField(2, steamId),      // steamid
            ProtoUtils.encodeString(3, code),              // code
            ProtoUtils.encodeVarintField(4, 3L),           // code_type = DeviceCode
        )

        val requestBody = FormBody.Builder()
            .add("input_protobuf_encoded", Base64.encodeToString(proto, Base64.NO_WRAP))
            .build()

        val request = Request.Builder()
            .url("$API_BASE/UpdateAuthSessionWithSteamGuardCode/v1")
            .post(requestBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        // 400/401 means wrong code; anything else unexpected is an error
        if (!response.isSuccessful && response.code != 400) {
            throw Exception("SteamGuard code submission failed: HTTP ${response.code}")
        }
    }

    // ── Step 5: Poll for tokens ───────────────────────────────────────────────

    private fun pollForTokens(clientId: Long, requestId: ByteArray): Pair<String, String> {
        val proto = ProtoUtils.concat(
            ProtoUtils.encodeVarintField(1, clientId),   // client_id
            ProtoUtils.encodeBytes(2, requestId),        // request_id
        )
        val encodedProto = Base64.encodeToString(proto, Base64.NO_WRAP)

        repeat(MAX_POLL_ATTEMPTS) {
            Thread.sleep(POLL_INTERVAL_MS)

            val requestBody = FormBody.Builder()
                .add("input_protobuf_encoded", encodedProto)
                .build()

            val request = Request.Builder()
                .url("$API_BASE/PollAuthSessionStatus/v1")
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBytes = response.body?.bytes() ?: return@repeat
            if (!response.isSuccessful) return@repeat

            // Parse: refresh_token(3,string) access_token(4,string)
            val reader = ProtoUtils.Reader(responseBytes)
            var refreshToken = ""
            var accessToken = ""

            while (reader.hasMore()) {
                val (field, wire) = reader.nextTag() ?: break
                when (field) {
                    3 -> refreshToken = reader.readString()   // refresh_token
                    4 -> accessToken = reader.readString()    // access_token
                    else -> reader.skip(wire)
                }
            }

            if (refreshToken.isNotBlank() && accessToken.isNotBlank()) {
                return Pair(refreshToken, accessToken)
            }
        }

        throw Exception("Timed out waiting for Steam login confirmation")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")

    // ── Response DTOs (JSON) ──────────────────────────────────────────────────

    private data class RsaKeyResponse(
        @SerializedName("response") val response: RsaKeyInner?,
    )

    private data class RsaKeyInner(
        @SerializedName("publickey_mod") val publickeyMod: String?,
        @SerializedName("publickey_exp") val publickeyExp: String?,
        @SerializedName("timestamp") val timestamp: String?,
    )

    private data class TokenRefreshResponse(
        @SerializedName("response") val response: TokenRefreshInner?,
    )

    private data class TokenRefreshInner(
        @SerializedName("token") val accessToken: String?,
    )
}
