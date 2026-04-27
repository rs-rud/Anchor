package com.example.anchor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-mode NDJSON: posts to a public HTTPS endpoint (phone can reach on Wi‑Fi/cellular),
 * mirrors to Logcat, and appends to an on-device file. Emulator hosts can also use
 * 127.0.0.1:7897 ingest if you replace ENDPOINT for local only.
 */
object AnchorDebugLog {
    private const val SESSION = "4ca874"
    private const val TAG = "4ca874"
    private const val ENDPOINT =
        "https://webhook.site/2438f4fe-fa4b-4768-a63f-2145869ae649"
    private const val LOG_FILE_NAME = "anchor-debug-4ca874.log"

    @Volatile
    private var appContext: Context? = null

    // #region agent log
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }
    // #endregion

    // #region agent log
    fun logFile(): File? {
        val ctx = appContext ?: return null
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, LOG_FILE_NAME)
    }
    // #endregion

    // #region agent log
    fun readAll(): String {
        val f = logFile() ?: return ""
        if (!f.exists()) return ""
        return try { f.readText() } catch (_: Exception) { "" }
    }
    // #endregion

    // #region agent log
    fun clear() {
        try { logFile()?.delete() } catch (_: Exception) { /* ignore */ }
    }
    // #endregion

    // #region agent log
    /**
     * @param storageContext When non-null (e.g. receiver/worker), ensures file logging works even if
     * [MainActivity] never ran in this process (cold start for background work only).
     */
    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "bypass-v1",
        storageContext: Context? = null
    ) {
        storageContext?.applicationContext?.let { c ->
            if (appContext == null) appContext = c
        }
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
        val line = payload.toString()
        Log.i(TAG, line)
        appendToFile(line)
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
                conn.outputStream.use { it.write(line.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.use { }
                conn.disconnect()
            } catch (_: Exception) { /* host unreachable on device is OK */ }
        }.start()
    }
    // #endregion

    // #region agent log
    private fun appendToFile(line: String) {
        val f = logFile() ?: return
        try {
            f.parentFile?.mkdirs()
            FileWriter(f, true).use { it.write(line + "\n") }
        } catch (_: Exception) { /* swallow */ }
    }
    // #endregion
}
