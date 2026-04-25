package com.example.anchor

import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import android.content.Context

// The data class matching your Supabase schema
@Serializable
data class TelemetryEvent(
    val event_type: String,
    val metadata: JsonObject
)

object TelemetryTracker {
    // Replace with your actual Supabase URL and anonymous key
    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_KEY = BuildConfig.SUPABASE_ANON_KEY

    // Initialize the client once
    private val supabase = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(Postgrest)
    }

    // Fire-and-forget background scope
    private val scope = CoroutineScope(Dispatchers.IO)
    private var userId: String? = null

    // Call this from MainActivity.onCreate()
    fun registerDeviceIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("anchor_telemetry", Context.MODE_PRIVATE)
        userId = prefs.getString("user_id", null)

        if (userId == null) {
            // It's a new installation! Generate an anonymous ID.
            val newUserId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("user_id", newUserId).apply()
            userId = newUserId

            scope.launch {
                try {
                    // Create the JSON payload for the installations table
                    val installData = buildJsonObject {
                        put("user_id", JsonPrimitive(newUserId))
                        put("device_sdk_int", JsonPrimitive(android.os.Build.VERSION.SDK_INT))
                        put("ab_variant", JsonPrimitive("control"))
                    }

                    // Insert into app_installations
                    supabase.postgrest["app_installations"].insert(installData)
                    Log.d("Telemetry", "New device registered: $newUserId")
                } catch (e: Exception) {
                    Log.e("Telemetry", "Failed to register device", e)
                }
            }
        }
    }
        fun logEvent(eventType: String, metadata: Map<String, String> = emptyMap()) {
            scope.launch {
                try {
                    // Convert Map to kotlinx.serialization JsonObject
                    val jsonMetadata = buildJsonObject {
                        metadata.forEach { (key, value) ->
                            put(key, JsonPrimitive(value))
                        }
                    }

                    val event = buildJsonObject {
                        // Attach the user_id so Grafana can group events by user
                        if (userId != null) put("user_id", JsonPrimitive(userId))
                        put("event_type", JsonPrimitive(eventType))
                        put("metadata", jsonMetadata)
                    }

                    // Insert into the telemetry_events table
                    supabase.postgrest["telemetry_events"].insert(event)
                    Log.d("Telemetry", "Logged: $eventType")

                } catch (e: Exception) {
                    // Fail silently - telemetry should never crash the app
                    Log.e("Telemetry", "Failed to log event: $eventType", e)
                }
            }
        }
    }