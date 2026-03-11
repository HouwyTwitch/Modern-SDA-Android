package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Steam login via IAuthenticationService API (aiosteampy approach).
 *
 * Full flow:
 *   1. GET steamcommunity.com        – init session cookies
 *   2. QueryTime                     – sync clock offset (fixes emulator drift)
 *   3. GetPasswordRSAPublicKey       – RSA public key
 *   4. BeginAuthSessionViaCredentials – start session via input_protobuf_encoded
 *   5. UpdateAuthSessionWithSteamGuardCode – submit TOTP (plain form fields)
 *   6. PollAuthSessionStatus         – poll via input_protobuf_encoded, parse protobuf response
 *   7. /jwt/finalizelogin            – exchange refresh_token for session cookies
 *   8. POST to each transfer_info URL – sets steamLoginSecure cookie
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val TAG = "SteamLogin"
        private const val API_BASE = "https://api.steampowered.com"
        private const val LOGIN_BASE = "https://login.steampowered.com"
        private const val COMMUNITY = "https://steamcommunity.com"
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_ATTEMPTS = 10

        // https://github.com/DoctorMcKay/node-steam-session/blob/698469cdbad3e555dda10c81f580f1ee3960156f/src/helpers.ts#L17
        private val API_HEADERS = mapOf(
            "accept" to "application/json, text/plain, */*",
            "sec-fetch-site" to "cross-site",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty",
        )
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

        // 1. Init session — GET Community to acquire initial cookies
        //    https://github.com/somespecialone/aiosteampy/blob/master/aiosteampy/mixins/login.py
        initSession(loginClient)

        // 2. Sync time with Steam (correct emulator clock drift)
        val timeOffset = fetchSteamTimeOffset()

        // 3. RSA public key
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName)

        // 4. Encrypt password + TOTP
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)
        val totpCode = SteamTotp.generateCode(sharedSecret, timeOffsetSeconds = timeOffset)
        Log.d(TAG, "[Login] timeOffset=${timeOffset}s totpCode=$totpCode rsaTimestamp=${rsaTimestamp}")

        // 5. Begin auth session via protobuf-encoded request
        val (clientId, serverSteamId, requestIdBytes) = beginAuthSession(
            accountName = accountName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaTimestamp,
            client = loginClient,
        )
        val actualSteamId = serverSteamId.takeIf { it != 0L } ?: steamId
        Log.d(TAG, "[Login] clientId=$clientId steamId=$actualSteamId requestId=${Base64.encodeToString(requestIdBytes, Base64.NO_WRAP)}")

        // 6. Submit TOTP
        submitGuardCode(clientId, actualSteamId, totpCode, loginClient)

        // 7. Poll via protobuf encoding until we get refresh_token
        val (refreshToken, accessToken) = pollForTokens(clientId, requestIdBytes, loginClient)

        // 8. FinalizeLogin — converts refresh_token into steamLoginSecure cookie
        finalizeLogin(refreshToken, sessionId, loginClient)

        // 9. Extract steamLoginSecure from cookie jar (URL-decoded)
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

    // ── Step 1: Init session ──────────────────────────────────────────────────

    private fun initSession(client: OkHttpClient) {
        try {
            val request = Request.Builder()
                .url(COMMUNITY)
                .get()
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()
            client.newCall(request).execute().body?.close()
        } catch (_: Exception) { /* best-effort */ }
    }

    // ── Step 2: Query Steam server time ──────────────────────────────────────

    private fun fetchSteamTimeOffset(): Long {
        return try {
            val request = Request.Builder()
                .url("$API_BASE/ITwoFactorService/QueryTime/v0001")
                .post(ByteArray(0).toRequestBody(null))
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()
            Log.d(TAG, "[QueryTime] POST ${request.url} (empty body)")
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return 0L
            Log.d(TAG, "[QueryTime] ${response.code} body=$body")
            val serverTime = JSONObject(body).optJSONObject("response")?.optLong("server_time", 0L) ?: 0L
            if (serverTime == 0L) 0L else serverTime - (System.currentTimeMillis() / 1000)
        } catch (_: Exception) { 0L }
    }

    // ── Step 3: RSA public key ────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String): RsaKey {
        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/GetPasswordRSAPublicKey/v1?format=json&account_name=${accountName.encodeUrl()}")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .get()
            .build()

        Log.d(TAG, "[GetRSAKey] GET ${request.url}")
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty RSA key response")
        Log.d(TAG, "[GetRSAKey] ${response.code} body=$body")
        if (!response.isSuccessful) throw Exception("RSA key fetch failed: HTTP ${response.code}")

        val resp = JSONObject(body).optJSONObject("response")
            ?: throw Exception("Bad RSA key response: $body")

        return RsaKey(
            mod = resp.getString("publickey_mod"),
            exp = resp.getString("publickey_exp"),
            timestamp = resp.optString("timestamp", "0").toLongOrNull() ?: 0L,
        )
    }

    // ── Step 4: RSA-encrypt password ─────────────────────────────────────────

    private fun rsaEncryptPassword(password: String, modHex: String, expHex: String): String {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modHex, 16), BigInteger(expHex, 16))
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    // ── Step 5: Begin auth session (protobuf-encoded) ─────────────────────────

    private data class SessionInfo(
        val clientId: Long,
        val steamId: Long,
        val requestId: ByteArray,
    )

    /**
     * Encode CAuthentication_BeginAuthSessionViaCredentials_Request as protobuf.
     *
     * Field map matches aiosteampy auth_pb2.py exactly:
     *   2  string  account_name
     *   3  string  encrypted_password
     *   4  uint64  encryption_timestamp
     *   5  uint32  remember_login
     *   7  uint32  persistence
     *   8  string  website_id          ("Community")
     *   9  message device_details
     *     1  string  device_friendly_name
     *     2  enum    platform_type  (2 = WebBrowser)
     *   11 uint32  additional_field    (8)
     */
    private fun buildBeginAuthProto(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
    ): ByteArray {
        val deviceDetails = ProtoUtils.concat(
            ProtoUtils.encodeString(1, "Modern SDA Android"),
            ProtoUtils.encodeVarintField(2, 2L),  // k_EAuthTokenPlatformType_WebBrowser
        )
        return ProtoUtils.concat(
            ProtoUtils.encodeString(2, accountName),
            ProtoUtils.encodeString(3, encryptedPassword),
            ProtoUtils.encodeVarintField(4, rsaTimestamp),
            ProtoUtils.encodeVarintField(5, 1L),   // remember_login
            ProtoUtils.encodeVarintField(7, 1L),   // persistence
            ProtoUtils.encodeString(8, "Community"),
            ProtoUtils.encodeMessage(9, deviceDetails),
            ProtoUtils.encodeVarintField(11, 8L),  // additional_field
        )
    }

    /**
     * Parse CAuthentication_BeginAuthSessionViaCredentials_Response protobuf bytes.
     *
     * Proto field map:
     *   1  uint64  client_id
     *   2  bytes   request_id
     *   5  uint64  steamid
     */
    private fun parseBeginAuthResponse(data: ByteArray): SessionInfo {
        val reader = ProtoUtils.Reader(data)
        var clientId = 0L
        var requestId = ByteArray(0)
        var steamId = 0L
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.nextTag() ?: break
            when (fieldNum) {
                1 -> clientId = reader.readVarint()
                2 -> requestId = reader.readBytes()
                5 -> steamId = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        if (clientId == 0L) throw Exception("BeginAuthSession: missing client_id in protobuf response")
        return SessionInfo(clientId, steamId, requestId)
    }

    private fun beginAuthSession(
        accountName: String,
        encryptedPassword: String,
        rsaTimestamp: Long,
        client: OkHttpClient,
    ): SessionInfo {
        val protoBytes = buildBeginAuthProto(accountName, encryptedPassword, rsaTimestamp)
        val encoded = Base64.encodeToString(protoBytes, Base64.NO_WRAP)

        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/BeginAuthSessionViaCredentials/v1")
            .post(FormBody.Builder().add("input_protobuf_encoded", encoded).build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)
            .build()

        Log.d(TAG, "[BeginAuth] POST ${request.url} payload_b64=$encoded")
        val response = client.newCall(request).execute()
        val contentType = response.header("Content-Type") ?: ""
        val bodyBytes = response.body?.bytes() ?: throw Exception("Empty BeginAuthSession response")
        Log.d(TAG, "[BeginAuth] ${response.code} content-type=$contentType bytes=${bodyBytes.size} hex=${bodyBytes.toHex()}")

        if (response.code == 401 || response.code == 403) {
            throw Exception("Invalid credentials (HTTP ${response.code})")
        }
        if (!response.isSuccessful) {
            throw Exception("BeginAuthSession failed: HTTP ${response.code} — ${bodyBytes.decodeToString()}")
        }

        // Handle JSON fallback (Steam may still return JSON for some accounts)
        if (contentType.contains("application/json")) {
            val json = JSONObject(bodyBytes.decodeToString())
            val resp = json.optJSONObject("response")
                ?: throw Exception("Invalid BeginAuthSession JSON response: ${bodyBytes.decodeToString()}")
            val clientId = resp.optString("client_id", "").toLongOrNull()
                ?: throw Exception("BeginAuthSession: missing client_id")
            val steamId = resp.optString("steamid", "0").toLongOrNull() ?: 0L
            val requestId = resp.optString("request_id", "")
                .let { Base64.decode(it, Base64.DEFAULT) }
            return SessionInfo(clientId, steamId, requestId)
        }

        // Default: protobuf response
        return parseBeginAuthResponse(bodyBytes)
    }

    // ── Step 6: Submit TOTP (raw form fields, same as aiosteampy) ────────────
    //
    // aiosteampy sends plain form fields — NOT input_protobuf_encoded:
    //   client_id  string  (integer as string)
    //   steamid    string  (integer as string)
    //   code_type  int     3 = k_EAuthSessionGuardType_DeviceCode
    //   code       string  TOTP code

    private fun submitGuardCode(clientId: Long, steamId: Long, code: String, client: OkHttpClient) {
        val formBody = FormBody.Builder()
            .add("client_id", clientId.toString())
            .add("steamid", steamId.toString())
            .add("code_type", "3")
            .add("code", code)
            .build()

        val request = Request.Builder()
            .url("$API_BASE/IAuthenticationService/UpdateAuthSessionWithSteamGuardCode/v1")
            .post(formBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .build()

        Log.d(TAG, "[UpdateAuth] POST ${request.url} client_id=$clientId steamid=$steamId code=$code code_type=3")
        val response = client.newCall(request).execute()
        val contentType = response.header("Content-Type") ?: ""
        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        val body = bodyBytes.decodeToString()
        Log.d(TAG, "[UpdateAuth] ${response.code} content-type=$contentType bytes=${bodyBytes.size} hex=${bodyBytes.toHex()} body=$body")
        if (response.code == 400) throw Exception("Steam rejected the authenticator code (wrong code or expired). Response: $body")
        if (!response.isSuccessful) throw Exception("UpdateAuthSession failed: HTTP ${response.code} — $body")
    }

    // ── Step 7: Poll for tokens (protobuf-encoded) ────────────────────────────

    /**
     * Encode CAuthentication_PollAuthSessionStatus_Request as protobuf.
     *
     * Proto field map:
     *   1  uint64  client_id
     *   2  bytes   request_id
     */
    private fun buildPollProto(clientId: Long, requestId: ByteArray): ByteArray =
        ProtoUtils.concat(
            ProtoUtils.encodeVarintField(1, clientId),
            ProtoUtils.encodeBytes(2, requestId),
        )

    /**
     * Parse CAuthentication_PollAuthSessionStatus_Response protobuf bytes.
     *
     * Proto field map:
     *   3  string  refresh_token
     *   4  string  access_token
     *   5  bool    had_remote_interaction
     */
    private fun parsePollResponse(data: ByteArray): Pair<String, String> {
        val reader = ProtoUtils.Reader(data)
        var refreshToken = ""
        var accessToken = ""
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.nextTag() ?: break
            when (fieldNum) {
                3 -> refreshToken = reader.readString()
                4 -> accessToken = reader.readString()
                else -> reader.skip(wireType)
            }
        }
        return Pair(refreshToken, accessToken)
    }

    private fun pollForTokens(clientId: Long, requestId: ByteArray, client: OkHttpClient): Pair<String, String> {
        val protoBytes = buildPollProto(clientId, requestId)
        val encoded = Base64.encodeToString(protoBytes, Base64.NO_WRAP)
        val requestBody = FormBody.Builder()
            .add("input_protobuf_encoded", encoded)
            .build()

        Log.d(TAG, "[Poll] payload_b64=$encoded raw_proto=${protoBytes.toHex()} requestId_b64=${Base64.encodeToString(requestId, Base64.NO_WRAP)}")

        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            if (attempt > 0) Thread.sleep(POLL_INTERVAL_MS)

            val request = Request.Builder()
                .url("$API_BASE/IAuthenticationService/PollAuthSessionStatus/v1")
                .post(requestBody)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .header("Referer", "$COMMUNITY/")
                .header("Origin", COMMUNITY)
                .build()

            val response = client.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""
            val bodyBytes = response.body?.bytes() ?: continue
            Log.d(TAG, "[Poll#$attempt] ${response.code} content-type=$contentType bytes=${bodyBytes.size} hex=${bodyBytes.toHex()}")

            if (!response.isSuccessful) continue

            val (refreshToken, accessToken) = if (contentType.contains("application/json")) {
                // JSON fallback
                Log.d(TAG, "[Poll#$attempt] JSON body=${bodyBytes.decodeToString()}")
                val resp = JSONObject(bodyBytes.decodeToString()).optJSONObject("response") ?: continue
                Pair(
                    resp.optString("refresh_token", ""),
                    resp.optString("access_token", ""),
                )
            } else {
                // Protobuf response
                parsePollResponse(bodyBytes)
            }

            Log.d(TAG, "[Poll#$attempt] refreshToken=${refreshToken.take(20).ifEmpty { "<empty>" }} accessToken=${accessToken.take(20).ifEmpty { "<empty>" }}")

            if (refreshToken.isNotBlank()) {
                return Pair(refreshToken, accessToken)
            }
        }

        throw Exception("Login timed out: Steam never returned tokens after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
    }

    // ── Step 8: Finalize login (gets steamLoginSecure cookie) ─────────────────

    private fun finalizeLogin(refreshToken: String, sessionId: String, client: OkHttpClient) {
        val requestBuilder = Request.Builder()
            .url("$LOGIN_BASE/jwt/finalizelogin")
            .post(FormBody.Builder()
                .add("nonce", refreshToken)
                .add("sessionid", sessionId)
                .add("redir", "$COMMUNITY/login/home/?goto=")
                .build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .header("Referer", "$COMMUNITY/")
            .header("Origin", COMMUNITY)

        // Add API headers required by aiosteampy / node-steam-session
        API_HEADERS.forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string()
            ?: throw Exception("Empty finalizelogin response")
        if (!response.isSuccessful) throw Exception("finalizelogin failed: HTTP ${response.code} — $body")

        val json = JSONObject(body)
        if (json.optString("error", "").isNotBlank() && json.optString("error") != "null") {
            throw Exception("finalizelogin error: ${json.optString("error")}")
        }

        val steamID = json.optString("steamID", "")
        val transferInfo = json.optJSONArray("transfer_info")
            ?: return  // Some accounts may skip this step

        // POST to each transfer URL — these set steamLoginSecure and other cookies
        for (i in 0 until transferInfo.length()) {
            val entry = transferInfo.getJSONObject(i)
            val url = entry.optString("url", "")
            if (url.isBlank()) continue
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

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
