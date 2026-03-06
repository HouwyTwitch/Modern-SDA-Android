package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import com.google.gson.Gson
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Steam login via IAuthenticationService API (crab713/steampy fork approach).
 *
 * Full flow:
 *   1. QueryTime          – sync clock offset (fixes emulator drift)
 *   2. GetPasswordRSAPublicKey  – RSA public key
 *   3. BeginAuthSessionViaCredentials  – start session, get client_id / request_id
 *   4. UpdateAuthSessionWithSteamGuardCode  – submit TOTP (plain form fields)
 *   5. PollAuthSessionStatus  – wait for refresh_token
 *   6. /jwt/finalizelogin  – exchange refresh_token for session cookies
 *   7. POST to each transfer_info URL  – sets steamLoginSecure cookie
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val API_BASE = "https://api.steampowered.com"
        private const val LOGIN_BASE = "https://login.steampowered.com"
        private const val COMMUNITY = "https://steamcommunity.com"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_ATTEMPTS = 10
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
        // Cookie jar shared across all login requests
        val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
        val loginClient = buildCookieClient(cookieStore)
        val sessionId = generateSessionId()

        // Pre-seed mobile client cookies
        seedCookies(cookieStore, "steamcommunity.com", mapOf(
            "sessionid" to sessionId,
            "mobileClientVersion" to "0 (3.0.0)",
            "mobileClient" to "android",
        ))

        // 1. Sync time with Steam (correct emulator clock drift)
        val timeOffset = fetchSteamTimeOffset()

        // 2. RSA public key
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName)

        // 3. Encrypt password + TOTP
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)
        val totpCode = SteamTotp.generateCode(sharedSecret, timeOffsetSeconds = timeOffset)

        // 4. Begin auth session (input_json + ?format=json)
        val (clientId, serverSteamId, requestId) = beginAuthSession(
            accountName = accountName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaTimestamp,
            client = loginClient,
        )
        val actualSteamId = serverSteamId.takeIf { it != 0L } ?: steamId

        // 5. Submit TOTP — plain form fields (as used by crab713/steampy fork)
        submitGuardCode(clientId, actualSteamId, totpCode, loginClient)

        // 6. Poll until we get refresh_token
        val (refreshToken, accessToken) = pollForTokens(clientId, requestId, loginClient)

        // 7. FinalizeLogin — converts refresh_token into steamLoginSecure cookie
        finalizeLogin(refreshToken, sessionId, loginClient)

        // 8. Extract steamLoginSecure from cookie jar (URL-decoded)
        val steamLoginSecure = cookieStore["steamcommunity.com"]
            ?.find { it.name == "steamLoginSecure" }?.value
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: run {
                // Fallback: build from access_token if cookie wasn't captured
                if (accessToken.isNotBlank()) "$actualSteamId||$accessToken"
                else throw Exception("Login failed: no steamLoginSecure cookie after finalizelogin")
            }

        // Prefer Steam's sessionid from cookie jar over our generated one
        val finalSessionId = cookieStore["steamcommunity.com"]
            ?.find { it.name == "sessionid" }?.value ?: sessionId

        return LoginResult(
            sessionId = finalSessionId,
            steamLoginSecure = steamLoginSecure,
            refreshToken = refreshToken,
            accessToken = steamLoginSecure.substringAfter("||", missingDelimiterValue = accessToken),
        )
    }

    /**
     * Exchange a stored refresh_token for a new access_token.
     */
    fun refreshAccessToken(refreshToken: String, steamId: Long): String {
        val requestBody = FormBody.Builder()
            .add("input_json", JSONObject().apply {
                put("refresh_token", refreshToken)
                put("steamid", steamId.toString())
            }.toString())
            .build()

        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/GenerateAccessTokenForApp/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty token refresh response")
        if (!response.isSuccessful) throw Exception("Token refresh failed: HTTP ${response.code}")

        val newToken = JSONObject(body).optJSONObject("response")?.optString("access_token", "")
            .takeIf { !it.isNullOrBlank() }
            ?: throw Exception("No access_token in refresh response: $body")

        return "$steamId||$newToken"
    }

    // ── Step 1: Query Steam server time ──────────────────────────────────────

    private fun fetchSteamTimeOffset(): Long {
        return try {
            val request = Request.Builder()
                .url("$API_BASE/ITwoFactorService/QueryTime/v0001")
                .post(RequestBody.create(null, ByteArray(0)))
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return 0L
            val serverTime = JSONObject(body).optJSONObject("response")?.optLong("server_time", 0L) ?: 0L
            if (serverTime == 0L) 0L else serverTime - (System.currentTimeMillis() / 1000)
        } catch (_: Exception) { 0L }
    }

    // ── Step 2: RSA public key ────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String): RsaKey {
        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/GetPasswordRSAPublicKey/v1?format=json&account_name=${accountName.encodeUrl()}")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty RSA key response")
        if (!response.isSuccessful) throw Exception("RSA key fetch failed: HTTP ${response.code}")

        val resp = JSONObject(body).optJSONObject("response")
            ?: throw Exception("Bad RSA key response: $body")

        return RsaKey(
            mod = resp.getString("publickey_mod"),
            exp = resp.getString("publickey_exp"),
            timestamp = resp.optString("timestamp", "0").toLongOrNull() ?: 0L,
        )
    }

    // ── Step 3: RSA-encrypt password ─────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modHex, 16), BigInteger(expHex, 16))
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ── Step 4: Begin auth session ────────────────────────────────────────────

    private data class SessionInfo(val clientId: String, val steamId: Long, val requestId: String)

    private fun beginAuthSession(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
        client: OkHttpClient,
    ): SessionInfo {
        val json = JSONObject().apply {
            put("account_name", accountName)
            put("encrypted_password", encryptedPassword)
            put("encryption_timestamp", rsaTimestamp)
            put("remember_login", true)
            put("persistence", 1)
            put("website_id", "Mobile")
            put("device_details", JSONObject().apply {
                put("platform_type", 3)   // k_EAuthTokenPlatformType_MobileApp
                put("os_type", -500)
            })
        }

        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/BeginAuthSessionViaCredentials/v1?format=json")
            .post(FormBody.Builder().add("input_json", json.toString()).build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty BeginAuthSession response")
        if (response.code == 401 || response.code == 403) throw Exception("Invalid credentials (HTTP ${response.code})")
        if (!response.isSuccessful) throw Exception("BeginAuthSession failed: HTTP ${response.code} — $body")

        val resp = JSONObject(body).optJSONObject("response")
            ?: throw Exception("Invalid BeginAuthSession response: $body")

        val clientId = resp.optString("client_id", "")
        val steamId = resp.optString("steamid", "0").toLongOrNull() ?: 0L
        val requestId = resp.optString("request_id", "")

        if (clientId.isBlank()) throw Exception("BeginAuthSession: missing client_id in: $body")
        if (requestId.isBlank()) throw Exception("BeginAuthSession: missing request_id in: $body")

        return SessionInfo(clientId, steamId, requestId)
    }

    // ── Step 5: Submit TOTP (plain form fields — crab713/steampy approach) ───

    private fun submitGuardCode(clientId: String, steamId: Long, code: String, client: OkHttpClient) {
        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/UpdateAuthSessionWithSteamGuardCode/v1?format=json")
            .post(FormBody.Builder()
                .add("client_id", clientId)
                .add("steamid", steamId.toString())
                .add("code_type", "3")
                .add("code", code)
                .build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (response.code == 400) throw Exception("Steam rejected the authenticator code (wrong code or expired). Response: $body")
        if (!response.isSuccessful) throw Exception("UpdateAuthSession failed: HTTP ${response.code} — $body")
    }

    // ── Step 6: Poll for tokens ───────────────────────────────────────────────

    private fun pollForTokens(clientId: String, requestId: String, client: OkHttpClient): Pair<String, String> {
        val requestBody = FormBody.Builder()
            .add("input_json", JSONObject().apply {
                put("client_id", clientId)
                put("request_id", requestId)
            }.toString())
            .build()

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS)

            val request = Request.Builder()
                .url("$API_BASE/IAuthenticationService/PollAuthSessionStatus/v1?format=json")
                .post(requestBody)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .header("Referer", "$COMMUNITY/")
                .header("Origin", COMMUNITY)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: continue

            if (!response.isSuccessful) continue

            val resp = JSONObject(body).optJSONObject("response") ?: continue
            val refreshToken = resp.optString("refresh_token", "")
            val accessToken = resp.optString("access_token", "")

            if (refreshToken.isNotBlank()) {
                return Pair(refreshToken, accessToken)
            }
        }

        throw Exception("Login timed out: Steam never returned tokens after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
    }

    // ── Step 7: Finalize login (gets steamLoginSecure cookie) ─────────────────

    private fun finalizeLogin(refreshToken: String, sessionId: String, client: OkHttpClient) {
        val request = Request.Builder()
            .url("$LOGIN_BASE/jwt/finalizelogin")
            .post(FormBody.Builder()
                .add("nonce", refreshToken)
                .add("sessionid", sessionId)
                .add("redir", "$COMMUNITY/login/home/?goto=")
                .build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw Exception("Empty finalizelogin response")
        if (!response.isSuccessful) throw Exception("finalizelogin failed: HTTP ${response.code} — $body")

        val json = JSONObject(body)
        val steamID = json.optString("steamID", "")
        val transferInfo = json.optJSONArray("transfer_info")
            ?: return  // Some accounts may skip this step

        // POST to each transfer URL — these set steamLoginSecure and other cookies
        for (i in 0 until transferInfo.length()) {
            val entry = transferInfo.getJSONObject(i)
            val url = entry.optString("url", "").ifBlank { continue }
            val params = entry.optJSONObject("params") ?: continue

            val formBuilder = FormBody.Builder()
            params.keys().forEach { key -> formBuilder.add(key, params.optString(key)) }
            if (steamID.isNotBlank()) formBuilder.add("steamID", steamID)

            try {
                val transferRequest = Request.Builder()
                    .url(url)
                    .post(formBuilder.build())
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                    .header("Referer", "$COMMUNITY/")
                    .build()
                client.newCall(transferRequest).execute().body?.close()
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildCookieClient(cookieStore: MutableMap<String, MutableList<Cookie>>): OkHttpClient =
        httpClient.newBuilder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                        cookies.forEach { new -> removeAll { it.name == new.name }; add(new) }
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    cookieStore[url.host] ?: emptyList()
            })
            .build()

    private fun seedCookies(store: MutableMap<String, MutableList<Cookie>>, domain: String, values: Map<String, String>) {
        store.getOrPut(domain) { mutableListOf() }.apply {
            values.forEach { (name, value) ->
                add(Cookie.Builder().name(name).value(value).domain(domain).path("/").build())
            }
        }
    }

    private fun generateSessionId(): String =
        (1..24).map { Random.nextInt(0, 16).toString(16) }.joinToString("")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
