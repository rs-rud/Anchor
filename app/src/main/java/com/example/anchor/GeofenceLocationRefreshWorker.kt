package com.example.anchor

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CancellationTokenSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Periodically refreshes [AnchorPrefs.KEY_IS_INSIDE_GEOFENCE] using a fused-location read
 * while a geofence is active. Interval is the WorkManager minimum (~15 minutes).
 */
class GeofenceLocationRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)) {
            return Result.success()
        }

        val fenceLat = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_LAT, 0f).toDouble()
        val fenceLng = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_LNG, 0f).toDouble()
        val radiusMeters = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_RADIUS, GeofenceManager.DEFAULT_RADIUS)

        if (fenceLat == 0.0 && fenceLng == 0.0) {
            return Result.success()
        }

        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)

        val location = try {
            fetchLocationWithFallback(fused)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing in worker", e)
            return Result.success()
        }

        if (location == null) {
            Log.d(TAG, "No location available this cycle")
            return Result.success()
        }

        GeofenceManager.applyInsideStateFromLocation(
            applicationContext,
            fenceLat,
            fenceLng,
            radiusMeters,
            location,
            TAG
        )
        return Result.success()
    }

    private suspend fun fetchLocationWithFallback(
        fused: FusedLocationProviderClient
    ): Location? {
        val current = suspendCoroutine { cont ->
            val cts = CancellationTokenSource()
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnCompleteListener { task ->
                    val loc = if (task.isSuccessful) task.result else null
                    cont.resume(loc)
                }
        }
        if (current != null) return current

        return suspendCoroutine { cont ->
            fused.lastLocation.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "geofence_location_refresh"
        private const val TAG = "GeofenceLocWorker"
    }
}
