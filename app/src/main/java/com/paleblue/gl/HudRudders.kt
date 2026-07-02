package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Head-fixed lower ship fins. These stay visually attached under the HUD panels
 * instead of drifting as world geometry when the user looks around.
 */
class HudRudders {
    private val vsrc = """
        attribute vec2 aPos;
        uniform vec2 uCenter;
        uniform vec2 uScale;
        uniform float uStretch;
        void main() {
            vec2 p = aPos;
            p.y *= uStretch;
            gl_Position = vec4(uCenter + p * uScale, 0.0, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        uniform vec3 uColor;
        uniform float uAlpha;
        void main() { gl_FragColor = vec4(uColor, uAlpha); }
    """

    private var prog = 0
    private var aPos = 0
    private var uCenter = 0
    private var uScale = 0
    private var uStretch = 0
    private var uColor = 0
    private var uAlpha = 0
    private val fin = GlAssets.floatBuffer(floatArrayOf(
        -1.00f,  0.18f,
        -0.12f,  0.34f,
         1.00f,  0.10f,
        -0.54f, -0.26f,
        -1.00f,  0.18f,
         1.00f,  0.10f
    ))

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter")
        uScale = GLES20.glGetUniformLocation(prog, "uScale")
        uStretch = GLES20.glGetUniformLocation(prog, "uStretch")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
    }

    fun draw(eyeAspect: Float, xShift: Float, timeSec: Float, alpha: Float, speed: Float) {
        if (alpha <= 0.01f) return
        GLES20.glUseProgram(prog)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, fin)
        GLES20.glEnableVertexAttribArray(aPos)
        val stretch = 1.0f + speed.coerceIn(0f, 1.4f) * 0.22f
        drawOne(-0.60f + xShift, -0.66f, 0.30f / eyeAspect, 0.115f, stretch, true, timeSec, alpha)
        drawOne(0.60f + xShift, -0.66f, 0.30f / eyeAspect, 0.115f, stretch, false, timeSec, alpha)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawOne(cx: Float, cy: Float, sx: Float, sy: Float, stretch: Float,
                        red: Boolean, timeSec: Float, alpha: Float) {
        val flash = 0.55f + 0.45f * kotlin.math.sin(timeSec * 7.0f + if (red) 0f else 3.14159f)
        val a = alpha * (0.75f + 0.25f * flash)
        GLES20.glUniform2f(uCenter, cx, cy)
        GLES20.glUniform2f(uScale, sx, sy)
        GLES20.glUniform1f(uStretch, stretch)
        if (red) GLES20.glUniform3f(uColor, 1.0f, 0.10f, 0.07f)
        else GLES20.glUniform3f(uColor, 0.10f, 0.36f, 1.0f)
        GLES20.glUniform1f(uAlpha, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }
}
