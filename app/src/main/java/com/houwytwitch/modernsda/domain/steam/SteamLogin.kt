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
 * Implements the Steam IAuthenticationService login flow.
 *
 * Uses JSON (input_json + ?format=json) for most endpoints.
 * Uses protobuf (input_protobuf_encoded) for UpdateAuthSessionWithSteamGuardCode
 * and PollAuthSessionStatus, because those endpoints silently ignore input_json.
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

        // 3. Begin auth session via JSON – returns clientId (uint64 string) and requestId (base64 bytes)
        val (clientId, requestId) = beginAuthSession(accountName, encryptedPassword, rsaTimestamp)

        // 4. Submit TOTP via protobuf (input_json is silently ignored by this endpoint)
        val totpCode = SteamTotp.generateCode(sharedSecret)
        submitSteamGuardCodeProto(clientId, steamId, totpCode)

        // 5. Poll for tokens via protobuf (JSON poll responses omit refresh_token/access_token)
        val (refreshToken, accessToken) = pollForTokensProto(clientId, requestId)

        val sessionId = generateSessionId()
        return LoginResult(
            sessionId = sessionId,
            steamLoginSecure = "$steamId||$accessToken",
            refreshToken = refreshToken,
            accessToken = accessToken,
        )
    }

    /**
     * Exchanges a refresh_token for a new access_token.
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
            ?: throw Exception("Invalid token refresh response: $body")
        val newAccessToken = resp.optString("access_token", "")
        if (newAccessToken.isBlank()) throw Exception("No access_token in refresh response: $body")

        return "$steamId||$newAccessToken"
    }

    // ── Step 1: RSA key (JSON) ────────────────────────────────────────────────

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

        val resp = JSONObject(body).optJSONObject("response")
            ?: throw Exception("Missing RSA key response: $body")

        return RsaKey(
            mod = resp.getString("publickey_mod"),
            exp = resp.getString("publickey_exp"),
            timestamp = resp.optString("timestamp", "0").toLongOrNull() ?: 0L,
        )
    }

    // ── Step 2: RSA encrypt password ──────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modHex, 16), BigInteger(expHex, 16))
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ── Step 3: Begin auth session (JSON) ─────────────────────────────────────

    // Returns (clientId as uint64 decimal string, requestId as base64 bytes string)
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
            put("persistence", 1)
            put("website_id", "Mobile")
            put("device_details", JSONObject().apply {
                put("device_friendly_name", "Android Phone")
                put("platform_type", 3)   // k_EAuthTokenPlatformType_MobileApp
                put("os_type", -500)      // k_EOSType_AndroidUnknown
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

        val resp = JSONObject(body).optJSONObject("response")
            ?: throw Exception("Invalid BeginAuthSession response: $body")

        val clientId = resp.optString("client_id", "")
        val requestId = resp.optString("request_id", "")

        if (clientId.isBlank()) throw Exception("BeginAuthSession: missing client_id in: $body")
        if (requestId.isBlank()) throw Exception("BeginAuthSession: missing request_id in: $body")

        return Pair(clientId, requestId)
    }

    // ── Step 4: Submit TOTP (PROTOBUF — input_json is silently ignored here) ─

    private fun submitSteamGuardCodeProto(clientId: String, steamId: Long, code: String) {
        // Parse clientId as unsigned 64-bit integer into Long (keeps correct bit pattern)
        val clientIdLong = BigInteger(clientId).toLong()

        val proto = ProtoUtils.concat(
            ProtoUtils.encodeVarintField(1, clientIdLong),   // client_id (uint64)
            ProtoUtils.encodeFixed64(2, steamId),            // steamid (fixed64 — wire type 1)
            ProtoUtils.encodeString(3, code),                // code
            ProtoUtils.encodeVarintField(4, 3L),             // code_type = DeviceCode (3)
        )

        val requestBody = FormBody.Builder()
            .add("input_protobuf_encoded", Base64.encodeToString(proto, Base64.NO_WRAP))
            .build()

        val request = Request.Builder()
            .url("$API_BASE/UpdateAuthSessionWithSteamGuardCode/v1")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        response.body?.close()

        if (response.code == 400) {
            throw Exception("Two-factor code rejected by Steam (wrong code or expired, try again)")
        }
        if (!response.isSuccessful) {
            throw Exception("SteamGuard submission failed: HTTP ${response.code}")
        }
    }

    // ── Step 5: Poll for tokens (PROTOBUF — JSON poll omits refresh/access tokens) ─

    private fun pollForTokensProto(clientId: String, requestIdB64: String): Pair<String, String> {
        val clientIdLong = BigInteger(clientId).toLong()
        val requestIdBytes = Base64.decode(requestIdB64, Base64.DEFAULT)

        val proto = ProtoUtils.concat(
            ProtoUtils.encodeVarintField(1, clientIdLong),  // client_id (uint64)
            ProtoUtils.encodeBytes(2, requestIdBytes),      // request_id (bytes)
        )
        val encodedProto = Base64.encodeToString(proto, Base64.NO_WRAP)

        var lastBody = ""
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS)

            val requestBody = FormBody.Builder()
                .add("input_protobuf_encoded", encodedProto)
                .build()

            val request = Request.Builder()
                .url("$API_BASE/PollAuthSessionStatus/v1")
                .post(requestBody)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBytes = response.body?.bytes() ?: return@repeat
            lastBody = responseBytes.toString(Charsets.UTF_8).take(200)

            if (!response.isSuccessful) return@repeat

            // Parse protobuf response: field 3 = refresh_token, field 4 = access_token
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

        throw Exception(
            "Login timed out: Steam never confirmed authentication. " +
            "Last poll response (${lastBody.length} chars): ${lastBody.take(100)}"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
