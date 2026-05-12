package com.uruj.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo Digital Elevation Model (DEM) lookup. Returns the actual ground elevation
 * at a given lat/lon from SRTM/COPERNICUS satellite data — independent of any phone
 * sensor. This is what Strava / Komoot / Garmin Connect use to correct elevation
 * post-ride.
 *
 * Free, no API key, public endpoint:
 *   GET https://api.open-meteo.com/v1/elevation?latitude=22.57&longitude=88.37
 *   → { "elevation": [12.0] }
 *
 * Used as the canonical altitude source when the device has no barometer (URUJ on
 * OnePlus 7/7T, most non-flagship phones). Cached client-side by 0.001-degree grid
 * (~110m precision) so repeated queries along the ride don't re-hit the API.
 */
class ElevationClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<Long, Float>()

    suspend fun elevationFor(lat: Double, lon: Double): Float? = withContext(Dispatchers.IO) {
        val key = gridKey(lat, lon)
        cache[key]?.let { return@withContext it }
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon",
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val response = json.decodeFromString(ElevationResponse.serializer(), body)
            val elev = response.elevation.firstOrNull()
            if (elev != null) {
                cache[key] = elev
                Log.d("URUJ-DEM", "Elevation $lat,$lon -> ${elev}m (cache size ${cache.size})")
            }
            elev
        }.onFailure { Log.w("URUJ-DEM", "elevation lookup failed: ${it.message}") }
            .getOrNull()
    }

    /** Grid key: round lat/lon to 0.001 degrees (~110m precision) and pack into a Long.
     *  At cycling speed we cover that much in 25s, so cache hit rate is high. */
    private fun gridKey(lat: Double, lon: Double): Long {
        val latKey = (lat * 1000).toLong()
        val lonKey = (lon * 1000).toLong()
        return (latKey shl 32) or (lonKey and 0xFFFFFFFFL)
    }
}

@Serializable
private data class ElevationResponse(val elevation: List<Float>)
