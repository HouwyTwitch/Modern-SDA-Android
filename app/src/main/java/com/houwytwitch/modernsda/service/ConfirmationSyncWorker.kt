package com.houwytwitch.modernsda.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.houwytwitch.modernsda.data.db.AccountDao
import com.houwytwitch.modernsda.data.model.ConfirmationType
import com.houwytwitch.modernsda.data.preferences.AppPreferences
import com.houwytwitch.modernsda.data.repository.ConfirmationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class ConfirmationSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountDao: AccountDao,
    private val confirmationRepository: ConfirmationRepository,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "modernsda_confirmations"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val settings = appPreferences.settings.first()
        if (!settings.backgroundSyncEnabled) return Result.success()

        val accounts = accountDao.getAllAccounts().first()
        if (accounts.isEmpty()) return Result.success()

        var totalPending = 0

        for (account in accounts) {
            try {
                val confirmations = confirmationRepository.fetchConfirmations(account)
                    .getOrNull() ?: continue

                totalPending += confirmations.size

                if (settings.autoConfirmMarket || settings.autoConfirmTrades) {
                    val toAccept = confirmations.filter { conf ->
                        when (conf.type) {
                            ConfirmationType.MARKET_LISTING -> settings.autoConfirmMarket
                            ConfirmationType.TRADE          -> settings.autoConfirmTrades
                            else -> false
                        }
                    }
                    if (toAccept.isNotEmpty()) {
                        confirmationRepository.acceptAllConfirmations(account, toAccept)
                        totalPending -= toAccept.size
                    }
                }
            } catch (_: Exception) {}
        }

        if (totalPending > 0 && settings.notifyOnPendingConfirmations) {
            showNotification(totalPending)
        }

        return Result.success()
    }

    private fun showNotification(count: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Confirmations", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Steam Confirmations")
            .setContentText("$count pending confirmation${if (count != 1) "s" else ""} waiting for review")
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
