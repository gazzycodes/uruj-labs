package com.uruj.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo — free public weather API, no key, no signup. Returns wind, temp, pressure
 * for any lat/lon. We poll once at ride start, then optionally every 10–15 min.
 *
 * https://open-meteo.com/en/docs
 */
class WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(lat: Double, lon: Double): WeatherSample? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m," +
                    "wind_speed_10m,wind_direction_10m,pressure_msl" +
                    "&wind_speed_unit=ms",
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val response = json.decodeFromString(WeatherResponse.serializer(), body)
            WeatherSample(
                fetchedAtMs = System.currentTimeMillis(),
                temperatureCelsius = response.current.temperature_2m,
                humidityPercent = response.current.relative_humidity_2m,
                windSpeedMs = response.current.wind_speed_10m,
                windDirectionDeg = response.current.wind_direction_10m,
                pressureHpa = response.current.pressure_msl,
            )
        }.onFailure { Log.w("URUJ-Weather", "fetch failed: ${it.message}") }
            .getOrNull()
    }
}

data class WeatherSample(
    val fetchedAtMs: Long,
    val temperatureCelsius: Float,
    val humidityPercent: Float,
    val windSpeedMs: Float,
    /** Direction the wind is COMING FROM (meteorological convention), degrees from north. */
    val windDirectionDeg: Float,
    val pressureHpa: Float,
)

/**
 * UI-visible weather subsystem status. The HUD renders one of these states so you
 * never wonder "is weather working or just blank?". Refresh-cadence info travels
 * inside the OK / Failed variants so the UI can show `next in N min`.
 */
sealed class WeatherStatus {
    object Idle : WeatherStatus()
    object WaitingForGps : WeatherStatus()
    object Fetching : WeatherStatus()
    data class Ok(val fetchedAtMs: Long, val nextFetchAtMs: Long) : WeatherStatus()
    data class Failed(val retryAtMs: Long) : WeatherStatus()
}

@Serializable
private data class WeatherResponse(val current: Current) {
    @Serializable
    data class Current(
        val temperature_2m: Float,
        val relative_humidity_2m: Float,
        val wind_speed_10m: Float,
        val wind_direction_10m: Float,
        val pressure_msl: Float,
    )
}

/**
 * Given the rider's heading and current wind, compute the headwind component (positive =
 * headwind, negative = tailwind) and apparent wind speed for the aero drag term.
 */
object WindMath {
    /**
     * @param bikeHeadingDeg direction bike is traveling (0=N, 90=E), from GPS bearing
     * @param windFromDeg wind meteorological "from" direction
     * @param windSpeedMs wind speed in m/s
     * @return headwind component (positive = wind in your face, m/s)
     */
    fun headwindComponentMs(bikeHeadingDeg: Float, windFromDeg: Float, windSpeedMs: Float): Float {
        // "Wind from N (0°)" means air flowing southward — same direction as a south-traveling
        // bike heading 180°. So a head-on collision is bikeHeading == windFrom.
        val deltaDeg = ((bikeHeadingDeg - windFromDeg) % 360f + 360f) % 360f
        val deltaRad = Math.toRadians(deltaDeg.toDouble())
        return (windSpeedMs * Math.cos(deltaRad)).toFloat()
    }
}
