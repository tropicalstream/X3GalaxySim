package com.paleblue

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * IMU 3DoF head tracking (Sensor.TYPE_ROTATION_VECTOR) — replaces tap-to-switch.
 * FULL 360°: yaw is unbounded and wrap-aware, so the user can spin the whole
 * way around — look back past the nacelles at where you've been. Gaze never
 * moves the ship — view only.
 */
class GazeCamera(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = FloatArray(9)
    @Volatile var yaw = 0f      // radians, smoothed, unbounded (360°)
    @Volatile var pitch = 0f
    val viewYaw: Float get() = yaw
    val viewPitch: Float get() = pitch
    private var zeroYaw = Float.NaN
    private var zeroPitch = Float.NaN

    fun start() = sm.registerListener(this,
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
        SensorManager.SENSOR_DELAY_GAME)

    fun stop() = sm.unregisterListener(this)

    fun recenter() {
        zeroYaw = Float.NaN
        zeroPitch = Float.NaN
        yaw = 0f
        pitch = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)

        // Android's rotation matrix maps device axes into world axes. Treat the
        // glasses' viewing direction as the device -Z basis vector in world space.
        // This avoids the old axis-cell hack where yaw, pitch, and roll bled into
        // each other on the X3 Pro sensor mount.
        val fx = -rot[2]
        val fy = -rot[5]
        val fz = -rot[8]
        val len = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-5f)
        val nx = fx / len
        val ny = fy / len
        val nz = fz / len

        val rawYaw = atan2(nx, -nz)
        val rawPitch = asin(ny.coerceIn(-1f, 1f))
        if (zeroYaw.isNaN() || zeroPitch.isNaN()) {
            zeroYaw = rawYaw
            zeroPitch = rawPitch
        }

        // RayNeo X3 Pro optical module remap, verified on-device:
        // - user look right/left is encoded in Android matrix pitch
        // - user look up/down is encoded in inverse Android matrix yaw
        // Keep this as the single source of truth for intuitive gaze.
        val mountedYaw = rawPitch - zeroPitch
        val mountedPitch = -wrapPi(rawYaw - zeroYaw)

        // 360-degree yaw: keep the target wrap-aware, then smooth toward it.
        val targetYaw = wrapPi(mountedYaw)
        val dy = wrapPi(targetYaw - wrapPi(yaw))
        yaw += dy * 0.22f

        // Looking up raises the world view; looking down lowers it. Pitch is
        // intentionally bounded for comfort, yaw is not.
        val targetPitch = mountedPitch.coerceIn(-1.35f, 1.35f)
        pitch += (targetPitch - pitch) * 0.22f
    }

    private fun wrapPi(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
