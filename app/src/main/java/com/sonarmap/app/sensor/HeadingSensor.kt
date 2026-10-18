package com.sonarmap.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.min

/**
 * User heading (compass azimuth) from the rotation-vector sensor, the only
 * sensor-specific class in the app layer — everything downstream consumes
 * plain degrees through Flow.
 *
 * Recipe from the official Android sensor documentation:
 * getRotationMatrixFromVector -> remapCoordinateSystem(AXIS_X, AXIS_Z) ->
 * getOrientation, where values[0] (azimuth) is the compass heading of the
 * device's top edge when held vertically: 0 = north, 90 = east.
 *
 * Output: [Flow] of heading degrees in [0, 360), or a single null when the
 * sensor is absent — the consumer degrades to mono pan instead of guessing.
 * Conflated: the audio controller pulls at its own throttled pace, sensor
 * events never queue up.
 *
 * Threading: registered on the collector's thread (main inside the screen
 * composition), so callbacks arrive on the main looper; all work is trivial
 * matrix math, nothing blocks.
 *
 * Known MVP limitation: heading is remapped for portrait handling; landscape
 * rotation of the screen is not compensated (no display-rotation branch).
 */
class HeadingSensor(private val context: Context) {

    fun heading(): Flow<Double?> = callbackFlow {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensor == null) {
            // Degrade: no rotation vector on this device -> mono pan.
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        val rotationVector = FloatArray(5)
        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                // SensorEvent.values is reused by the system; copy first.
                System.arraycopy(
                    event.values, 0, rotationVector, 0,
                    min(5, event.values.size),
                )
                SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
                // AXIS_X north / AXIS_Z as the remap target = azimuth follows
                // the direction the top edge points (official compass recipe).
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remapped,
                )
                SensorManager.getOrientation(remapped, orientation)
                val headingDegrees = Math.toDegrees(orientation[0].toDouble())
                trySend((headingDegrees + 360.0) % 360.0)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()
}
