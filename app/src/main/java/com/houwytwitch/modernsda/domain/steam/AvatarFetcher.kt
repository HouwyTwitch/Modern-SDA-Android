package com.houwytwitch.modernsda.domain.steam

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches Steam user avatar URLs from the Steam Web API.
 */
class AvatarFetcher(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {
    /**
     * Fetch avatar URL for a given Steam ID using Steam's public profile API.
     * Returns null if the fetch fails.
     */
    suspend fun fetchAvatarUrl(steamId: Long): String? = runCatching {
        val url = "https://steamcommunity.com/profiles/$steamId/?xml=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Pro)")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@runCatching null

        // Parse XML to find avatar URL
        val regex = Regex("<avatarFull><!\\[CDATA\\[(.+?)]]></avatarFull>")
        val match = regex.find(body)
        match?.groupValues?.get(1)
    }.getOrNull()
}
