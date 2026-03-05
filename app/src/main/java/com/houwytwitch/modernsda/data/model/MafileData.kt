package com.houwytwitch.modernsda.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a .mafile imported from Steam Desktop Authenticator.
 * Contains shared/identity secrets and session tokens.
 */
data class MafileData(
    @SerializedName("shared_secret") val sharedSecret: String,
    @SerializedName("serial_number") val serialNumber: String = "",
    @SerializedName("revocation_code") val revocationCode: String = "",
    @SerializedName("uri") val uri: String = "",
    @SerializedName("server_time") val serverTime: Long = 0L,
    @SerializedName("account_name") val accountName: String,
    @SerializedName("token_gid") val tokenGid: String = "",
    @SerializedName("identity_secret") val identitySecret: String,
    @SerializedName("secret_1") val secret1: String = "",
    @SerializedName("status") val status: Int = 1,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fully_enrolled") val fullyEnrolled: Boolean = true,
    @SerializedName("Session") val session: MafileSession? = null,
)

data class MafileSession(
    @SerializedName("SessionID") val sessionId: String = "",
    @SerializedName("SteamLoginSecure") val steamLoginSecure: String = "",
    @SerializedName("WebCookie") val webCookie: String = "",
    @SerializedName("OAuthToken") val oAuthToken: String = "",
    @SerializedName("SteamID") val steamId: Long = 0L,
)
