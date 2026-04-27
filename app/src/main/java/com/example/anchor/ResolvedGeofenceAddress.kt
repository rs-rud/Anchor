package com.example.anchor

/**
 * A user-chosen place with coordinates, from Places autocomplete or current GPS + reverse geocode.
 */
data class ResolvedGeofenceAddress(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val fromGps: Boolean = false
)
