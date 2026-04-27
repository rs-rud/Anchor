package com.example.anchor

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.getCustomerInfoWith

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    /** When true, [BottomNavigationView] selection was updated programmatically; skip duplicate fragment loads. */
    private var programTabChange = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
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
            TelemetryTracker.logEvent("permission_granted", mapOf("type" to "background_location"))
        } else {
            TelemetryTracker.logEvent("permission_denied", mapOf("type" to "background_location"))
        }
    }

    private val REVENUECAT_KEY = BuildConfig.REVENUECAT_KEY;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

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

        bottomNav = findViewById(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            if (programTabChange) {
                // Selection already applied with commitNow in [openTabFromIntentIfPresent]
                return@setOnItemSelectedListener true
            }
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

        if (savedInstanceState == null) {
            if (!openTabFromIntentIfPresent(intent)) {
                loadFragment(BlockedAppsFragment())
            }
        } else {
            openTabFromIntentIfPresent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabFromIntentIfPresent(intent)
    }

    private fun openTabFromIntentIfPresent(intent: Intent?): Boolean {
        if (intent == null) return false
        val tabId = intent.getIntExtra(EXTRA_OPEN_TAB_ITEM_ID, -1)
        if (tabId == -1) return false
        intent.removeExtra(EXTRA_OPEN_TAB_ITEM_ID)
        val fragment: Fragment = when (tabId) {
            R.id.nav_blocked_apps -> BlockedAppsFragment()
            R.id.nav_geofence -> GeofenceFragment()
            R.id.nav_ritual -> RitualFragment()
            else -> return false
        }
        // commit() is async — RitualFragment could still be resumed and re-launch the paywall before
        // the tab switch applies. commitNow() removes Ritual from the back stack before Main resumes.
        programTabChange = true
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commitNow()
            bottomNav.selectedItemId = tabId
        } finally {
            programTabChange = false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        TelemetryTracker.registerDeviceIfNeeded(this);
        val isEnabled = isAccessibilityServiceEnabled()

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

    companion object {
        /**
         * When set, MainActivity shows this bottom-nav tab (e.g. after dismissing the ritual paywall
         * so [RitualFragment] does not immediately re-launch the paywall).
         */
        const val EXTRA_OPEN_TAB_ITEM_ID = "extra_open_tab_item_id"
    }
}
