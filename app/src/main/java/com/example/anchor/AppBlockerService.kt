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

        if (blockedApps.contains(packageName)) {
            Log.d(TAG, "Blocked app detected: $packageName — launching BlockActivity")
            launchBlockScreen(packageName)
        }
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
    }

    companion object {
        private const val TAG = "AppBlockerService"
    }
}
