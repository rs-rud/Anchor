package com.example.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            TelemetryTracker.logEvent("geofence_error", mapOf("error_code" to geofencingEvent.errorCode.toString()))
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val prefs = context.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.d(TAG, "Entered geofence — blocking activated")
                prefs.edit().putBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, true).apply()

                // ---> TRACKING INJECTED HERE
                TelemetryTracker.logEvent("geofence_triggered", mapOf("transition" to "ENTER"))
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                // Dwell means they entered and stayed for a specified duration
                Log.d(TAG, "Dwelling in geofence — blocking activated")
                prefs.edit().putBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, true).apply()

                // ---> TRACKING INJECTED HERE
                TelemetryTracker.logEvent("geofence_triggered", mapOf("transition" to "DWELL"))
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.d(TAG, "Exited geofence — blocking deactivated")
                prefs.edit().putBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false).apply()

                // ---> TRACKING INJECTED HERE
                TelemetryTracker.logEvent("geofence_triggered", mapOf("transition" to "EXIT"))
            }
            else -> {
                Log.w(TAG, "Unknown geofence transition type: $transition")

                // ---> TRACKING INJECTED HERE
                TelemetryTracker.logEvent("geofence_triggered", mapOf("transition" to "UNKNOWN"))
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}