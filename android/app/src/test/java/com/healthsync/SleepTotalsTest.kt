package com.healthsynceng

import java.time.Instant
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SleepTotalsTest {
    private fun time(value: String) = ZonedDateTime.parse(value + "+02:00[Europe/Stockholm]").toInstant()
    private val now = time("2026-10-15T12:00")
    @Test fun splitNightBelongsToWakeDate() {
        val sessions = listOf(time("2026-10-14T23:00") to time("2026-10-15T03:00"), time("2026-10-15T05:00") to time("2026-10-15T07:00"))
        assertEquals(mapOf("2026-10-15" to 360L), SleepTotals.byWakeDate(sessions, now))
    }
    @Test fun yesterdayAndFutureEndTimesStaySeparate() {
        val sessions = listOf(time("2026-10-13T23:00") to time("2026-10-14T07:00"), time("2026-10-15T11:00") to time("2026-10-15T13:00"))
        assertEquals(mapOf("2026-10-14" to 480L), SleepTotals.byWakeDate(sessions, now))
    }
    @Test fun duplicateAndOverlappingSessionsCountOnce() {
        val session = time("2026-10-14T23:00") to time("2026-10-15T03:00")
        assertEquals(mapOf("2026-10-15" to 300L), SleepTotals.byWakeDate(listOf(session, session, time("2026-10-15T02:00") to time("2026-10-15T04:00")), now))
    }
    @Test fun wakeDateUsesStockholmNotUtc() {
        assertEquals(mapOf("2026-10-15" to 60L), SleepTotals.byWakeDate(listOf(time("2026-10-14T23:30") to time("2026-10-15T00:30")), now))
    }
    @Test fun daylightSavingUsesElapsedTime() {
        val start = ZonedDateTime.parse("2026-10-24T23:00+02:00[Europe/Stockholm]").toInstant()
        val end = ZonedDateTime.parse("2026-10-25T07:00+01:00[Europe/Stockholm]").toInstant()
        assertEquals(mapOf("2026-10-25" to 540L), SleepTotals.byWakeDate(listOf(start to end), end))
    }
    @Test fun noSessionsMeansNoValue() {
        assertTrue(SleepTotals.byWakeDate(emptyList(), now).isEmpty())
    }
}
