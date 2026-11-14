package com.expiryeye.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expiryeye.data.ExpiryDatabase
import com.expiryeye.data.FoodItem
import com.expiryeye.domain.defaultReminderOffsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "expiry_reminders"
private const val KEY_ITEM_ID = "item_id"
private const val KEY_OFFSET = "days_before"
private val reminderOptions = listOf(14, 7, 3, 1, 0)

class ExpiryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1)
        val offset = inputData.getInt(KEY_OFFSET, -1)
        if (itemId < 0 || offset !in 0..365) return Result.failure()
        val item = ExpiryDatabase.get(applicationContext).foodDao().find(itemId) ?: return Result.success()
        if (item.consumedAt != null) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        show(item, offset)
        return Result.success()
    }

    private fun show(item: FoodItem, days: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Food expiration reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Reminders for food approaching its expiration date" })
        }
        val title = if (days == 0) "${item.name} expires today" else "${item.name} is expiring soon"
        val body = when (days) { 0 -> "Check the date and decide whether to use it today."; 1 -> "${item.name} expires tomorrow. Consider using it soon."; else -> "${item.name} expires in $days days. Consider planning to use it." }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(com.expiryeye.R.drawable.ic_stat_expiry)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify("expiry-${item.id}-$days", item.id.toInt() + days, notification)
    }

    companion object {
        fun schedule(context: Context, item: FoodItem, offsets: List<Int> = defaultReminderOffsets) {
            cancel(context, item.id)
            if (item.consumedAt != null) return
            val manager = WorkManager.getInstance(context)
            val expiry = Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()
            offsets.distinct().filter { it in reminderOptions }.forEach { offset ->
                val notifyDate = expiry.minusDays(offset.toLong())
                if (notifyDate.isBefore(LocalDate.now())) return@forEach
                val trigger = notifyDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val delay = (trigger - System.currentTimeMillis()).coerceAtLeast(0)
                val request = OneTimeWorkRequestBuilder<ExpiryReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_ITEM_ID to item.id, KEY_OFFSET to offset)).build()
                manager.enqueueUniqueWork("expiry-${item.id}-$offset", ExistingWorkPolicy.REPLACE, request)
            }
        }

        fun cancel(context: Context, itemId: Long) {
            reminderOptions.forEach { WorkManager.getInstance(context).cancelUniqueWork("expiry-$itemId-$it") }
        }

        fun rescheduleAll(context: Context, items: List<FoodItem>, offsets: List<Int> = defaultReminderOffsets) {
            items.forEach { schedule(context, it, offsets) }
        }
    }
}
