package com.example.anchor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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

        if (packageName == applicationContext.packageName) return
        if (ignoredPackages.contains(packageName)) return

        val geofenceActive = prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
        val isInsideGeofence = prefs.getBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false)

        Log.d(TAG, "Window changed: pkg=$packageName, fenceActive=$geofenceActive, inside=$isInsideGeofence")

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
            )
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
        launchBlockScreen(packageName)
    }

    private fun launchBlockScreen(blockedPackage: String) {
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
    }
}