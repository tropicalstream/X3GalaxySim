package com.rayneo.x3constellation

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs

class X3ConstellationView(
    context: Context,
    private val audioEngine: SpaceAudioEngine
) : GLSurfaceView(context) {
    private val renderer = StereoConstellationRenderer(audioEngine)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun telemetry(): String = renderer.telemetry()

    fun setApproachListener(listener: (String) -> Unit) = renderer.setApproachListener(listener)

    fun setViewListener(listener: (Int) -> Unit) = renderer.setViewListener(listener)

    fun setSpeedListener(listener: (Int) -> Unit) = renderer.setSpeedListener(listener)

    fun currentBodyName(): String = renderer.currentBodyName()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val elapsed = event.eventTime - downAt
                if (elapsed < 280 && abs(dx) < 42f && abs(dy) < 42f) {
                    queueEvent { renderer.switchView() }
                    audioEngine.tap()
                    return true
                }
                if (abs(dx) > abs(dy) && abs(dx) > 80f) {
                    if (dx > 0f) {
                        queueEvent { renderer.accelerateForward() }
                        audioEngine.warp()
                    } else {
                        queueEvent { renderer.accelerateBack() }
                        audioEngine.reverse()
                    }
                    return true
                }
            }
        }
        return true
    }
}
