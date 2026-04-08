package com.example.anchor

object AnchorPrefs {
    const val FILE_NAME = "anchor_prefs"

    /** Per-package temporary unblock after "Open app anyway" (epoch millis). */
    const val JAILBREAK_DURATION_MS = 10L * 60L * 1000L

    fun jailbreakUntilKey(packageName: String) = "jailbreak_until_$packageName"

    const val KEY_BLOCKED_APPS = "blocked_apps"
    const val KEY_IS_INSIDE_GEOFENCE = "is_inside_geofence"
    const val KEY_GEOFENCE_LAT = "geofence_lat"
    const val KEY_GEOFENCE_LNG = "geofence_lng"
    const val KEY_GEOFENCE_RADIUS = "geofence_radius"
    const val KEY_GEOFENCE_ADDRESS = "geofence_address"
    const val KEY_GEOFENCE_ACTIVE = "geofence_active"
}
