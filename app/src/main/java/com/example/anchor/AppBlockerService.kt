package com.example.anchor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        // #region agent log
        AnchorDebugLog.init(this)
        AnchorDebugLog.log(
            hypothesisId = "INIT",
            location = "AppBlockerService.kt:onServiceConnected",
            message = "service_connected",
            data = mapOf("ts" to System.currentTimeMillis()),
            storageContext = this
        )
        // #endregion
        prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "AppBlockerService connected")

        // ---> TRACKING INJECTED HERE
        // Logs when the system successfully binds to your accessibility service
        TelemetryTracker.logEvent("accessibility_service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H1",
            location = "AppBlockerService.kt:onAccessibilityEvent:entry",
            message = "window_state_changed",
            data = mapOf(
                "pkg" to packageName,
                "className" to (event.className?.toString() ?: "null"),
                "eventTime" to event.eventTime,
                "isOwnPkg" to (packageName == applicationContext.packageName),
                "isIgnored" to ignoredPackages.contains(packageName)
            ),
            storageContext = this
        )
        // #endregion

        if (packageName == applicationContext.packageName) return
        if (ignoredPackages.contains(packageName)) return

        val geofenceActive = prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
        val isInsideGeofence = prefs.getBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false)

        Log.d(TAG, "Window changed: pkg=$packageName, fenceActive=$geofenceActive, inside=$isInsideGeofence")

        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H6",
            location = "AppBlockerService.kt:onAccessibilityEvent:postFenceCheck",
            message = "geofence_state_at_event",
            data = mapOf(
                "pkg" to packageName,
                "geofenceActive" to geofenceActive,
                "isInsideGeofence" to isInsideGeofence
            ),
            storageContext = this
        )
        // #endregion

        if (!geofenceActive || !isInsideGeofence) return

        val blockedApps = prefs.getStringSet(AnchorPrefs.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        val inBlockedSet = blockedApps.contains(packageName)
        val jailKey = AnchorPrefs.jailbreakUntilKey(packageName)
        var jailUntil = prefs.getLong(jailKey, 0L)
        val now = System.currentTimeMillis()
        if (jailUntil > 0 && jailUntil <= now) {
            prefs.edit().remove(jailKey).apply()
            jailUntil = 0L
        }
        val jailbreakActive = jailUntil > now

        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H1",
            location = "AppBlockerService.kt:onAccessibilityEvent",
            message = "block_decision",
            data = mapOf(
                "pkg" to packageName,
                "geofenceActive" to geofenceActive,
                "inside" to isInsideGeofence,
                "inBlockedSet" to inBlockedSet,
                "jailUntil" to jailUntil,
                "now" to now,
                "jailbreakActive" to jailbreakActive,
                "willLaunchBlock" to (inBlockedSet && !jailbreakActive)
            ),
            storageContext = this
        )
        // #endregion

        if (!inBlockedSet) return
        if (jailbreakActive) {
            Log.d(TAG, "Jailbreak window active for $packageName until $jailUntil — skip block")
            return
        }

        Log.d(TAG, "Blocked app detected: $packageName — launching BlockActivity")

        // ---> TRACKING INJECTED HERE
        // Track the actual block event.
        // Note: For production privacy, you may want to map 'packageName' to a generic category string
        // before sending it to Supabase (e.g., "social", "game") rather than the exact package.
        TelemetryTracker.logEvent(
            eventType = "app_blocked",
            metadata = mapOf(
                "package" to packageName,
                "geofence_active" to geofenceActive.toString(),
                "is_inside_geofence" to isInsideGeofence.toString()
            )
        )
        incrementAttemptCounter(packageName)
        launchBlockScreen(packageName)
    }

    private fun incrementAttemptCounter(packageName: String) {
        val today = DAY_BUCKET_FORMAT.format(Date())
        val key = AnchorPrefs.attemptsKey(packageName, today)
        val next = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, next).apply()
    }

    private fun launchBlockScreen(blockedPackage: String) {
        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H1",
            location = "AppBlockerService.kt:launchBlockScreen",
            message = "launching_block_activity",
            data = mapOf(
                "pkg" to blockedPackage,
                "ts" to System.currentTimeMillis()
            ),
            storageContext = this
        )
        // #endregion
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(BlockActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")

        // ---> TRACKING INJECTED HERE
        // Logs when the service is killed or interrupted by the OS or user
        TelemetryTracker.logEvent("accessibility_service_interrupted")
    }

    companion object {
        private const val TAG = "AppBlockerService"
        private val DAY_BUCKET_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}