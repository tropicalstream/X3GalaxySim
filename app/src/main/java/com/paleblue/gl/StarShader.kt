package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets
import java.nio.FloatBuffer

/**
 * Point-sprite starfield with relativistic Doppler tint.
 * Stars are directions on a far sphere that travels with the camera.
 * uBeta = v/c; forward stars shift blue, rear stars shift red.
 */
class StarShader {
    private val vsrc = """
        uniform mat4 uVP;              // view-projection with camera translation removed
        uniform float uBeta;
        uniform vec3 uForward;
        attribute vec3 aDir;           // unit direction of the star
        attribute vec4 aColorSize;     // rgb + point size
        varying vec3 vColor;
        void main() {
            float s = uBeta * dot(aDir, uForward);   // >0 approaching (blue), <0 receding (red)
            vec3 c = aColorSize.rgb;
            c.b *= (1.0 + 2.2 * max(s, 0.0));
            c.r *= (1.0 + 2.2 * max(-s, 0.0));
            c.g *= (1.0 - 0.35 * abs(s));
            vColor = c * (1.0 + 1.5 * max(s, 0.0)); // headlight brightening
            gl_Position = uVP * vec4(aDir * 380.0, 1.0);
            gl_PointSize = aColorSize.a * (1.0 + 1.2 * max(s, 0.0));
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vColor;
        void main() {
            vec2 d = gl_PointCoord - vec2(0.5);
            float a = smoothstep(0.5, 0.05, length(d));
            gl_FragColor = vec4(vColor, a);
        }
    """

    private var prog = 0
    private var aDir = 0; private var aColorSize = 0
    private var uVP = 0; private var uBeta = 0; private var uForward = 0

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aDir = GLES20.glGetAttribLocation(prog, "aDir")
        aColorSize = GLES20.glGetAttribLocation(prog, "aColorSize")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uBeta = GLES20.glGetUniformLocation(prog, "uBeta")
        uForward = GLES20.glGetUniformLocation(prog, "uForward")
    }

    /** vbo layout: [dir.xyz, r, g, b, size] x count */
    fun draw(buffer: FloatBuffer, count: Int, vp: FloatArray, beta: Float, forward: FloatArray) {
        if (count == 0) return
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform1f(uBeta, beta)
        GLES20.glUniform3fv(uForward, 1, forward, 0)
        val stride = 7 * 4
        buffer.position(0)
        GLES20.glVertexAttribPointer(aDir, 3, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aDir)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aColorSize, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(aColorSize)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aDir)
        GLES20.glDisableVertexAttribArray(aColorSize)
    }
}
