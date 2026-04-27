package com.example.anchor

import android.Manifest
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/**
 * Periodically refreshes [AnchorPrefs.KEY_IS_INSIDE_GEOFENCE] using a fused-location read
 * while a geofence is active. Interval is the WorkManager minimum (~15 minutes).
 */
class GeofenceLocationRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H2",
            location = "GeofenceLocationRefreshWorker.kt:doWork:entry",
            message = "worker_started",
            data = mapOf("ts" to System.currentTimeMillis()),
            storageContext = applicationContext
        )
        // #endregion

        val prefs = applicationContext.getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)) {
            // #region agent log
            AnchorDebugLog.log(
                hypothesisId = "H2",
                location = "GeofenceLocationRefreshWorker.kt:doWork:noActiveFence",
                message = "worker_skipped_no_active_fence",
                storageContext = applicationContext
            )
            // #endregion
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch location", e)
            return Result.success()
        }

        if (location == null) {
            // #region agent log
            AnchorDebugLog.log(
                hypothesisId = "H5",
                location = "GeofenceLocationRefreshWorker.kt:doWork:nullLocation",
                message = "worker_got_null_location",
                storageContext = applicationContext
            )
            // #endregion
            Log.d(TAG, "No location available this cycle")
            return Result.success()
        }

        // #region agent log
        AnchorDebugLog.log(
            hypothesisId = "H5",
            location = "GeofenceLocationRefreshWorker.kt:doWork:gotLocation",
            message = "worker_got_location",
            data = mapOf(
                "locTime" to location.time,
                "ageSec" to ((System.currentTimeMillis() - location.time) / 1000),
                "accuracyM" to location.accuracy,
                "hasLat" to (location.latitude != 0.0),
                "provider" to (location.provider ?: "null")
            ),
            storageContext = applicationContext
        )
        // #endregion

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

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun fetchLocationWithFallback(
        fused: FusedLocationProviderClient
    ): Location? {
        // .await() magically suspends until the Play Services Task finishes
        return fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: fused.lastLocation.await()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "geofence_location_refresh"
        private const val TAG = "GeofenceLocWorker"
    }
}