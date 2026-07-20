package com.paleblue.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Renders text into a texture (Android Canvas) and draws it as a screen-space
 * quad. Used by the caption HUD and the minimap readout. High-contrast:
 * white text, black outline, optional backing bar.
 */
class HudText(
    private val texW: Int = 1024,
    private val texH: Int = 256,
    textSizePx: Float = 46f,
    private val maxChars: Int = 40,
    private val maxLines: Int = 3
) {

    private val vsrc = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        uniform vec2 uCenter;      // NDC center
        uniform vec2 uScale;       // NDC half-extents
        varying vec2 vUV;
        void main() {
            vUV = aUV;
            gl_Position = vec4(uCenter + aPos * uScale, 0.0, 1.0);
        }
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
    private var lastText: String? = null
    var aspectRatio = 4f; private set   // text block w/h, for layout

    private val baseTextSize = textSizePx
    private val bmp = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = textSizePx; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE; strokeWidth = textSizePx * 0.16f; color = Color.BLACK
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

    /**
     * Word-wraps [text] (honoring explicit \n) and re-uploads if changed.
     * NOTHING IS EVER TRUNCATED: if the wrapped block exceeds maxLines the
     * font auto-shrinks until every line fits the texture.
     */
    fun setText(text: String, backingBar: Boolean = true) {
        if (text == lastText) return
        lastText = text
        bmp.eraseColor(Color.TRANSPARENT)
        if (text.isNotBlank()) {
            var size = baseTextSize
            var chars = maxChars
            var lines = wrap(text, chars)
            // auto-fit pass 1: shrink font (widening the char budget) only while
            // re-wrapping actually reduces the line count — explicit \n lines
            // can never merge, so shrinking further would just waste size
            while (lines.size > maxLines && size > baseTextSize * 0.45f) {
                val smaller = size * 0.88f
                val rechars = (maxChars * baseTextSize / smaller).toInt()
                val relines = wrap(text, rechars)
                if (relines.size >= lines.size) break
                size = smaller; chars = rechars; lines = relines
            }
            // auto-fit pass 2: whatever the line count, the block must fit the
            // texture height — scale to fit exactly instead of overflowing
            val maxBlockH = texH - 30f
            if (lines.size * size * 1.28f > maxBlockH) {
                size = maxBlockH / (lines.size * 1.28f)
            }
            fill.textSize = size
            stroke.textSize = size
            stroke.strokeWidth = size * 0.16f
            val lh = size * 1.28f
            val totalH = lines.size * lh
            if (backingBar) {
                val bar = Paint().apply { color = Color.argb(150, 0, 0, 0) }
                canvas.drawRoundRect(texW * 0.02f, texH / 2f - totalH / 2f - 14f,
                    texW * 0.98f, texH / 2f + totalH / 2f + 14f, 18f, 18f, bar)
            }
            var y = texH / 2f - totalH / 2f + size
            for (l in lines) {
                canvas.drawText(l, texW / 2f, y, stroke)
                canvas.drawText(l, texW / 2f, y, fill)
                y += lh
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        aspectRatio = texW.toFloat() / texH
    }

    /** Wrap honoring explicit newlines first, then word-wrapping each paragraph. */
    private fun wrap(text: String, maxChars: Int): List<String> {
        val lines = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isBlank()) { lines.add(""); continue }
            var cur = StringBuilder()
            for (w in para.trim().split(" ")) {
                if (cur.length + w.length + 1 > maxChars && cur.isNotEmpty()) {
                    lines.add(cur.toString()); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
            if (cur.isNotEmpty()) lines.add(cur.toString())
        }
        return lines
    }

    /** Draw at NDC [cx, cy] with half-height [halfH] (width follows texture aspect / eye aspect). */
    fun draw(cx: Float, cy: Float, halfH: Float, eyeAspect: Float, alpha: Float) {
        if (lastText.isNullOrBlank() || alpha <= 0.01f) return
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform2f(uCenter, cx, cy)
        GLES20.glUniform2f(uScale, halfH * aspectRatio / eyeAspect, halfH)
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
