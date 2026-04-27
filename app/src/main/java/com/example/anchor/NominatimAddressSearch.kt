package com.example.anchor

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class NominatimAddressResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
) {
    override fun toString(): String = displayName
}

/**
 * Searches for address suggestions via the public Nominatim (OpenStreetMap) API.
 * Requests are debounced by the caller; do not call faster than one per second (usage policy).
 */
object NominatimAddressSearch {

    private const val BASE =
        "https://nominatim.openstreetmap.org/search?format=json&limit=8&addressdetails=0"

    fun search(
        query: String,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): List<NominatimAddressResult> {
        val q = query.trim()
        if (q.length < 3) return emptyList()

        val url = StringBuilder(BASE)
            .append("&q=")
            .append(URLEncoder.encode(q, StandardCharsets.UTF_8.name()))
            .append("&accept-language=")
            .append(URLEncoder.encode(languageTag, StandardCharsets.UTF_8.name()))
            .toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty(
                "User-Agent",
                "Anchor/1.0 (com.example.anchor; contact: in-app; geofence address search)"
            )
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
            } ?: ""
            if (code !in 200..299) return emptyList()
            parseArray(JSONArray(text))
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseArray(arr: JSONArray): List<NominatimAddressResult> {
        val out = ArrayList<NominatimAddressResult>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("display_name", "")
            if (name.isBlank()) continue
            val lat = o.optString("lat", "").toDoubleOrNull() ?: continue
            val lon = o.optString("lon", "").toDoubleOrNull() ?: continue
            out.add(NominatimAddressResult(name, lat, lon))
        }
        return out
    }
}
