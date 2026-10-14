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
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

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
        "Active kcal",
        "Total kcal",
        "Resting HR",
        "Sleep h",
        "Sleep start",
        "Sleep end",
        "Distance km"
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
                ValueRange("Daily!A1:J1", listOf(dailyHeader)),
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
        val dailyExisting = existing.optJSONArray(0)?.rowsAfterHeader().orEmpty()
        val workoutExisting = existing.optJSONArray(1)?.rowsAfterHeader().orEmpty()

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
        dailyByDate[snapshot.date] = dailyRow(snapshot)

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

        val paddedDailyRows = padRows(dailyRows, dailyHeader.size)
            .padToRowCount(dailyExisting.size + 1, dailyHeader.size)
        val paddedWorkoutRows = padRows(workoutRows, workoutHeader.size)
            .padToRowCount(workoutExisting.size + 1, workoutHeader.size)
        updateValues(
            spreadsheetId,
            accessToken,
            listOf(
                ValueRange("Daily!A1:J${paddedDailyRows.size}", paddedDailyRows),
                ValueRange("Workouts!A1:F${paddedWorkoutRows.size}", paddedWorkoutRows)
            )
        )
    }

    private fun dailyRow(snapshot: HealthSnapshot): List<Any> = listOf(
        snapshot.date,
        snapshot.steps ?: "",
        snapshot.activeMinutes ?: "",
        snapshot.caloriesActive ?: "",
        snapshot.caloriesTotal ?: "",
        snapshot.heartRateResting ?: "",
        snapshot.sleepDurationMinutes?.let { roundToOneDecimal(it / 60.0) } ?: "",
        snapshot.sleepStart?.let(::stockholmTime).orEmpty(),
        snapshot.sleepEnd?.let(::stockholmTime).orEmpty(),
        snapshot.distanceMeters?.let { roundToTwoDecimals(it / 1000.0) } ?: ""
    )

    private fun workoutRow(workout: WorkoutSummary): List<Any> = listOf(
        workout.date,
        workout.start,
        workout.type,
        workout.durationMinutes,
        workout.averageHeartRate ?: "",
        workout.maximumHeartRate ?: ""
    )

    private fun stockholmTime(value: String): String = try {
        ZonedDateTime.parse(value).withZoneSameInstant(ZoneId.of("Europe/Stockholm"))
            .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
    } catch (_: DateTimeParseException) {
        ""
    }

    private fun roundToOneDecimal(value: Double): Double = kotlin.math.round(value * 10) / 10

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
            "?ranges=Daily!A:J&ranges=Workouts!A:F"
        return requestJson(Request.Builder().url(url).get(), accessToken)
            .optJSONArray("valueRanges")
            ?: throw IOException("Google Sheets returned no sheet data.")
    }

    private data class ValueRange(val range: String, val rows: List<List<Any>>)

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
