package com.houwytwitch.modernsda.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

data class AppSettings(
    val darkTheme: Boolean = false,
    val useDynamicColor: Boolean = true,
    val autoRefreshCode: Boolean = false,
    val refreshIntervalSeconds: Int = 30,
    val copyOnClick: Boolean = true,
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.dataStore

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val AUTO_REFRESH_CODE = booleanPreferencesKey("auto_refresh_code")
        val REFRESH_INTERVAL_SECONDS = intPreferencesKey("refresh_interval_seconds")
        val COPY_ON_CLICK = booleanPreferencesKey("copy_on_click")
        val LAST_SELECTED_STEAM_ID = stringPreferencesKey("last_selected_steam_id")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: false,
            useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: true,
            autoRefreshCode = prefs[Keys.AUTO_REFRESH_CODE] ?: false,
            refreshIntervalSeconds = prefs[Keys.REFRESH_INTERVAL_SECONDS] ?: 30,
            copyOnClick = prefs[Keys.COPY_ON_CLICK] ?: true,
        )
    }

    val lastSelectedSteamId: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SELECTED_STEAM_ID]?.toLongOrNull()
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoRefreshCode(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_REFRESH_CODE] = enabled }
    }

    suspend fun setRefreshIntervalSeconds(seconds: Int) {
        dataStore.edit { it[Keys.REFRESH_INTERVAL_SECONDS] = seconds }
    }

    suspend fun setCopyOnClick(enabled: Boolean) {
        dataStore.edit { it[Keys.COPY_ON_CLICK] = enabled }
    }

    suspend fun setLastSelectedSteamId(steamId: Long?) {
        dataStore.edit {
            if (steamId != null) {
                it[Keys.LAST_SELECTED_STEAM_ID] = steamId.toString()
            } else {
                it.remove(Keys.LAST_SELECTED_STEAM_ID)
            }
        }
    }
}
