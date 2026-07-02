package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/** Cinematic letterbox bars for cut-scene beats. Slide in/out with uAmount. */
class Letterbox {
    private val vsrc = """
        attribute vec2 aPos;
        uniform float uAmount;         // 0..1
        uniform float uTop;            // 1 = top bar, 0 = bottom bar
        void main() {
            float h = 0.17 * uAmount;
            float y = mix(-1.0 + aPos.y * h, 1.0 - (1.0 - aPos.y) * h, uTop);
            gl_Position = vec4(aPos.x, y, 0.0, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        void main() { gl_FragColor = vec4(0.0, 0.0, 0.0, 0.94); }
    """
    private var prog = 0
    private var aPos = 0; private var uAmount = 0; private var uTop = 0
    private val quad = GlAssets.floatBuffer(floatArrayOf(-1f, 0f, 1f, 0f, -1f, 1f, 1f, 1f))

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uAmount = GLES20.glGetUniformLocation(prog, "uAmount")
        uTop = GLES20.glGetUniformLocation(prog, "uTop")
    }

    fun draw(amount: Float) {
        if (amount < 0.01f) return
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(uAmount, amount)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (top in 0..1) {
            GLES20.glUniform1f(uTop, top.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
