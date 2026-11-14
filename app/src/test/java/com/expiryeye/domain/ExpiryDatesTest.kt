package com.expiryeye.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ExpiryDatesTest {
    @Test fun todayTomorrowAndYesterdayUseCalendarDays() {
        val today = LocalDate.of(2026, 11, 14)
        assertEquals(0, daysUntilExpiry(today, today))
        assertEquals(1, daysUntilExpiry(today.plusDays(1), today))
        assertEquals(-1, daysUntilExpiry(today.minusDays(1), today))
    }

    @Test fun sevenDayBoundaryIsInclusive() {
        val today = LocalDate.of(2026, 12, 29)
        assertEquals(7, daysUntilExpiry(LocalDate.of(2027, 1, 5), today))
        assertEquals(8, daysUntilExpiry(LocalDate.of(2027, 1, 6), today))
    }

    @Test fun leapDayAndYearBoundaryAreHandled() {
        assertEquals(1, daysUntilExpiry(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 28)))
        assertEquals(1, daysUntilExpiry(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31)))
    }

    @Test fun remindersUseExpectedOffsets() {
        assertEquals(listOf(7, 3, 1, 0), defaultReminderOffsets)
    }
}
