package com.expiryeye.domain

import java.time.LocalDate

/** Positive values are days remaining; zero expires today; negative values are overdue. */
fun daysUntilExpiry(expiryDate: LocalDate, today: LocalDate): Int =
    (expiryDate.toEpochDay() - today.toEpochDay()).toInt()

val defaultReminderOffsets: List<Int> = listOf(7, 3, 1, 0)
