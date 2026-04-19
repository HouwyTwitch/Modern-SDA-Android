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
import kotlinx.coroutines.flow.first
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
    val backgroundSyncEnabled: Boolean = false,
    val syncIntervalMinutes: Int = 15,
    val autoConfirmMarket: Boolean = false,
    val autoConfirmTrades: Boolean = false,
    val notifyOnPendingConfirmations: Boolean = true,
    val pinEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val pinLength: Int = 4,
)

data class PinCredentials(
    val hash: String,
    val salt: String,
    val length: Int,
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
        val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
        val AUTO_CONFIRM_MARKET = booleanPreferencesKey("auto_confirm_market")
        val AUTO_CONFIRM_TRADES = booleanPreferencesKey("auto_confirm_trades")
        val NOTIFY_ON_PENDING = booleanPreferencesKey("notify_on_pending_confirmations")
        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_LENGTH = intPreferencesKey("pin_length")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: false,
            useDynamicColor = prefs[Keys.USE_DYNAMIC_COLOR] ?: true,
            autoRefreshCode = prefs[Keys.AUTO_REFRESH_CODE] ?: false,
            refreshIntervalSeconds = prefs[Keys.REFRESH_INTERVAL_SECONDS] ?: 30,
            copyOnClick = prefs[Keys.COPY_ON_CLICK] ?: true,
            backgroundSyncEnabled = prefs[Keys.BACKGROUND_SYNC_ENABLED] ?: false,
            syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL_MINUTES] ?: 15,
            autoConfirmMarket = prefs[Keys.AUTO_CONFIRM_MARKET] ?: false,
            autoConfirmTrades = prefs[Keys.AUTO_CONFIRM_TRADES] ?: false,
            notifyOnPendingConfirmations = prefs[Keys.NOTIFY_ON_PENDING] ?: true,
            pinEnabled = prefs[Keys.PIN_ENABLED] ?: false,
            biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: false,
            pinLength = prefs[Keys.PIN_LENGTH] ?: 4,
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

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_SYNC_ENABLED] = enabled }
    }

    suspend fun setSyncIntervalMinutes(minutes: Int) {
        dataStore.edit { it[Keys.SYNC_INTERVAL_MINUTES] = minutes.coerceIn(15, 60) }
    }

    suspend fun setAutoConfirmMarket(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONFIRM_MARKET] = enabled }
    }

    suspend fun setAutoConfirmTrades(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_CONFIRM_TRADES] = enabled }
    }

    suspend fun setNotifyOnPendingConfirmations(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_ON_PENDING] = enabled }
    }

    suspend fun savePinCredentials(hash: String, salt: String, length: Int) {
        dataStore.edit {
            it[Keys.PIN_HASH] = hash
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_LENGTH] = length
            it[Keys.PIN_ENABLED] = true
        }
    }

    suspend fun clearPinCredentials() {
        dataStore.edit {
            it.remove(Keys.PIN_HASH)
            it.remove(Keys.PIN_SALT)
            it.remove(Keys.PIN_LENGTH)
            it[Keys.PIN_ENABLED] = false
            it[Keys.BIOMETRIC_ENABLED] = false
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun getPinCredentials(): PinCredentials? {
        val prefs = dataStore.data.first()
        val hash = prefs[Keys.PIN_HASH] ?: return null
        val salt = prefs[Keys.PIN_SALT] ?: return null
        val length = prefs[Keys.PIN_LENGTH] ?: return null
        return PinCredentials(hash = hash, salt = salt, length = length)
    }
}
