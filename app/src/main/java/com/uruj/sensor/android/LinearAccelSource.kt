package com.uruj.sensor.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.uruj.sensor.AccelerometerSample
import com.uruj.sensor.AccelerometerSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

private const val EARTH_G_MS2 = 9.81f

class LinearAccelSource(context: Context) : AccelerometerSource {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // TYPE_LINEAR_ACCELERATION reports acceleration with gravity removed. Auto-pause uses
    // this as a "vibration baseline" — a moving bike produces road-vibration noise even at
    // constant speed; a stopped bike sits near zero. GPS speed alone can drift to non-zero
    // at standstill, so combining both signals is more reliable than either in isolation.
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    override val isAvailable: Boolean
        get() = sensor != null

    override fun samples(): Flow<AccelerometerSample> = callbackFlow {
        val accelSensor = sensor ?: run {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val magnitudeG = sqrt(x * x + y * y + z * z) / EARTH_G_MS2
                    trySend(AccelerometerSample(System.currentTimeMillis(), magnitudeG))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
