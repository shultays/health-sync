package com.healthsynceng

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SheetsClientTest {
    private val header = listOf("Date", "Steps", "Exercise min", "Activity kcal", "Avg HR", "Total sleep h", "Distance km", "Awake HR", "Exercise HR", "Sleep HR")
    private val cutoff = LocalDate.parse("2026-07-18")
    private fun snapshot(date: String = "2026-10-15", steps: Long? = 150) =
        HealthSnapshot(date, steps, null, 999, null, null, null, null, null, null, emptyList())
    private fun existing(rows: List<List<Any>>, columns: List<String> = header): JSONArray = JSONArray()
        .put(JSONObject().put("values", JSONArray(listOf(columns) + rows)))
        .put(JSONObject().put("values", JSONArray(listOf(listOf("Date", "Start", "Type", "Duration min", "Avg HR", "Max HR")))))

    @Test fun exportsFractionalCaloriesAndOrdinaryHeartRate() {
        val saved = existing(listOf(listOf("2026-10-15", 150)))
        val today = snapshot().copy(caloriesActive = 1.03, heartRateAverage = 72)
        val changes = SheetsClient.planChanges(saved, today, cutoff)
        assertEquals(listOf(1.03), changes.first { it.range == "Daily!D2" }.rows.single())
        assertEquals(listOf(72), changes.first { it.range == "Daily!E2" }.rows.single())
        val tiny = SheetsClient.planChanges(saved, today.copy(caloriesActive = 0.03), cutoff)
        assertEquals(listOf(0.03), tiny.first { it.range == "Daily!D2" }.rows.single())
    }

    @Test fun migratesAverageInsteadOfRelabelingRestingHeartRate() {
        val oldHeader = listOf("Date", "Steps", "Exercise min", "Active kcal", "Resting HR", "Sleep h", "Sleep start", "Sleep end", "Distance km", "Avg HR")
        val saved = existing(listOf(listOf("2026-10-14", 150, "", 1.03, 55, 8, "23:00", "07:00", 1.2, 72)), oldHeader)
        val changes = SheetsClient.planChanges(saved, snapshot(), cutoff)
        assertEquals(listOf(72), changes.first { it.range == "Daily!E2" }.rows.single())
        assertEquals(listOf(""), changes.first { it.range == "Daily!J2" }.rows.single())
        assertEquals(listOf(1.2), changes.first { it.range == "Daily!G2" }.rows.single())
        assertFalse(changes.any { it.range == "Daily!F2" })
        assertEquals(listOf("Activity kcal"), changes.first { it.range == "Daily!D1" }.rows.single())
    }

    @Test fun historicalSleepTotalsUpdateOnlySleepCells() {
        val saved = existing(listOf(listOf("2026-10-14", 900, 20, 70, 60, 2.0, 1.2), listOf("2026-10-15", 150)))
        val today = snapshot().copy(sleepMinutesByDate = mapOf("2026-10-14" to 360, "2026-10-15" to 120))
        val changes = SheetsClient.planChanges(saved, today, cutoff)
        assertEquals(setOf("Daily!F2", "Daily!F3"), changes.map { it.range }.toSet())
        assertEquals(listOf(6.0), changes.first { it.range == "Daily!F2" }.rows.single())
    }

    @Test fun addsContextColumnsAndLeavesExistingMetricsUntouched() {
        val oldHeader = header.take(7)
        val saved = existing(listOf(listOf("2026-10-15", 150, "", "", "", "", 1.2)), oldHeader)
        val changes = SheetsClient.planChanges(saved, snapshot().copy(heartRateAwake = 65, heartRateExercise = 120, heartRateSleep = 55), cutoff)
        assertEquals(setOf("Daily!H1", "Daily!I1", "Daily!J1", "Daily!H2", "Daily!I2", "Daily!J2"), changes.map { it.range }.toSet())
        assertEquals(listOf(65), changes.first { it.range == "Daily!H2" }.rows.single())
        assertEquals(listOf(120), changes.first { it.range == "Daily!I2" }.rows.single())
        assertEquals(listOf(55), changes.first { it.range == "Daily!J2" }.rows.single())
    }

    @Test fun newDayPreservesPreviousDay() {
        val changes = SheetsClient.planChanges(existing(listOf(listOf("2026-10-14", 300))), snapshot(), cutoff)
        assertEquals(setOf("Daily!A3", "Daily!B3"), changes.map { it.range }.toSet())
    }

    @Test fun manuallyInsertedYesterdayAndMovedTodayStayUnchanged() {
        val rows = listOf(
            listOf<Any>("2026-10-14", 50, "", "", "", "", 0.01),
            listOf<Any>("2026-10-15", 50, "", "", "", "", 0.01)
        )
        val today = snapshot(steps = 50).copy(distanceMeters = 10)
        assertTrue(SheetsClient.planChanges(existing(rows), today, cutoff).isEmpty())
    }

    @Test fun unchangedSyncDoesNotWrite() {
        assertTrue(SheetsClient.planChanges(existing(listOf(listOf("2026-10-15", 150.0))), snapshot(), cutoff).isEmpty())
    }

    @Test fun stepsRequireMoreThan100AndNeverDecrease() {
        val saved = existing(listOf(listOf("2026-10-15", 150)))
        for (steps in listOf(0L, 149L, 150L, 249L, 250L)) {
            assertTrue("steps=$steps", SheetsClient.planChanges(saved, snapshot(steps = steps), cutoff).isEmpty())
        }
        val changes = SheetsClient.planChanges(saved, snapshot(steps = 251), cutoff)
        assertEquals(listOf("Daily!B2"), changes.map { it.range })
        assertEquals(251L, changes.single().rows.single().single())
    }

    @Test fun missingValuesPreserveSavedData() {
        val saved = existing(listOf(listOf("2026-10-15", 150, 20, 70, 60, 8.0)))
        assertTrue(SheetsClient.planChanges(saved, snapshot(steps = null), cutoff).isEmpty())
    }

    @Test fun legacyColumnMigrationPreservesMetricAlignmentAndOnlyRunsOnce() {
        val legacyHeader = listOf("Date", "Steps", "Exercise min", "Active kcal", "Total kcal", "Resting HR", "Sleep h", "Sleep start", "Sleep end", "Distance km")
        val oldRow = listOf<Any>("2026-10-15", 150, 20, 70, 1900, 60, 8.0, "23:00", "07:00", 1.2)
        val changes = SheetsClient.planChanges(existing(listOf(oldRow), legacyHeader), snapshot(), cutoff)
        assertEquals(listOf(""), changes.first { it.range == "Daily!E2" }.rows.single())
        assertEquals(listOf(""), changes.first { it.range == "Daily!J2" }.rows.single())
        val migrated = listOf<Any>("2026-10-15", 150, 20, 70, "", 8.0, 1.2)
        assertTrue(SheetsClient.planChanges(existing(listOf(migrated)), snapshot(), cutoff).isEmpty())
    }
}
