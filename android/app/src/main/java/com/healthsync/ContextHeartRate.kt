package com.healthsynceng

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

internal object ContextHeartRate {
    data class Result(val awake: Int?, val exercise: Int?, val sleep: Int?)

    fun calculate(
        samples: List<Pair<Instant, Long>>,
        todaysSleep: List<Pair<Instant, Instant>>,
        allSleep: List<Pair<Instant, Instant>>,
        exerciseSessions: List<Pair<Instant, Instant>>,
        workouts: List<WorkoutSummary>,
        today: LocalDate,
        now: Instant,
    ): Result {
        val zone = ZoneId.of("Europe/Stockholm")
        fun contains(intervals: List<Pair<Instant, Instant>>, time: Instant) =
            intervals.any { (start, end) -> time >= start && time < end }
        fun average(values: List<Pair<Instant, Long>>): Int? =
            values.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()
        val valid = samples.filter { it.first <= now && it.second > 0 }.distinct()
        val awake = valid.filter { (time, _) ->
            time.atZone(zone).toLocalDate() == today &&
                !contains(allSleep, time) && !contains(exerciseSessions, time)
        }
        val sleep = valid.filter { contains(todaysSleep, it.first) }
        // Use the same workout averages exported to Workouts, weighted by duration.
        val eligible = workouts.filter { it.date == today.toString() && it.durationMinutes > 0 && it.averageHeartRate != null }
        val duration = eligible.sumOf { it.durationMinutes }
        val exercise = if (duration == 0L) null else
            (eligible.sumOf { it.averageHeartRate!!.toDouble() * it.durationMinutes } / duration).roundToInt()
        return Result(average(awake), exercise, average(sleep))
    }
}
