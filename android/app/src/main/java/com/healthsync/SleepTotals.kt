package com.healthsynceng

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal object SleepTotals {
    fun byWakeDate(sessions: List<Pair<Instant, Instant>>, now: Instant): Map<String, Long> {
        val zone = ZoneId.of("Europe/Stockholm")
        return sessions.filter { (start, end) -> end > start && end <= now }
            .groupBy { (_, end) -> end.atZone(zone).toLocalDate().toString() }
            .mapValues { (_, intervals) ->
                // Merge overlaps so duplicate records from multiple apps count once.
                val sorted = intervals.sortedBy { it.first }
                var start = sorted.first().first
                var end = sorted.first().second
                var seconds = 0L
                sorted.drop(1).forEach { (nextStart, nextEnd) ->
                    if (nextStart <= end) {
                        if (nextEnd > end) end = nextEnd
                    } else {
                        seconds += Duration.between(start, end).seconds
                        start = nextStart
                        end = nextEnd
                    }
                }
                (seconds + Duration.between(start, end).seconds) / 60
            }
    }
}
