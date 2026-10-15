package com.healthsynceng

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object SheetsClient {

    private const val PREFS = "health_sync"
    private const val KEY_SPREADSHEET_ID = "spreadsheet_id"
    private const val KEY_SPREADSHEET_URL = "spreadsheet_url"
    const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    private const val API_BASE = "https://sheets.googleapis.com/v4/spreadsheets"
    private const val HISTORY_DAYS = 90L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient()

    private val dailyHeader = listOf(
        "Date",
        "Steps",
        "Exercise min",
        "Activity kcal",
        "Avg HR",
        "Total sleep h",
        "Distance km",
        "Awake HR",
        "Exercise HR",
        "Sleep HR",
        "Estirmated Calories Burned"
    )
    private val workoutHeader = listOf(
        "Date",
        "Start",
        "Type",
        "Duration min",
        "Avg HR",
        "Max HR"
    )

    fun hasSpreadsheet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SPREADSHEET_ID, null) != null

    fun spreadsheetUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SPREADSHEET_URL, null)

    fun accessToken(context: Context): String {
        val signedInAccount = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IOException("Connect a Google account before syncing.")
        val account: Account = signedInAccount.account
            ?: throw IOException("Could not identify the signed-in Google account.")
        return GoogleAuthUtil.getToken(context, account, "oauth2:$SHEETS_SCOPE")
    }

    fun connect(context: Context, accessToken: String): String {
        val response = requestJson(
            Request.Builder()
                .url(API_BASE)
                .post(
                    JSONObject()
                        .put("properties", JSONObject().put("title", "Health Sync"))
                        .put(
                            "sheets",
                            JSONArray()
                                .put(JSONObject().put("properties", JSONObject().put("title", "Daily")))
                                .put(JSONObject().put("properties", JSONObject().put("title", "Workouts")))
                        )
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                ),
            accessToken
        )

        val spreadsheetId = response.optString("spreadsheetId")
        val spreadsheetUrl = response.optString("spreadsheetUrl")
        if (spreadsheetId.isBlank() || spreadsheetUrl.isBlank()) {
            throw IOException("Google Sheets created a spreadsheet without returning its ID or URL.")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SPREADSHEET_ID, spreadsheetId)
            .putString(KEY_SPREADSHEET_URL, spreadsheetUrl)
            .apply()
        updateValues(
            spreadsheetId,
            accessToken,
            listOf(
                ValueRange("Daily!A1:K1", listOf(dailyHeader)),
                ValueRange("Workouts!A1:F1", listOf(workoutHeader))
            )
        )
        return spreadsheetUrl
    }

    fun syncSnapshot(context: Context, snapshot: HealthSnapshot, accessToken: String) {
        val spreadsheetId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SPREADSHEET_ID, null)
            ?: throw IOException("Google Sheets is not connected. Tap 'Connect Google Sheets' first.")
        val cutoff = LocalDate.now(ZoneId.of("Europe/Stockholm")).minusDays(HISTORY_DAYS - 1)
        val existing = getValues(spreadsheetId, accessToken)
        val changes = planChanges(existing, snapshot, cutoff)
        if (changes.isNotEmpty()) updateValues(spreadsheetId, accessToken, changes)
    }

    internal fun planChanges(existing: JSONArray, snapshot: HealthSnapshot, cutoff: LocalDate): List<ValueRange> {
        val dailyValues = existing.optJSONObject(0)?.optJSONArray("values") ?: JSONArray()
        val dailyOriginal = dailyValues.rowsAfterHeader()
        val oldHeader = dailyValues.optJSONArray(0) ?: JSONArray()
        // Match by header so old averages move to E, while resting values are discarded.
        val dailyExisting = dailyOriginal.map { row ->
            dailyHeader.map { name ->
                val aliases = when (name) {
                    "Activity kcal" -> setOf(name, "Active kcal")
                    "Total sleep h" -> setOf(name, "Sleep h")
                    "Estirmated Calories Burned" -> setOf(name, "Total kcal")
                    else -> setOf(name)
                }
                val index = (0 until oldHeader.length()).firstOrNull { oldHeader.optString(it) in aliases }
                if (index == null) "" else row.getOrNull(index) ?: ""
            }
        }
        val workoutExisting = existing.optJSONObject(1)?.optJSONArray("values")?.rowsAfterHeader().orEmpty()

        val dailyByDate = linkedMapOf<String, List<Any>>()
        val dailyUnkeyed = mutableListOf<List<Any>>()
        dailyExisting.forEach { row ->
            val date = row.firstOrNull()?.toString().orEmpty()
            val parsedDate = parseDate(date)
            if (parsedDate == null) {
                dailyUnkeyed += row
            } else if (!parsedDate.isBefore(cutoff)) {
                dailyByDate[date] = row
            }
        }
        val incoming = dailyRow(snapshot).toMutableList()
        dailyByDate[snapshot.date]?.let { saved ->
            // Missing source values must not erase previously exported data.
            incoming.indices.drop(1).forEach { index ->
                if (incoming[index] == "") incoming[index] = saved.getOrNull(index) ?: ""
            }
            val savedSteps = saved.getOrNull(1)?.toString()?.toDoubleOrNull()
            val newSteps = incoming[1].toString().toDoubleOrNull()
            if (savedSteps != null && newSteps != null && newSteps <= savedSteps + 100) {
                incoming[1] = saved[1]
            }
            val savedCalories = saved.getOrNull(10)?.toString()?.toDoubleOrNull()
            val newCalories = incoming[10].toString().toDoubleOrNull()
            if (savedCalories != null && newCalories != null && newCalories < savedCalories + 100) {
                incoming[10] = saved[10]
            }
        }
        dailyByDate[snapshot.date] = incoming
        snapshot.sleepMinutesByDate.forEach { (date, minutes) ->
            val parsed = parseDate(date)
            if (parsed != null && !parsed.isBefore(cutoff) && parsed <= LocalDate.parse(snapshot.date)) {
                val row = (dailyByDate[date] ?: listOf(date)).toMutableList()
                while (row.size < dailyHeader.size) row.add("")
                row[5] = roundToTwoDecimals(minutes / 60.0)
                dailyByDate[date] = row
            }
        }

        val workoutsByKey = linkedMapOf<String, List<Any>>()
        workoutExisting.forEach { row ->
            val date = row.firstOrNull()?.toString().orEmpty()
            val parsedDate = parseDate(date)
            if (parsedDate == null || !parsedDate.isBefore(cutoff)) {
                workoutsByKey[workoutKey(row)] = row
            }
        }
        snapshot.workouts.forEach { workout ->
            val row = workoutRow(workout)
            workoutsByKey[workoutKey(row)] = row
        }

        val dailyRows = listOf(dailyHeader) + dailyByDate.entries
            .sortedBy { it.key }
            .map { it.value } + dailyUnkeyed
        val workoutRows = listOf(workoutHeader) + workoutsByKey.values
            .sortedWith(compareBy({ it.getOrNull(0).toString() }, { it.getOrNull(1).toString() }))

        // Clear obsolete trailing export columns during migration.
        val dailyWidth = maxOf(dailyHeader.size, dailyValues.optJSONArray(0)?.length() ?: 0)
        val paddedDailyRows = padRows(dailyRows, dailyWidth)
            .padToRowCount(dailyOriginal.size + 1, dailyWidth)
        val paddedWorkoutRows = padRows(workoutRows, workoutHeader.size)
            .padToRowCount(workoutExisting.size + 1, workoutHeader.size)
        return changedCells("Daily", dailyValues, paddedDailyRows) +
            changedCells("Workouts", existing.optJSONObject(1)?.optJSONArray("values") ?: JSONArray(), paddedWorkoutRows)
    }

    private fun changedCells(
        sheet: String,
        existing: JSONArray,
        rows: List<List<Any>>
    ): List<ValueRange> = buildList {
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, value ->
                val previous = existing.optJSONArray(rowIndex)?.opt(columnIndex) ?: ""
                val oldNumber = previous.toString().toDoubleOrNull()
                val newNumber = value.toString().toDoubleOrNull()
                val equal = if (oldNumber != null && newNumber != null) oldNumber == newNumber
                    else previous.toString() == value.toString()
                if (!equal) {
                    val column = ('A'.code + columnIndex).toChar()
                    add(ValueRange("$sheet!$column${rowIndex + 1}", listOf(listOf(value))))
                }
            }
        }
    }

    private fun dailyRow(snapshot: HealthSnapshot): List<Any> = listOf(
        snapshot.date,
        snapshot.steps ?: "",
        snapshot.activeMinutes ?: "",
        snapshot.caloriesActive?.let(::roundToTwoDecimals) ?: "",
        snapshot.heartRateAverage ?: "",
        snapshot.sleepDurationMinutes?.let { roundToTwoDecimals(it / 60.0) } ?: "",
        snapshot.distanceMeters?.let { roundToTwoDecimals(it / 1000.0) } ?: "",
        snapshot.heartRateAwake ?: "",
        snapshot.heartRateExercise ?: "",
        snapshot.heartRateSleep ?: "",
        snapshot.caloriesTotal ?: ""
    )

    private fun workoutRow(workout: WorkoutSummary): List<Any> = listOf(
        workout.date,
        workout.start,
        workout.type,
        workout.durationMinutes,
        workout.averageHeartRate ?: "",
        workout.maximumHeartRate ?: ""
    )

    private fun roundToTwoDecimals(value: Double): Double = kotlin.math.round(value * 100) / 100

    private fun workoutKey(row: List<Any>): String =
        listOf(row.getOrNull(0), row.getOrNull(1), row.getOrNull(2)).joinToString("|")

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun padRows(rows: List<List<Any>>, width: Int): List<List<Any>> =
        rows.map { row -> row + List((width - row.size).coerceAtLeast(0)) { "" } }

    private fun List<List<Any>>.padToRowCount(rowCount: Int, width: Int): List<List<Any>> =
        this + List((rowCount - size).coerceAtLeast(0)) { List(width) { "" } }

    private fun JSONArray.rowsAfterHeader(): List<List<Any>> =
        (1 until length()).map { rowIndex ->
            val row = getJSONArray(rowIndex)
            (0 until row.length()).map { row.get(it) }
        }

    private fun getValues(spreadsheetId: String, accessToken: String): JSONArray {
        val url = "$API_BASE/$spreadsheetId/values:batchGet" +
            "?ranges=Daily!A:K&ranges=Workouts!A:F"
        return requestJson(Request.Builder().url(url).get(), accessToken)
            .optJSONArray("valueRanges")
            ?: throw IOException("Google Sheets returned no sheet data.")
    }

    internal data class ValueRange(val range: String, val rows: List<List<Any>>)

    private fun updateValues(
        spreadsheetId: String,
        accessToken: String,
        ranges: List<ValueRange>
    ) {
        val data = JSONArray()
        ranges.forEach { valueRange ->
            val rows = JSONArray()
            valueRange.rows.forEach { row ->
                rows.put(JSONArray().also { cells -> row.forEach(cells::put) })
            }
            data.put(
                JSONObject()
                    .put("range", valueRange.range)
                    .put("majorDimension", "ROWS")
                    .put("values", rows)
            )
        }
        requestJson(
            Request.Builder()
                .url("$API_BASE/$spreadsheetId/values:batchUpdate")
                .post(
                    JSONObject()
                        .put("valueInputOption", "RAW")
                        .put("data", data)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                ),
            accessToken
        )
    }

    private fun requestJson(requestBuilder: Request.Builder, accessToken: String): JSONObject {
        val request = requestBuilder
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { response.message }
                throw IOException("Google Sheets API failed (${response.code}): $message")
            }
            if (body.isBlank()) return JSONObject()
            return JSONObject(body)
        }
    }
}
