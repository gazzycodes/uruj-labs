package com.uruj.sensor.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.uruj.sensor.LocationSample
import com.uruj.sensor.LocationSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class FusedLocationSource(context: Context) : LocationSource {

    private val appContext = context.applicationContext

    override val isAvailable: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun samples(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    trySend(location.toSample())
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }
}

private fun Location.toSample(): LocationSample = LocationSample(
    timestampMs = time,
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = altitude,
    speedMetersPerSecond = if (hasSpeed()) speed else 0f,
    horizontalAccuracyMeters = if (hasAccuracy()) accuracy else Float.POSITIVE_INFINITY,
    bearingDeg = if (hasBearing()) bearing else null,
)
