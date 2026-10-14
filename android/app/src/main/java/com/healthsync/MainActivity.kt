package com.healthsynceng

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var healthManager: HealthConnectManager
    private lateinit var statusText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val sheetsSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            updateStatus("Google Sheets connection cancelled.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val url = withContext(Dispatchers.IO) {
                    val token = SheetsClient.accessToken(applicationContext)
                    SheetsClient.connect(applicationContext, token)
                }
                updateStatus("Google Sheets connected.\n$url")
                refreshStatusDisplay()
            } catch (e: Exception) {
                updateStatus("Google Sheets connection failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        lifecycleScope.launch {
            if (healthManager.hasPermissions()) {
                updateStatus("Health Connect connected.")
            } else if (granted.isNotEmpty()) {
                updateStatus(
                    "Health Connect partially connected. Some health fields may show blank."
                )
            } else {
                updateStatus("Health Connect permissions are still off. Opening Health Connect settings...")
                openHealthConnectPermissions()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        healthManager = HealthConnectManager(this)
        statusText = findViewById(R.id.statusText)
        findViewById<ViewGroup>(R.id.contentStack).scheduleLayoutAnimation()
        findViewById<View>(R.id.contentRoot).animate()
            .alpha(1f)
            .setDuration(260)
            .start()

        refreshStatusDisplay()

        findViewById<Button>(R.id.btnConnectHealth).setOnClickListener {
            lifecycleScope.launch {
                when (healthManager.availability()) {
                    HealthConnectManager.Availability.AVAILABLE -> {
                        try {
                            if (!healthManager.hasPermissions()) {
                                healthPermissionLauncher.launch(healthManager.permissions)
                            } else {
                                updateStatus("Health Connect already connected.")
                            }
                        } catch (e: Exception) {
                            updateStatus("Health Connect failed: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                    HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                        openHealthConnectInstall()
                    }
                    HealthConnectManager.Availability.UNAVAILABLE -> {
                        updateStatus(
                            "Health Connect is not available on this phone. " +
                            "Update Android and Google Play services, then try again."
                        )
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnConnectSheets).setOnClickListener {
            val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(SheetsClient.SHEETS_SCOPE))
                .build()
            sheetsSignInLauncher.launch(GoogleSignIn.getClient(this, signInOptions).signInIntent)
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            lifecycleScope.launch {
                if (!healthManager.hasPermissions()) {
                    updateStatus("Step 1: Connect Health Connect first. Opening Health Connect settings...")
                    openHealthConnectPermissions()
                    return@launch
                }
                if (!SheetsClient.hasSpreadsheet(this@MainActivity)) {
                    updateStatus("Step 2: Connect Google Sheets first.")
                    return@launch
                }
                updateStatus("Syncing to Google Sheets...")
                try {
                    val snapshot = healthManager.readTodaySnapshot()
                    withContext(Dispatchers.IO) {
                        val token = SheetsClient.accessToken(applicationContext)
                        SheetsClient.syncSnapshot(applicationContext, snapshot, token)
                    }
                    updateStatus(
                        "Synced to Google Sheets!\n" +
                        "Steps: ${snapshot.steps ?: "--"}\n" +
                        "Resting HR: ${snapshot.heartRateResting ?: "--"} bpm\n" +
                        "Calories: ${snapshot.caloriesTotal ?: "--"} kcal\n" +
                        "Sleep: ${snapshot.sleepDurationMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "--"}\n" +
                        "Workouts: ${snapshot.workouts.size}"
                    )
                } catch (e: Exception) {
                    updateStatus("Sync failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        findViewById<Button>(R.id.btnSchedule).setOnClickListener {
            lifecycleScope.launch {
                if (!healthManager.hasPermissions()) {
                    updateStatus("Connect Health Connect before starting auto sync.")
                    openHealthConnectPermissions()
                    return@launch
                }
                if (!SheetsClient.hasSpreadsheet(this@MainActivity)) {
                    updateStatus("Connect Google Sheets before starting auto sync.")
                    return@launch
                }
                SyncWorker.schedule(this@MainActivity)
                SyncWorker.runOnce(this@MainActivity)
                requestNotificationPermissionIfNeeded()
                requestBatteryOptimizationExemption()
                updateStatus("Auto sync active. Syncing to Google Sheets now, then Android will run background sync about every 15 min.")
            }
        }
    }

    private fun updateStatus(message: String) {
        statusText.text = message
        statusText.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_in))
    }

    private fun refreshStatusDisplay() {
        val spreadsheetUrl = SheetsClient.spreadsheetUrl(this)
        lifecycleScope.launch {
            val healthAvailability = healthManager.availability()
            val hasHealth = runCatching { healthManager.hasPermissions() }.getOrDefault(false)
            statusText.text = buildString {
                appendLine("Health Connect: ${healthStatusText(healthAvailability, hasHealth)}")
                appendLine("Google Sheets: ${if (spreadsheetUrl != null) "Connected" else "Tap button below"}")
                if (spreadsheetUrl != null) appendLine(spreadsheetUrl)
                if (hasHealth && spreadsheetUrl != null) {
                    appendLine("\nReady to sync. Tap 'Sync Now' or 'Start Auto Sync'.")
                }
            }
        }
    }

    private fun openHealthConnectInstall() {
        updateStatus("Health Connect needs to be installed or updated. Opening Play Store...")
        try {
            startActivity(healthManager.installOrUpdateIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus("Open Play Store and install or update Health Connect, then try again.")
        }
    }

    private fun openHealthConnectPermissions() {
        try {
            startActivity(healthManager.managePermissionsIntent())
        } catch (e: ActivityNotFoundException) {
            updateStatus(
                "Open Health Connect settings manually:\n" +
                "Settings > Security & privacy > Privacy > Health Connect > App permissions > Health Sync"
            )
        }
    }

    private fun healthStatusText(
        availability: HealthConnectManager.Availability,
        hasPermissions: Boolean
    ): String {
        return when {
            hasPermissions -> "Connected"
            availability == HealthConnectManager.Availability.AVAILABLE -> "Tap button below"
            availability == HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED -> {
                "Install or update required"
            }
            else -> "Unavailable on this phone"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

}
