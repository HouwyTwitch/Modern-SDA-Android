package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.houwytwitch.modernsda.data.model.Confirmation
import com.houwytwitch.modernsda.data.model.ConfirmationResult
import com.houwytwitch.modernsda.data.model.ConfirmationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Steam mobile confirmation API client.
 *
 * Handles fetching and acting on Steam trade/market confirmations
 * using the mobile confirmation endpoint.
 */
class SteamConfirmations(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    companion object {
        private const val TAG = "SteamConf"
        private const val BASE_URL = "https://steamcommunity.com"
        private const val MOBILECONF_URL = "$BASE_URL/mobileconf"
    }

    /**
     * Fetch pending confirmations for an account.
     */
    suspend fun fetchConfirmations(
        steamId: Long,
        identitySecret: String,
        deviceId: String,
        sessionId: String,
        steamLoginSecure: String,
    ): Result<List<Confirmation>> = withContext(Dispatchers.IO) { runCatching {
        val time = getSteamTime()
        val params = buildConfirmationParams(identitySecret, deviceId, steamId, time, "conf")
        val cookie = buildCookieHeader(steamId, sessionId, steamLoginSecure)

        val request = Request.Builder()
            .url("$MOBILECONF_URL/getlist?$params")
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Pro) AppleWebKit/537.36")
            .header("X-Requested-With", "com.valvesoftware.android.steam.community")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        Log.d(TAG, "[getlist] ${response.code} body=${body.take(500)}")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }

        val confirmationResponse = gson.fromJson(body, ConfirmationListResponse::class.java)
        Log.d(TAG, "[getlist] success=${confirmationResponse.success} needsAuth=${confirmationResponse.needsAuth} conf.size=${confirmationResponse.conf?.size}")

        if (!confirmationResponse.success) {
            // Session may have expired
            if (confirmationResponse.needsAuth == true) {
                throw SessionExpiredException("Session expired for account $steamId")
            }
            throw Exception("Failed to fetch confirmations: $body")
        }

        confirmationResponse.conf?.map { it.toDomain() } ?: emptyList()
    } }

    /**
     * Accept or decline a single confirmation.
     */
    suspend fun respondToConfirmation(
        steamId: Long,
        identitySecret: String,
        deviceId: String,
        sessionId: String,
        steamLoginSecure: String,
        confirmation: Confirmation,
        accept: Boolean,
    ): ConfirmationResult = withContext(Dispatchers.IO) {
        try {
            val time = getSteamTime()
            val tag = if (accept) "allow" else "cancel"
            val params = buildConfirmationParams(identitySecret, deviceId, steamId, time, tag)
            val cookie = buildCookieHeader(steamId, sessionId, steamLoginSecure)

            val url = "$MOBILECONF_URL/ajaxop?op=${if (accept) "allow" else "cancel"}" +
                "&$params&cid=${confirmation.id}&ck=${confirmation.nonce}"

            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Pro) AppleWebKit/537.36")
                .header("X-Requested-With", "com.valvesoftware.android.steam.community")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val result = gson.fromJson(body, ConfirmationActionResponse::class.java)
            if (result.success) {
                ConfirmationResult.Success
            } else {
                ConfirmationResult.Error(result.message ?: "Action failed")
            }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            ConfirmationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Accept all pending confirmations at once.
     */
    suspend fun acceptAllConfirmations(
        steamId: Long,
        identitySecret: String,
        deviceId: String,
        sessionId: String,
        steamLoginSecure: String,
        confirmations: List<Confirmation>,
    ): ConfirmationResult {
        if (confirmations.isEmpty()) return ConfirmationResult.Success

        return withContext(Dispatchers.IO) {
            try {
                val time = getSteamTime()
                val params = buildConfirmationParams(identitySecret, deviceId, steamId, time, "allow")
                val cookie = buildCookieHeader(steamId, sessionId, steamLoginSecure)

                val cidParams = confirmations.joinToString("&") { "cid[]=${it.id}" }
                val ckParams = confirmations.joinToString("&") { "ck[]=${it.nonce}" }

                val url = "$MOBILECONF_URL/multiajaxop?op=allow&$params&$cidParams&$ckParams"

                val request = Request.Builder()
                    .url(url)
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Pro) AppleWebKit/537.36")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")
                val result = gson.fromJson(body, ConfirmationActionResponse::class.java)

                if (result.success) ConfirmationResult.Success
                else ConfirmationResult.Error(result.message ?: "Failed")
            } catch (e: Exception) {
                ConfirmationResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun buildConfirmationParams(
        identitySecret: String,
        deviceId: String,
        steamId: Long,
        time: Long,
        tag: String,
    ): String {
        val key = Base64.decode(identitySecret, Base64.DEFAULT)
        val tagBytes = tag.toByteArray(Charsets.UTF_8)

        val msg = ByteBuffer.allocate(8 + tagBytes.size)
            .putLong(time)
            .put(tagBytes)
            .array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val b64hash = Base64.encodeToString(hash, Base64.NO_WRAP)
            .replace("+", "%2B")
            .replace("/", "%2F")
            .replace("=", "%3D")

        // p = device_id (fixed), k = HMAC confirmation key
        return "p=$deviceId&a=$steamId&k=$b64hash&t=$time&m=react&tag=$tag"
    }

    private fun buildCookieHeader(steamId: Long, sessionId: String, steamLoginSecure: String): String {
        return "sessionid=$sessionId; steamLoginSecure=$steamLoginSecure; steamMachineAuth$steamId=1"
    }

    private fun getSteamTime(): Long = System.currentTimeMillis() / 1000

    // Response data classes
    private data class ConfirmationListResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("needauth") val needsAuth: Boolean?,
        @SerializedName("conf") val conf: List<ConfirmationItem>?,
    )

    private data class ConfirmationItem(
        @SerializedName("id") val id: String,
        @SerializedName("nonce") val nonce: String,
        @SerializedName("creator_id") val creatorId: Long,
        @SerializedName("type") val type: Int,
        @SerializedName("type_name") val typeName: String,
        @SerializedName("headline") val headline: String,
        @SerializedName("summary") val summary: List<String>?,
        @SerializedName("icon") val icon: String?,
    ) {
        fun toDomain() = Confirmation(
            id = id,
            nonce = nonce,
            creatorId = creatorId,
            type = ConfirmationType.fromInt(type),
            headline = headline,
            summary = summary?.firstOrNull() ?: typeName,
            icon = icon ?: "",
        )
    }

    private data class ConfirmationActionResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("message") val message: String?,
    )
}

class SessionExpiredException(message: String) : Exception(message)
