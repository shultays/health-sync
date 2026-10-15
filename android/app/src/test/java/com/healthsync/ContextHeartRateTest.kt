package com.healthsynceng

import java.time.LocalDate
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ContextHeartRateTest {
    private val day = LocalDate.parse("2026-10-15")
    private fun time(value: String) = Instant.parse(value)
    private val now = time("2026-10-15T18:00:00Z")
    private val sleep = listOf(time("2026-10-14T21:00:00Z") to time("2026-10-15T05:00:00Z"))
    private val exercise = listOf(time("2026-10-15T08:00:00Z") to time("2026-10-15T09:00:00Z"))
    private fun calculate(samples: List<Pair<Instant, Long>>, workouts: List<WorkoutSummary> = emptyList()) =
        ContextHeartRate.calculate(samples, sleep, sleep, exercise, workouts, day, now)

    @Test fun awakeExcludesSleepAndExerciseAndSleepIncludesPreviousEvening() {
        val result = calculate(listOf(
            time("2026-10-14T21:30:00Z") to 50L,
            time("2026-10-15T04:00:00Z") to 60L,
            time("2026-10-15T08:30:00Z") to 130L,
            time("2026-10-15T10:00:00Z") to 70L,
        ))
        assertEquals(70, result.awake)
        assertEquals(55, result.sleep)
        assertNull(result.exercise)
    }
    @Test fun exerciseWeightsAvailableWorkoutAveragesByDuration() {
        val workouts = listOf(
            WorkoutSummary(day.toString(), "10:00", "Walk", 10, 100, 120),
            WorkoutSummary(day.toString(), "11:00", "Run", 30, 140, 160),
            WorkoutSummary(day.toString(), "12:00", "Walk", 90, null, null),
            WorkoutSummary("2026-10-14", "12:00", "Run", 60, 180, 190),
        )
        assertEquals(130, calculate(emptyList(), workouts).exercise)
    }
    @Test fun missingReadingsAreBlank() {
        assertEquals(ContextHeartRate.Result(null, null, null), calculate(emptyList()))
    }
    @Test fun boundariesAreStartInclusiveAndEndExclusive() {
        val result = calculate(listOf(
            time("2026-10-15T05:00:00Z") to 60L,
            time("2026-10-15T08:00:00Z") to 120L,
            time("2026-10-15T09:00:00Z") to 80L,
        ))
        assertEquals(70, result.awake)
        assertNull(result.sleep)
    }
    @Test fun duplicateReadingsDoNotSkewAverages() {
        val reading = time("2026-10-15T10:00:00Z") to 60L
        val result = calculate(listOf(reading, reading, time("2026-10-15T11:00:00Z") to 80L))
        assertEquals(70, result.awake)
    }
}
