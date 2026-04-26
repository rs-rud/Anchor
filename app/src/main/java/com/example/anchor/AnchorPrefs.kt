package com.example.anchor

object AnchorPrefs {
    const val FILE_NAME = "anchor_prefs"

    /** Per-package temporary unblock after "Open app anyway" (epoch millis). */
    const val JAILBREAK_DURATION_MS = 10L * 60L * 1000L

    fun jailbreakUntilKey(packageName: String) = "jailbreak_until_$packageName"

    /** Per-package, per-day counter of "blocked app open" attempts. dayBucket should be `yyyy-MM-dd`. */
    fun attemptsKey(packageName: String, dayBucket: String) =
        "block_attempts_${packageName}_$dayBucket"

    const val KEY_BLOCKED_APPS = "blocked_apps"
    const val KEY_RITUAL_TYPE = "ritual_type"
    const val KEY_GOOD_APP_PACKAGE = "good_app_package"
    const val KEY_IS_INSIDE_GEOFENCE = "is_inside_geofence"
    const val KEY_GEOFENCE_LAT = "geofence_lat"
    const val KEY_GEOFENCE_LNG = "geofence_lng"
    const val KEY_GEOFENCE_RADIUS = "geofence_radius"
    const val KEY_GEOFENCE_ADDRESS = "geofence_address"
    const val KEY_GEOFENCE_ACTIVE = "geofence_active"

    const val RITUAL_BREATHING = "breathing"
    const val RITUAL_GOOD_APP = "good_app"
    const val RITUAL_SHAME = "shame"
    const val RITUAL_METRICS = "metrics"
}
