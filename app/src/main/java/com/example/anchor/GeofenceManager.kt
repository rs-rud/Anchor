package com.example.anchor

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun addGeofence(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
        requestId: String = GEOFENCE_ID,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        if (!hasLocationPermission()) {
            onFailure(SecurityException("Location permission not granted"))
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(lat, lng, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT or
                Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(LOITERING_DELAY_MS)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent).run {
                addOnSuccessListener {
                    Log.d(TAG, "Geofence added: $requestId at ($lat, $lng) r=${radiusMeters}m")
                    saveGeofenceToPrefs(lat, lng, radiusMeters)
                    onSuccess()
                }
                addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofence", e)
                    onFailure(e)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException adding geofence", e)
            onFailure(e)
        }
    }

    fun removeGeofence(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d(TAG, "Geofences removed")
                clearGeofencePrefs()
                onSuccess()
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences", e)
                onFailure(e)
            }
        }
    }

    fun reRegisterFromPrefs() {
        val prefs = context.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
        if (!active) return

        val lat = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_LAT, 0f).toDouble()
        val lng = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_LNG, 0f).toDouble()
        val radius = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_RADIUS, DEFAULT_RADIUS)

        if (lat == 0.0 && lng == 0.0) return

        addGeofence(lat, lng, radius)
    }

    private fun saveGeofenceToPrefs(lat: Double, lng: Double, radius: Float) {
        context.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(AnchorPrefs.KEY_GEOFENCE_LAT, lat.toFloat())
            .putFloat(AnchorPrefs.KEY_GEOFENCE_LNG, lng.toFloat())
            .putFloat(AnchorPrefs.KEY_GEOFENCE_RADIUS, radius)
            .putBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, true)
            .apply()
    }

    private fun clearGeofencePrefs() {
        context.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
            .putBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false)
            .remove(AnchorPrefs.KEY_GEOFENCE_LAT)
            .remove(AnchorPrefs.KEY_GEOFENCE_LNG)
            .remove(AnchorPrefs.KEY_GEOFENCE_RADIUS)
            .remove(AnchorPrefs.KEY_GEOFENCE_ADDRESS)
            .apply()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return fine && background
    }

    companion object {
        private const val TAG = "GeofenceManager"
        const val GEOFENCE_ID = "anchor_geofence"
        private const val LOITERING_DELAY_MS = 30_000
        const val DEFAULT_RADIUS = 200f
    }
}
