package com.healthsynceng

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass

data class HealthSnapshot(
    val date: String,
    val steps: Long?,
    val caloriesActive: Long?,
    val caloriesTotal: Long?,
    val heartRateResting: Int?,
    val distanceMeters: Long?,
    val activeMinutes: Long?,
    val sleepDurationMinutes: Long?,
    val sleepStart: String?,
    val sleepEnd: String?,
    val workouts: List<WorkoutSummary>
)

data class WorkoutSummary(
    val date: String,
    val start: String,
    val type: String,
    val durationMinutes: Long,
    val averageHeartRate: Int?,
    val maximumHeartRate: Int?
)

class HealthConnectManager(private val context: Context) {

    enum class Availability {
        AVAILABLE,
        INSTALL_OR_UPDATE_REQUIRED,
        UNAVAILABLE
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    private val supportedRecordTypes: List<KClass<out Record>> = listOf(
        ActiveCaloriesBurnedRecord::class,
        DistanceRecord::class,
        ExerciseSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
    )

    val permissions = supportedRecordTypes
        .map { HealthPermission.getReadPermission(it) }
        .toSet() + setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun availability(): Availability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Availability.INSTALL_OR_UPDATE_REQUIRED
            }
            else -> Availability.UNAVAILABLE
        }
    }

    fun installOrUpdateIntent(): Intent {
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.apps.healthdata")
        ).apply {
            setPackage("com.android.vending")
        }
    }

    fun managePermissionsIntent(): Intent {
        return Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    suspend fun grantedPermissions(): Set<String> {
        if (availability() != Availability.AVAILABLE) return emptySet()
        return client.permissionController.getGrantedPermissions()
    }

    suspend fun hasPermissions(): Boolean {
        if (availability() != Availability.AVAILABLE) return false
        return grantedPermissions().containsAll(requiredPermissions)
    }

    suspend fun readTodaySnapshot(): HealthSnapshot {
        check(availability() == Availability.AVAILABLE) {
            "Health Connect is not available. Install or update Health Connect first."
        }

        val zone = ZoneId.of("Europe/Stockholm")
        val today = LocalDate.now(zone)
        val startOfDay = today.atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val todayRange = TimeRangeFilter.between(startOfDay, now)

        // Sleep: look back 24h to catch last night
        val sleepRange = TimeRangeFilter.between(
            now.minusSeconds(86400),
            now
        )

        val sleep = readOptional { readSleep(sleepRange) }
        val summary = readOptional { readDailySummary(todayRange) }
        val historyDays = if (
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions()
        ) WORKOUT_HISTORY_DAYS else MIN_WORKOUT_HISTORY_DAYS
        val workoutRange = TimeRangeFilter.between(
            now.minusSeconds(historyDays * 24 * 60 * 60),
            now
        )
        val workouts = readWorkouts(workoutRange, zone)

        return HealthSnapshot(
            date = today.toString(),
            steps = summary?.steps,
            caloriesActive = summary?.caloriesActive,
            caloriesTotal = summary?.caloriesTotal,
            heartRateResting = summary?.heartRateResting,
            distanceMeters = summary?.distanceMeters,
            activeMinutes = summary?.exerciseMinutes,
            sleepDurationMinutes = sleep?.durationMinutes ?: summary?.sleepDurationMinutes,
            sleepStart = sleep?.start,
            sleepEnd = sleep?.end,
            workouts = workouts
        )
    }

    private suspend fun <T> readOptional(block: suspend () -> T?): T? {
        return try {
            block()
        } catch (e: SecurityException) {
            null
        }
    }

    data class DailySummary(
        val steps: Long?,
        val caloriesActive: Long?,
        val caloriesTotal: Long?,
        val heartRateResting: Int?,
        val distanceMeters: Long?,
        val exerciseMinutes: Long?,
        val sleepDurationMinutes: Long?,
        val selectedOrigin: String?,
        val dataOrigins: List<String>,
        val allSourcesSteps: Long?,
        val allSourcesDistanceMeters: Long?,
        val allSourcesCaloriesTotal: Long?
    )

    private suspend fun readDailySummary(range: TimeRangeFilter): DailySummary {
        val allSourcesResult = aggregateDailyMetrics(range, emptySet())
        val selectedOrigin = chooseSummaryOrigin(range, allSourcesResult.dataOrigins)
        val result = if (selectedOrigin != null) {
            aggregateDailyMetrics(range, setOf(DataOrigin(selectedOrigin)))
        } else {
            allSourcesResult
        }

        return DailySummary(
            steps = result[StepsRecord.COUNT_TOTAL],
            caloriesActive = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                ?.inKilocalories
                ?.toLong(),
            caloriesTotal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories
                ?.toLong(),
            heartRateResting = result[RestingHeartRateRecord.BPM_AVG]?.toInt(),
            distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toLong(),
            exerciseMinutes = result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]
                ?.toMinutes(),
            sleepDurationMinutes = result[SleepSessionRecord.SLEEP_DURATION_TOTAL]
                ?.toMinutes(),
            selectedOrigin = selectedOrigin,
            dataOrigins = result.dataOrigins
                .map { it.packageName }
                .sorted(),
            allSourcesSteps = allSourcesResult[StepsRecord.COUNT_TOTAL],
            allSourcesDistanceMeters = allSourcesResult[DistanceRecord.DISTANCE_TOTAL]
                ?.inMeters
                ?.toLong(),
            allSourcesCaloriesTotal = allSourcesResult[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                ?.inKilocalories
                ?.toLong()
        )
    }

    private suspend fun aggregateDailyMetrics(
        range: TimeRangeFilter,
        dataOrigins: Set<DataOrigin>
    ) = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    HeartRateRecord.BPM_AVG,
                    RestingHeartRateRecord.BPM_AVG,
                    DistanceRecord.DISTANCE_TOTAL,
                    ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                    SleepSessionRecord.SLEEP_DURATION_TOTAL,
                ),
                timeRangeFilter = range,
                dataOriginFilter = dataOrigins,
            )
        )

    private suspend fun chooseSummaryOrigin(
        range: TimeRangeFilter,
        availableOrigins: Set<DataOrigin>
    ): String? {
        val availablePackages = availableOrigins.map { it.packageName }.toSet()
        val preferred = PREFERRED_DAILY_SUMMARY_ORIGINS.firstOrNull { it in availablePackages }
        if (preferred != null && originHasSteps(range, preferred)) return preferred

        val originsWithSteps = availablePackages.filter { originHasSteps(range, it) }
        return if (originsWithSteps.size == 1) originsWithSteps.first() else null
    }

    private suspend fun originHasSteps(range: TimeRangeFilter, packageName: String): Boolean {
        val result = aggregateDailyMetrics(range, setOf(DataOrigin(packageName)))
        return (result[StepsRecord.COUNT_TOTAL] ?: 0L) > 0L
    }

    private suspend fun readWorkouts(
        range: TimeRangeFilter,
        zone: ZoneId
    ): List<WorkoutSummary> {
        val sessions = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = range,
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
            )
            sessions += response.records
            pageToken = response.pageToken
        } while (pageToken != null)

        return sessions.map { session ->
            val workoutRange = TimeRangeFilter.between(session.startTime, session.endTime)
            val heartRate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                    timeRangeFilter = workoutRange,
                )
            )
            val start = ZonedDateTime.ofInstant(session.startTime, zone)
            WorkoutSummary(
                date = start.toLocalDate().toString(),
                start = start.toLocalTime().withSecond(0).withNano(0).toString(),
                type = exerciseTypeName(session.exerciseType),
                durationMinutes = Duration.between(session.startTime, session.endTime).toMinutes(),
                averageHeartRate = heartRate[HeartRateRecord.BPM_AVG]?.toInt(),
                maximumHeartRate = heartRate[HeartRateRecord.BPM_MAX]?.toInt()
            )
        }
    }

    private fun exerciseTypeName(type: Int): String {
        if (type == ExerciseSessionRecord.EXERCISE_TYPE_YOGA) {
            return "Nintendo Switch Exercises"
        }
        val constant = ExerciseSessionRecord::class.java.fields.firstOrNull { field ->
            field.name.startsWith("EXERCISE_TYPE_") &&
                runCatching { field.getInt(null) == type }.getOrDefault(false)
        }?.name ?: return "Exercise"
        return constant.removePrefix("EXERCISE_TYPE_")
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }

    data class SleepData(
        val durationMinutes: Long,
        val start: String,
        val end: String,
    )

    private suspend fun readSleep(range: TimeRangeFilter): SleepData? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = range,
                ascendingOrder = true,
            )
        ).records
        val session = records.maxByOrNull { it.endTime } ?: return null

        val durationMinutes = (session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60000
        val zone = ZoneId.of("Europe/Stockholm")
        val localStart = ZonedDateTime.ofInstant(session.startTime, zone)
        val localEnd = ZonedDateTime.ofInstant(session.endTime, zone)

        return SleepData(
            durationMinutes = durationMinutes,
            start = localStart.toString(),
            end = localEnd.toString(),
        )
    }

    companion object {
        private const val WORKOUT_HISTORY_DAYS = 90L
        private const val MIN_WORKOUT_HISTORY_DAYS = 30L
        private const val PAGE_SIZE = 500
        private val PREFERRED_DAILY_SUMMARY_ORIGINS = listOf(
            "com.fitbit.FitbitMobile",
            "com.google.android.apps.fitness",
            "com.google.android.apps.healthdata",
        )
    }
}
