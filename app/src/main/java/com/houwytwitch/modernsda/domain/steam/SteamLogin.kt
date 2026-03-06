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
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * Authenticates via the Steam community website login endpoint (dologin).
 * This is the approach used by steampy and compatible SDA tools.
 *
 * Flow:
 *   1. Sync time with Steam servers (fix clock drift on emulators)
 *   2. GET getrsakey → RSA public key + timestamp (session cookies stored via cookie jar)
 *   3. RSA-encrypt password, generate TOTP with synced time
 *   4. POST dologin → transfer_parameters.token_secure (JWT access token)
 */
class SteamLogin(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val COMMUNITY = "https://steamcommunity.com"
        private const val STEAM_API = "https://api.steampowered.com"
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
        // Build a client with a cookie jar so session cookies flow through the login requests
        val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
        val loginClient = httpClient.newBuilder()
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

        // Pre-seed mobile client cookies (required by Steam's mobile login)
        listOf("mobileClientVersion" to "0 (3.0.0)", "mobileClient" to "android").forEach { (name, value) ->
            cookieStore.getOrPut("steamcommunity.com") { mutableListOf() }.add(
                Cookie.Builder().name(name).value(value).domain("steamcommunity.com").path("/").build()
            )
        }

        // 1. Sync time with Steam to correct emulator clock drift
        val timeOffset = fetchSteamTimeOffset(loginClient)

        // 2. Get RSA key (this also lets Steam set a sessionid cookie via cookie jar)
        val (rsaMod, rsaExp, rsaTimestamp) = getRsaKey(accountName, loginClient)

        // 3. Encrypt password + generate TOTP with server-synced time
        val encryptedPassword = rsaEncryptPassword(password, rsaMod, rsaExp)
        val totpCode = SteamTotp.generateCode(sharedSecret, timeOffsetSeconds = timeOffset)

        // 4. Login via dologin
        val (tokenSecure, serverSteamId) = doLogin(
            accountName = accountName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaTimestamp,
            totpCode = totpCode,
            steamId = steamId,
            client = loginClient,
        )

        // Use Steam's sessionid from cookie jar if available, otherwise generate one
        val sessionId = cookieStore["steamcommunity.com"]
            ?.find { it.name == "sessionid" }?.value
            ?: generateSessionId()

        val actualSteamId = serverSteamId.takeIf { it != 0L } ?: steamId
        return LoginResult(
            sessionId = sessionId,
            steamLoginSecure = "$actualSteamId||$tokenSecure",
            refreshToken = "",
            accessToken = tokenSecure,
        )
    }

    /**
     * Exchange a refresh_token (from a previous IAuthenticationService login) for a new access token.
     */
    fun refreshAccessToken(refreshToken: String, steamId: Long): String {
        val requestBody = FormBody.Builder()
            .add("input_json", JSONObject().apply {
                put("refresh_token", refreshToken)
                put("steamid", steamId.toString())
            }.toString())
            .build()

        val request = Request.Builder()
            .url("$STEAM_API/IAuthenticationService/GenerateAccessTokenForApp/v1?format=json")
            .post(requestBody)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty token refresh response")
        if (!response.isSuccessful) throw Exception("Token refresh failed: HTTP ${response.code}")

        val newAccessToken = JSONObject(body)
            .optJSONObject("response")
            ?.optString("access_token", "")
            .takeIf { !it.isNullOrBlank() }
            ?: throw Exception("No access_token in refresh response: $body")

        return "$steamId||$newAccessToken"
    }

    // ── Step 0: Sync time ─────────────────────────────────────────────────────

    private fun fetchSteamTimeOffset(client: OkHttpClient): Long {
        return try {
            val request = Request.Builder()
                .url("$STEAM_API/ITwoFactorService/QueryTime/v0001")
                .post(RequestBody.create(null, ByteArray(0)))
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 14)")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return 0L
            val serverTime = JSONObject(body).optJSONObject("response")?.optLong("server_time", 0L) ?: 0L
            if (serverTime == 0L) 0L else serverTime - (System.currentTimeMillis() / 1000)
        } catch (_: Exception) {
            0L
        }
    }

    // ── Step 1: RSA public key ────────────────────────────────────────────────

    private data class RsaKey(val mod: String, val exp: String, val timestamp: Long)

    private fun getRsaKey(accountName: String, client: OkHttpClient): RsaKey {
        val request = Request.Builder()
            .url("$COMMUNITY/login/getrsakey/?username=${accountName.encodeUrl()}&donotcache=${System.currentTimeMillis()}")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36")
            .get()
            .build()

        val response = client.newCall(request).execute()
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
        steamId: Long,
        client: OkHttpClient,
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
                "Referer",
                "$COMMUNITY/mobilelogin?oauth_client_id=DE45CD61&oauth_scope=read_profile%20write_profile%20read_client%20write_client",
            )
            .header("X-Requested-With", "com.valvesoftware.android.steam.community")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty dologin response")
        if (!response.isSuccessful) throw Exception("dologin HTTP ${response.code}: $body")

        val json = JSONObject(body)

        if (!json.optBoolean("success", false)) {
            val message = json.optString("message", "")
            when {
                json.optBoolean("emailauth_needed", false) ->
                    throw Exception("Steam requires email confirmation. Check your email and try again.")
                json.optBoolean("captcha_needed", false) ->
                    throw Exception("Steam requires CAPTCHA. Please try again in a few minutes.")
                json.optBoolean("requires_twofactor", false) ->
                    throw Exception("Steam rejected the authenticator code. Check device clock sync and try again.")
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
