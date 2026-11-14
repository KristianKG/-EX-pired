package com.expiryeye.notifications

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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.expiryeye.MainActivity
import com.expiryeye.R
import com.expiryeye.data.ExpiryDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val DAILY_SUMMARY_WORK = "daily_kitchen_summary"
private const val DAILY_SUMMARY_CHANNEL = "daily_kitchen_summary"

class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val items = ExpiryDatabase.get(applicationContext).foodDao().activeNow()
        val today = LocalDate.now()
        val dueSoon = items.count { item ->
            val expiry = Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()
            !expiry.isBefore(today) && !expiry.isAfter(today.plusDays(3))
        }
        val overdue = items.count { item ->
            Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate().isBefore(today)
        }
        if (dueSoon > 0 || overdue > 0) show(dueSoon, overdue)
        Result.success()
    }.getOrElse { Result.retry() }

    private fun show(dueSoon: Int, overdue: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(DAILY_SUMMARY_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(DAILY_SUMMARY_CHANNEL, "Daily kitchen summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A morning summary of food that may need attention"
            })
        }
        val body = buildList {
            if (overdue > 0) add("$overdue expired")
            if (dueSoon > 0) add("$dueSoon expiring in the next 3 days")
        }.joinToString(" · ")
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, DAILY_SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_expiry)
            .setContentTitle("Your kitchen today")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(DAILY_SUMMARY_CHANNEL, 1, notification)
    }

    companion object {
        fun schedule(context: Context) {
            val now = java.time.ZonedDateTime.now()
            var next = now.toLocalDate().atTime(9, 0).atZone(now.zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = java.time.Duration.between(now, next).toMillis().coerceAtLeast(0)
            val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_SUMMARY_WORK, ExistingPeriodicWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY_SUMMARY_WORK)
        }
    }
}
