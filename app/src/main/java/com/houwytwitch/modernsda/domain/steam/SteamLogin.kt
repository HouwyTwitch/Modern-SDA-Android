package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
 * Implements the Steam IAuthenticationService login flow using JSON format.
 *
 * Steps:
 * 1. GetPasswordRSAPublicKey  – fetch RSA public key (JSON)
 * 2. BeginAuthSessionViaCredentials – start auth session (JSON)
 * 3. UpdateAuthSessionWithSteamGuardCode – submit TOTP (JSON)
 * 4. PollAuthSessionStatus – get refresh_token + access_token (JSON)
 * 5. Construct steamLoginSecure from steamId + access_token
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val API_BASE = "https://api.steampowered.com/IAuthenticationService"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_ATTEMPTS = 8
    }

    data class LoginResult(
        val sessionId: String,
        val steamLoginSecure: String,
        val refreshToken: String,
        val accessToken: String,
    )

    /**
     * Full login flow. Throws on any failure with a descriptive message.
     */
    fun login(
        accountName: String,
        password: String,
        sharedSecret: String,
        steamId: Long,
    ): LoginResult {
        // 1. Get RSA public key
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName)

        // 2. Encrypt password
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)

        // 3. Begin auth session – returns clientId (string) and requestId (base64 string)
        val (clientId, requestId) = beginAuthSession(accountName, encryptedPassword, rsaTimestamp)

        // 4. Submit TOTP code generated from shared_secret
        val totpCode = SteamTotp.generateCode(sharedSecret)
        submitSteamGuardCode(clientId, steamId.toString(), totpCode)

        // 5. Poll until tokens arrive (should be fast after TOTP submission)
        val (refreshToken, accessToken) = pollForTokens(clientId, requestId)

        val sessionId = generateSessionId()
        return LoginResult(
            sessionId = sessionId,
            steamLoginSecure = "$steamId||$accessToken",
            refreshToken = refreshToken,
            accessToken = accessToken,
        )
    }

    /**
     * Exchanges a refresh_token for a new access_token via GenerateAccessTokenForApp.
     * Returns new steamLoginSecure value.
     */
    fun refreshAccessToken(refreshToken: String, steamId: Long): String {
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
            put("steamid", steamId.toString())
        }

        val requestBody = FormBody.Builder()
            .add("input_json", json.toString())
            .build()

        val request = Request.Builder()
            .url("$API_BASE/GenerateAccessTokenForApp/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty token refresh response")
        if (!response.isSuccessful) throw Exception("Token refresh failed: HTTP ${response.code}")

        val root = JSONObject(body)
        val resp = root.optJSONObject("response")
            ?: throw Exception("Invalid token refresh response")
        val newAccessToken = resp.optString("access_token", "")
        if (newAccessToken.isBlank()) throw Exception("No access_token in refresh response")

        return "$steamId||$newAccessToken"
    }

    // ── Step 1: RSA key ───────────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String): RsaKey {
        val request = Request.Builder()
            .url("$API_BASE/GetPasswordRSAPublicKey/v1?format=json&account_name=${accountName.encodeUrl()}")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty RSA key response")
        if (!response.isSuccessful) throw Exception("RSA key fetch failed: HTTP ${response.code}")

        val root = JSONObject(body)
        val resp = root.optJSONObject("response")
            ?: throw Exception("Missing RSA key response")

        return RsaKey(
            mod = resp.getString("publickey_mod"),
            exp = resp.getString("publickey_exp"),
            timestamp = resp.optString("timestamp", "0").toLongOrNull() ?: 0L,
        )
    }

    // ── Step 2: RSA encrypt password ──────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val modulus = BigInteger(modHex, 16)
        val exponent = BigInteger(expHex, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ── Step 3: Begin auth session ────────────────────────────────────────────

    // Returns (clientId as string, requestId as base64 string)
    private fun beginAuthSession(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
    ): Pair<String, String> {
        val json = JSONObject().apply {
            put("account_name", accountName)
            put("encrypted_password", encryptedPassword)
            put("encryption_timestamp", rsaTimestamp)
            put("remember_login", true)
            put("persistence", 1)   // k_ESessionPersistence_Persistent
            put("website_id", "Mobile")
            put("device_details", JSONObject().apply {
                put("device_friendly_name", "Android Phone")
                put("platform_type", 3)  // k_EAuthTokenPlatformType_MobileApp
                put("os_type", -500)     // k_EOSType_AndroidUnknown
                put("gaming_device_type", 528)
            })
        }

        val requestBody = FormBody.Builder()
            .add("input_json", json.toString())
            .build()

        val request = Request.Builder()
            .url("$API_BASE/BeginAuthSessionViaCredentials/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty BeginAuthSession response")

        if (response.code == 401 || response.code == 403) {
            throw Exception("Invalid credentials (HTTP ${response.code})")
        }
        if (!response.isSuccessful) {
            throw Exception("BeginAuthSession failed: HTTP ${response.code} — $body")
        }

        val root = JSONObject(body)
        val resp = root.optJSONObject("response")
            ?: throw Exception("Invalid BeginAuthSession response: $body")

        val clientId = resp.optString("client_id", "")
        val requestId = resp.optString("request_id", "")

        if (clientId.isBlank()) throw Exception("BeginAuthSession: missing client_id")
        if (requestId.isBlank()) throw Exception("BeginAuthSession: missing request_id")

        return Pair(clientId, requestId)
    }

    // ── Step 4: Submit TOTP ───────────────────────────────────────────────────

    private fun submitSteamGuardCode(clientId: String, steamId: String, code: String) {
        val json = JSONObject().apply {
            put("client_id", clientId)
            put("steamid", steamId)
            put("code", code)
            put("code_type", 3)  // k_EAuthSessionGuardType_DeviceCode
        }

        val requestBody = FormBody.Builder()
            .add("input_json", json.toString())
            .build()

        val request = Request.Builder()
            .url("$API_BASE/UpdateAuthSessionWithSteamGuardCode/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""

        // Steam returns 400 if the TOTP code is wrong / expired
        if (response.code == 400) {
            throw Exception("Two-factor code was rejected by Steam (possibly expired, try again)")
        }
        if (!response.isSuccessful) {
            throw Exception("SteamGuard submission failed: HTTP ${response.code}")
        }
    }

    // ── Step 5: Poll for tokens ───────────────────────────────────────────────

    private fun pollForTokens(clientId: String, requestId: String): Pair<String, String> {
        val json = JSONObject().apply {
            put("client_id", clientId)
            put("request_id", requestId)
        }
        val inputJson = json.toString()

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS)

            val requestBody = FormBody.Builder()
                .add("input_json", inputJson)
                .build()

            val request = Request.Builder()
                .url("$API_BASE/PollAuthSessionStatus/v1?format=json")
                .post(requestBody)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@repeat
            if (!response.isSuccessful) return@repeat

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@repeat
            val resp = root.optJSONObject("response") ?: return@repeat

            val refreshToken = resp.optString("refresh_token", "")
            val accessToken = resp.optString("access_token", "")

            if (refreshToken.isNotBlank() && accessToken.isNotBlank()) {
                return Pair(refreshToken, accessToken)
            }
        }

        throw Exception("Login timed out waiting for Steam to confirm authentication")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
