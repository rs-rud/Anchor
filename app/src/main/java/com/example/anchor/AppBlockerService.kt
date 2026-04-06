package com.example.anchor // Change to your actual package name

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.util.Log

class AppBlockerService : AccessibilityService() {

    // Hardcoded for testing. Later, this will be populated by your Geofence logic.
    private val blockedApps = listOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.twitter.android"
    )

    // Flag to toggle blocking on/off (controlled by your geofence later)
    private var isBlockingActive = true

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBlockingActive || event == null) return

        // We only care when the window state changes (an app comes to the foreground)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            Log.d("AppBlocker", "App launched: $packageName")

            if (blockedApps.contains(packageName)) {
                Log.d("AppBlocker", "Blocked app detected! Executing block.")
                executeBlock()
            }
        }
    }

    private fun executeBlock() {
        // Option 1: The "Kick to Home Screen" method (Simple & requires no extra permissions)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)

        /* // Option 2: The "Launch Block Screen" method (Uncomment to use)
        // You'll need to create a BlockActivity class first!
        val blockIntent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("BLOCKED_MESSAGE", "You're in the geofence. Get back to work!")
        }
        startActivity(blockIntent)
        */
    }

    override fun onInterrupt() {
        // Called if the system interrupts the service. Usually left blank.
        Log.d("AppBlocker", "Service Interrupted")
    }
}