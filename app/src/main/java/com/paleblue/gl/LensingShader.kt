package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Sgr A* climax: screen-space gravitational lensing post pass.
 * The scene is rendered into an FBO; this pass distorts samples toward the
 * event-horizon center with a 1/r falloff, a hard black disk, and a photon ring.
 * All math happens in per-eye UV space (uOff/uSpan map the eye's sub-rect
 * inside the shared FBO texture).
 */
class LensingShader {
    private val vsrc = """
        attribute vec2 aPos;           // NDC quad
        attribute vec2 aUV;            // FBO texture space
        varying vec2 vUV;
        void main() { vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }
    """
    private val fsrc = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uScene;
        uniform vec2 uOff;             // eye rect origin in texture space
        uniform vec2 uSpan;            // eye rect size in texture space
        uniform vec2 uCenter;          // BH center in EYE UV space
        uniform float uStrength;       // 0..1 cue-driven
        uniform float uAspect;         // eye viewport w/h
        void main() {
            vec2 eye = (vUV - uOff) / uSpan;
            vec2 d = eye - uCenter;
            d.x *= uAspect;
            float r = max(length(d), 1e-4);
            vec2 dir = d / r;
            float k = 0.045 * uStrength;
            float bend = k / r;                     // 1/r deflection
            vec2 off = dir * bend * min(r * 6.0, 1.0);
            vec2 seye = clamp(eye - vec2(off.x / uAspect, off.y), 0.0, 1.0);
            vec3 col = texture2D(uScene, uOff + seye * uSpan).rgb;
            float rs = 0.055 * uStrength;           // event horizon (screen space)
            float ring = smoothstep(rs * 1.9, rs * 1.25, r) * smoothstep(rs * 0.9, rs * 1.15, r);
            col += vec3(1.0, 0.72, 0.38) * ring * 1.6 * uStrength;   // photon ring
            col *= smoothstep(rs * 0.92, rs * 1.08, r);              // hard black disk
            gl_FragColor = vec4(col, 1.0);
        }
    """

    private var prog = 0
    private var aPos = 0; private var aUV = 0
    private var uScene = 0; private var uCenter = 0; private var uStrength = 0; private var uAspect = 0
    private var uOff = 0; private var uSpan = 0
    private val quad = GlAssets.floatBuffer(floatArrayOf(
        -1f, -1f, 0f, 0f,   1f, -1f, 1f, 0f,   -1f, 1f, 0f, 1f,   1f, 1f, 1f, 1f))

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uScene = GLES20.glGetUniformLocation(prog, "uScene")
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter")
        uStrength = GLES20.glGetUniformLocation(prog, "uStrength")
        uAspect = GLES20.glGetUniformLocation(prog, "uAspect")
        uOff = GLES20.glGetUniformLocation(prog, "uOff")
        uSpan = GLES20.glGetUniformLocation(prog, "uSpan")
    }

    /** [uvRect] = eye sub-rect in FBO texture UV (x, y, w, h); [centerUv] in eye UV space. */
    fun draw(sceneTex: Int, uvRect: FloatArray, centerUv: FloatArray, strength: Float, aspect: Float) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex)
        GLES20.glUniform1i(uScene, 0)
        GLES20.glUniform2fv(uCenter, 1, centerUv, 0)
        GLES20.glUniform1f(uStrength, strength)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform2f(uOff, uvRect[0], uvRect[1])
        GLES20.glUniform2f(uSpan, uvRect[2], uvRect[3])
        val q = floatArrayOf(
            -1f, -1f, uvRect[0], uvRect[1],
            1f, -1f, uvRect[0] + uvRect[2], uvRect[1],
            -1f, 1f, uvRect[0], uvRect[1] + uvRect[3],
            1f, 1f, uvRect[0] + uvRect[2], uvRect[1] + uvRect[3])
        val buf = GlAssets.floatBuffer(q)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUV)
    }
}
