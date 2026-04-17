package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import com.houwytwitch.modernsda.data.db.AccountDao
import com.houwytwitch.modernsda.data.model.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed class QrLoginResult {
    data object Success : QrLoginResult()
    data class Error(val message: String) : QrLoginResult()
}

class QrLoginService(
    private val httpClient: OkHttpClient,
    private val accountDao: AccountDao,
) {
    companion object {
        private const val AUTH_BASE = "https://api.steampowered.com/IAuthenticationService"
        private const val GET_AUTH_SESSIONS_URL = "$AUTH_BASE/GetAuthSessionsForAccount/v1"
        private const val GET_AUTH_SESSION_INFO_URL = "$AUTH_BASE/GetAuthSessionInfo/v1"
        private const val UPDATE_AUTH_SESSION_URL = "$AUTH_BASE/UpdateAuthSessionWithMobileConfirmation/v1"
        private const val GENERATE_ACCESS_TOKEN_URL = "$AUTH_BASE/GenerateAccessTokenForApp/v1"

        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val MOBILE_ORIGIN = "https://steamcommunity.com"
        private const val MOBILE_REFERER = "https://steamcommunity.com/mobileconf/"
    }

    fun looksLikeSteamLoginQr(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("steam") || lower.contains("s.team") || lower.contains("steammobile")
    }

    suspend fun approveLoginRequest(account: Account, scannedText: String): QrLoginResult {
        if (!looksLikeSteamLoginQr(scannedText)) {
            return QrLoginResult.Error("Not a Steam QR code")
        }

        return withContext(Dispatchers.IO) {
            try {
                val accessToken = resolveMobileAccessToken(account)
                    ?: return@withContext QrLoginResult.Error(
                        "No valid session token — please log in first so the app can approve QR codes."
                    )

                val qrClientId = resolveClientIdFromQr(scannedText, accessToken)
                val sessionIds = if (qrClientId != null) listOf(qrClientId)
                                 else getPendingAuthSessionIds(accessToken)

                when {
                    sessionIds.isEmpty() ->
                        QrLoginResult.Error("No pending login requests found")
                    sessionIds.size > 1 ->
                        QrLoginResult.Error("Multiple pending logins — scan the QR code directly")
                    account.sharedSecret.isBlank() ->
                        QrLoginResult.Error("Account is missing a shared_secret")
                    else -> {
                        approveAuthSession(account, accessToken, sessionIds.single())
                        persistToken(account, accessToken)
                        QrLoginResult.Success
                    }
                }
            } catch (e: Exception) {
                QrLoginResult.Error(e.message ?: "QR login failed")
            }
        }
    }

    // ── Client ID resolution ──────────────────────────────────────────────────

    private fun resolveClientIdFromQr(text: String, accessToken: String): ULong? {
        val direct = extractClientIdFromText(text)
        if (direct != null && canReadAuthSession(accessToken, direct)) return direct

        for (field in listOf("qrcode", "qr_code", "url")) {
            try {
                val json = postForm(
                    url = "$GET_AUTH_SESSION_INFO_URL?access_token=${enc(accessToken)}",
                    params = listOf(field to text),
                    accessToken = accessToken,
                )
                val id = parseClientIdFromJson(json) ?: continue
                if (canReadAuthSession(accessToken, id)) return id
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractClientIdFromText(text: String): ULong? {
        val decoded = try { URLDecoder.decode(text, "UTF-8") } catch (_: Exception) { text }
        val patterns = listOf(
            Regex("[?&](?:client_id|clientid)=([0-9]{5,})", RegexOption.IGNORE_CASE),
            Regex("(?:client_id|clientid)[:=]([0-9]{5,})", RegexOption.IGNORE_CASE),
            Regex("/q/\\d+/([0-9]{5,})(?:[/?#]|$)", RegexOption.IGNORE_CASE),
            Regex("/qr/([0-9]{5,})(?:[/?#]|$)", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(decoded)?.groupValues?.getOrNull(1)?.toULongOrNull()?.let { return it }
        }
        return null
    }

    private fun parseClientIdFromJson(root: JSONObject): ULong? {
        val resp = root.optJSONObject("response") ?: root
        return resp.optString("client_id", "").toULongOrNull()
            ?: resp.optLong("client_id", 0L).takeIf { it > 0L }?.toULong()
    }

    private fun canReadAuthSession(accessToken: String, clientId: ULong): Boolean = try {
        getAuthSessionInfo(accessToken, clientId); true
    } catch (_: Exception) { false }

    private fun getAuthSessionInfo(accessToken: String, clientId: ULong): JSONObject = postForm(
        url = "$GET_AUTH_SESSION_INFO_URL?access_token=${enc(accessToken)}",
        params = listOf("client_id" to clientId.toString()),
        accessToken = accessToken,
    )

    private fun getPendingAuthSessionIds(accessToken: String): List<ULong> {
        val request = Request.Builder()
            .url("$GET_AUTH_SESSIONS_URL?access_token=${enc(accessToken)}")
            .get()
            .applyHeaders(accessToken)
            .build()
        val json = execute(request)
        val resp = json.optJSONObject("response") ?: json
        val ids = mutableSetOf<ULong>()

        for (key in listOf("client_ids", "pending_client_ids", "pending_confirmations")) {
            val arr = resp.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is Number -> item.toLong().takeIf { it > 0L }?.toULong()?.let { ids.add(it) }
                    is String -> item.toULongOrNull()?.let { ids.add(it) }
                    is JSONObject -> {
                        val id = item.optString("client_id", "").toULongOrNull()
                            ?: item.optLong("client_id", 0L).takeIf { it > 0L }?.toULong()
                        if (id != null) ids.add(id)
                    }
                }
            }
        }
        return ids.toList()
    }

    // ── Session approval ──────────────────────────────────────────────────────

    private fun approveAuthSession(account: Account, accessToken: String, clientId: ULong) {
        val signature = computeSignature(clientId, account.steamId.toULong(), account.sharedSecret)
        val json = postForm(
            url = "$UPDATE_AUTH_SESSION_URL?access_token=${enc(accessToken)}",
            params = listOf(
                "version"   to "1",
                "client_id" to clientId.toString(),
                "steamid"   to account.steamId.toString(),
                "signature" to Base64.encodeToString(signature, Base64.NO_WRAP),
                "confirm"   to "true",
                "persistence" to "1",
            ),
            accessToken = accessToken,
        )
        if (json.has("success") && !json.optBoolean("success", true)) {
            throw IllegalStateException(json.optString("message", "Steam rejected QR approval"))
        }
    }

    // HMAC-SHA256 over: version[2 LE] + clientId[8 LE] + steamId[8 LE] = 18 bytes
    private fun computeSignature(clientId: ULong, steamId: ULong, sharedSecret: String): ByteArray {
        val secret = Base64.decode(sharedSecret, Base64.DEFAULT)
        val data = ByteArray(18)
        val versionBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array()
        val clientBytes  = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(clientId.toLong()).array()
        val steamBytes   = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(steamId.toLong()).array()
        System.arraycopy(versionBytes, 0, data,  0, 2)
        System.arraycopy(clientBytes,  0, data,  2, 8)
        System.arraycopy(steamBytes,   0, data, 10, 8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ── Token resolution ──────────────────────────────────────────────────────

    private fun resolveMobileAccessToken(account: Account): String? {
        account.steamLoginSecure.extractJwt()?.let { return it }
        account.oAuthToken.extractJwt()?.let { return it }
        if (account.refreshToken.isBlank()) return null
        return try {
            val json = postForm(
                url = GENERATE_ACCESS_TOKEN_URL,
                params = listOf(
                    "refresh_token" to account.refreshToken,
                    "steamid"       to account.steamId.toString(),
                    "renewal_type"  to "1",
                ),
            )
            (json.optJSONObject("response") ?: json).optString("access_token", "").extractJwt()
        } catch (_: Exception) { null }
    }

    private suspend fun persistToken(account: Account, accessToken: String) {
        accountDao.updateSessionTokens(
            steamId          = account.steamId,
            sessionId        = account.sessionId,
            steamLoginSecure = "${account.steamId}||$accessToken",
            refreshToken     = account.refreshToken,
        )
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun postForm(
        url: String,
        params: List<Pair<String, String>>,
        accessToken: String? = null,
    ): JSONObject {
        val form = FormBody.Builder().also { params.forEach { (k, v) -> it.add(k, v) } }.build()
        val request = Request.Builder().url(url).post(form).applyHeaders(accessToken).build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            if (response.code == 401) throw IllegalStateException("Access token invalid or expired")
            throw IllegalStateException("HTTP ${response.code}: $body")
        }
        return JSONObject(body.ifBlank { "{}" })
    }

    private fun Request.Builder.applyHeaders(accessToken: String?): Request.Builder = apply {
        header("User-Agent",       MOBILE_UA)
        header("Accept",           "application/json, */*")
        header("Accept-Language",  "en-US,en;q=0.9")
        header("Origin",           MOBILE_ORIGIN)
        header("Referer",          MOBILE_REFERER)
        header("X-Requested-With", "com.valvesoftware.android.steam.community")
        header("Cookie", "mobileClient=android; mobileClientVersion=777777 3.6.1; Steam_Language=english")
        if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // Extract JWT from "steamId||jwt.payload.sig" or raw JWT
    private fun String.extractJwt(): String? {
        if (isBlank()) return null
        val decoded = if (contains('%')) try { URLDecoder.decode(this, "UTF-8") } catch (_: Exception) { this } else this
        val candidate = decoded.substringAfter("||", decoded).trim()
        if (candidate.isBlank()) return null
        val parts = candidate.split('.')
        return if (parts.size == 3 && parts.none { it.isBlank() }) candidate else null
    }
}
