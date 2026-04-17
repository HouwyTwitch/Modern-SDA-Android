package com.houwytwitch.modernsda.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = appPreferences.settings.first()
                if (settings.backgroundSyncEnabled) {
                    syncScheduler.schedule(settings.syncIntervalMinutes.toLong())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
