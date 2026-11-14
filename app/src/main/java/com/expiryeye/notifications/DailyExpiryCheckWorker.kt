package com.expiryeye.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.expiryeye.data.ExpiryDatabase
import com.expiryeye.domain.defaultReminderOffsets
import java.util.concurrent.TimeUnit

private const val DAILY_CHECK_NAME = "daily_expiry_recheck"
private const val KEY_REMINDER_OFFSETS = "reminder_offsets"
private val supportedOffsets = setOf(14, 7, 3, 1, 0)

class DailyExpiryCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val offsets = inputData.getIntArray(KEY_REMINDER_OFFSETS)
            ?.toList()
            ?.filter { it in supportedOffsets }
            ?.distinct()
            ?.sortedDescending()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultReminderOffsets
        return runCatching {
            val items = ExpiryDatabase.get(applicationContext).foodDao().activeNow()
            ExpiryReminderWorker.rescheduleAll(applicationContext, items, offsets)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun schedule(context: Context, offsets: List<Int>) {
            val request = PeriodicWorkRequestBuilder<DailyExpiryCheckWorker>(1, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_REMINDER_OFFSETS to offsets.toIntArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY_CHECK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}
