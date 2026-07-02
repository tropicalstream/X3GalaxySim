package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Camera-facing procedural nebula billboard (Orion stellar nursery).
 * Cheap value-noise "clouds", additive blend, cue-independent slow drift.
 */
class Nebula {
    private val vsrc = """
        uniform mat4 uVP;
        uniform vec3 uCenter;
        uniform vec3 uRight;
        uniform vec3 uUp;
        uniform float uSize;
        attribute vec2 aPos;
        varying vec2 vUV;
        void main() {
            vUV = aPos;
            vec3 wp = uCenter + (uRight * aPos.x + uUp * aPos.y) * uSize;
            gl_Position = uVP * vec4(wp, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec2 vUV;
        uniform float uTime;
        float hash(vec2 p) { return fract(sin(dot(p, vec2(41.3, 289.1))) * 43758.5453); }
        float noise(vec2 p) {
            vec2 i = floor(p); vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        void main() {
            vec2 p = vUV * 3.0 + vec2(uTime * 0.008, 0.0);
            float n = 0.0; float a = 0.55; vec2 q = p;
            for (int i = 0; i < 4; i++) { n += noise(q) * a; q *= 2.1; a *= 0.5; }
            float r = length(vUV);
            float mask = smoothstep(1.0, 0.25, r);
            float cloud = smoothstep(0.35, 0.85, n) * mask;
            vec3 col = mix(vec3(0.12, 0.30, 0.38), vec3(0.55, 0.20, 0.45), noise(p * 0.7));
            col += vec3(0.9, 0.75, 0.55) * smoothstep(0.75, 0.95, n) * 0.6;  // embedded stars glow
            gl_FragColor = vec4(col * cloud, cloud * 0.85);
        }
    """

    private var prog = 0
    private var aPos = 0
    private var uVP = 0; private var uCenter = 0; private var uRight = 0; private var uUp = 0
    private var uSize = 0; private var uTime = 0
    private val quad = GlAssets.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter")
        uRight = GLES20.glGetUniformLocation(prog, "uRight")
        uUp = GLES20.glGetUniformLocation(prog, "uUp")
        uSize = GLES20.glGetUniformLocation(prog, "uSize")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
    }

    fun draw(vp: FloatArray, center: FloatArray, right: FloatArray, up: FloatArray, size: Float, time: Float) {
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform3fv(uCenter, 1, center, 0)
        GLES20.glUniform3fv(uRight, 1, right, 0)
        GLES20.glUniform3fv(uUp, 1, up, 0)
        GLES20.glUniform1f(uSize, size)
        GLES20.glUniform1f(uTime, time)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
