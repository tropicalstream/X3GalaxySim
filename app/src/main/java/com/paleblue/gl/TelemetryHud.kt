package com.paleblue.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Live science telemetry panel (head-fixed, left side): velocity with real
 * relativistic dress-up, Lorentz γ time-dilation factor, hull equilibrium
 * temperature, deflector state, and the NEXT point of interest with live
 * distance + ETA. Text renders via Canvas into a texture, updated only when
 * a line actually changes.
 */
class TelemetryHud {
    private val texW = 512; private val texH = 400
    private val vsrc = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        uniform vec2 uCenter;
        uniform vec2 uScale;
        varying vec2 vUV;
        void main() { vUV = aUV; gl_Position = vec4(uCenter + aPos * uScale, 0.0, 1.0); }
    """
    private val fsrc = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform float uAlpha;
        void main() {
            vec4 c = texture2D(uTex, vUV);
            gl_FragColor = vec4(c.rgb, c.a * uAlpha);
        }
    """
    private var prog = 0
    private var aPos = 0; private var aUV = 0
    private var uCenter = 0; private var uScale = 0; private var uTex = 0; private var uAlpha = 0
    private var tex = 0
    private var lastKey = ""
    private val bmp = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 31f
    }
    private val panel = Paint().apply { color = Color.argb(110, 2, 10, 18) }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(160, 60, 160, 200)
    }
    private val quad = GlAssets.floatBuffer(floatArrayOf(
        -1f, -1f, 0f, 1f,   1f, -1f, 1f, 1f,   -1f, 1f, 0f, 0f,   1f, 1f, 1f, 0f))

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter")
        uScale = GLES20.glGetUniformLocation(prog, "uScale")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Lines as (label, value, colorHint) — colorHint: 0 cyan, 1 white, 2 amber, 3 green. */
    fun setLines(lines: List<Triple<String, String, Int>>) {
        val key = lines.joinToString("|") { it.first + it.second + it.third }
        if (key == lastKey) return
        lastKey = key
        bmp.eraseColor(Color.TRANSPARENT)
        canvas.drawRoundRect(4f, 4f, texW - 4f, texH - 4f, 16f, 16f, panel)
        canvas.drawRoundRect(4f, 4f, texW - 4f, texH - 4f, 16f, 16f, edge)
        var y = 54f
        for ((label, value, hint) in lines) {
            mono.color = Color.argb(200, 110, 190, 220)
            canvas.drawText(label, 22f, y, mono)
            mono.color = when (hint) {
                1 -> Color.WHITE
                2 -> Color.rgb(255, 190, 90)
                3 -> Color.rgb(120, 235, 160)
                else -> Color.rgb(140, 225, 255)
            }
            canvas.drawText(value, 168f, y, mono)
            y += 50f
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun draw(eyeAspect: Float, xShift: Float, alpha: Float) {
        if (alpha <= 0.01f || lastKey.isEmpty()) return
        val halfH = 0.26f
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform2f(uCenter, -0.60f + xShift, -0.46f)
        GLES20.glUniform2f(uScale, halfH * (texW.toFloat() / texH) / eyeAspect, halfH)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUV)
    }
}
