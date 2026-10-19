package com.example.tiktoktowhatsappstickers.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tiktoktowhatsappstickers.MainActivity
import com.example.tiktoktowhatsappstickers.R
import java.util.concurrent.TimeUnit

/**
 * Background update check. Every ~6 hours (Android decides the exact moment) the app asks GitHub for
 * update.json; if a newer versionCode is published the user gets a notification that opens the app
 * straight onto the "Update now" button. No server or Firebase needed.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val info = UpdateChecker.checkForUpdate() ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("stickerbridge_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("last_notified_version", 0) >= info.versionCode) return Result.success()
        if (notify(applicationContext, info)) prefs.edit().putInt("last_notified_version", info.versionCode).apply()
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "updates"
        const val EXTRA_OPEN_UPDATE = "open_update"
        private const val WORK_NAME = "stickerbridge-update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, info: UpdateInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = info.changelog.firstOrNull() ?: "Tap to update with one tap."
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("StickerBridge ${info.versionName} is available")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(info.changelog.joinToString("\n• ", prefix = "• ").ifBlank { text }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1001, n)
            return true
        }
    }
}
