package com.example.anchor

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-mode NDJSON: posts to ingest (emulator: 10.0.2.2 = host) and mirrors to Logcat.
 */
object AnchorDebugLog {
    private const val SESSION = "4ca874"
    private const val TAG = "4ca874"
    private const val ENDPOINT =
        "http://10.0.2.2:7897/ingest/39123c9c-e688-4fbe-ac10-1e9295e81d33"

    // #region agent log
    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "bypass-v1"
    ) {
        val payload = JSONObject().apply {
            put("sessionId", SESSION)
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            put("runId", runId)
            data.forEach { (k, v) ->
                when (v) {
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v?.toString() ?: "null")
                }
            }
        }
        Log.i(TAG, payload.toString())
        Thread {
            try {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Debug-Session-Id", SESSION)
                    doOutput = true
                    connectTimeout = 2000
                    readTimeout = 2000
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                conn.inputStream.use { }
                conn.disconnect()
            } catch (_: Exception) { /* host unreachable on device is OK */ }
        }.start()
    }
    // #endregion
}
