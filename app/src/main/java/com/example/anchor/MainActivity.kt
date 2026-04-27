package com.example.anchor

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.getCustomerInfoWith

class MainActivity : AppCompatActivity() {

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            // ---> TRACKING INJECTED HERE
            TelemetryTracker.logEvent("permission_granted", mapOf("type" to "fine_location"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation()
            }
        } else {
            TelemetryTracker.logEvent("permission_denied", mapOf("type" to "fine_location"))
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // ---> TRACKING INJECTED HERE
            TelemetryTracker.logEvent("permission_granted", mapOf("type" to "background_location"))
        } else {
            TelemetryTracker.logEvent("permission_denied", mapOf("type" to "background_location"))
        }
    }

    private val REVENUECAT_KEY = BuildConfig.REVENUECAT_KEY;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #region agent log
        AnchorDebugLog.init(this)
        // #endregion
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // #region agent log
        findViewById<MaterialButton>(R.id.btnCopyDebugLog)?.setOnClickListener {
            val text = AnchorDebugLog.readAll()
            if (text.isBlank()) {
                Toast.makeText(this, "Debug log is empty", Toast.LENGTH_SHORT).show()
            } else {
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("anchor-debug", text))
                Toast.makeText(this, "Debug log copied (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnCopyDebugLog)?.setOnLongClickListener {
            AnchorDebugLog.clear()
            Toast.makeText(this, "Debug log cleared", Toast.LENGTH_SHORT).show()
            true
        }
        // #endregion

        TelemetryTracker.logEvent("app_opened")
        Purchases.configure(PurchasesConfiguration.Builder(this, REVENUECAT_KEY).build())
        Purchases.sharedInstance.getCustomerInfoWith(
            onError = { error ->
                // Silently fail, don't interrupt the user
                Log.e("RevenueCat", "Failed to sync: ${error.message}")
            },
            onSuccess = { customerInfo ->
                // Check the actual backend status
                val backendIsPro = customerInfo.entitlements["pro_access"]?.isActive == true

                // Update your local SharedPreferences to match the absolute truth
                val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_pro_unlocked", backendIsPro).apply()
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        if (savedInstanceState == null) {
            loadFragment(BlockedAppsFragment())
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_blocked_apps -> {
                    loadFragment(BlockedAppsFragment())
                    true
                }
                R.id.nav_geofence -> {
                    loadFragment(GeofenceFragment())
                    true
                }
                R.id.nav_ritual -> {
                    loadFragment(RitualFragment())
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H3",
            location = "MainActivity.kt:onResume",
            message = "app_foregrounded",
            data = mapOf("ts" to System.currentTimeMillis()),
            storageContext = this
        )
        // #endregion

        TelemetryTracker.registerDeviceIfNeeded(this);
        val isEnabled = isAccessibilityServiceEnabled()

        // ---> TRACKING INJECTED HERE
        TelemetryTracker.logEvent("accessibility_status_check", mapOf("is_enabled" to isEnabled.toString()))

        if (!isEnabled) {
            showAccessibilityDialog()
        }
        requestLocationPermissions()
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == AppBlockerService::class.java.name
        }
    }

    private fun showAccessibilityDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accessibility_dialog_title)
            .setMessage(R.string.accessibility_dialog_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.later, null)
            .setCancelable(false)
            .show()
    }

    private fun requestLocationPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (fineLocation != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            if (bgLocation != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocation()
            }
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bg_location_dialog_title)
                .setMessage(R.string.bg_location_dialog_message)
                .setPositiveButton(R.string.grant) { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }
}
