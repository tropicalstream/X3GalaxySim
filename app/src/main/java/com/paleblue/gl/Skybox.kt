package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Procedural deep-space background: subtle blue-black gradient plus the
 * Milky Way band (density falls off with galactic latitude). Drawn first,
 * depth-writes off, on an inverted sphere that travels with the camera.
 */
class Skybox {
    private val vsrc = """
        uniform mat4 uVP;
        attribute vec3 aPos;
        varying vec3 vDir;
        void main() {
            vDir = aPos;
            gl_Position = uVP * vec4(aPos * 390.0, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vDir;
        uniform float uBandBoost;      // brighter as we near the galactic core
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        void main() {
            vec3 d = normalize(vDir);
            // galactic band: tilt the plane a little for visual interest
            float lat = abs(d.y * 0.92 + d.x * 0.18);
            float band = exp(-lat * lat * 18.0);
            float dust = hash(floor(d.xz * 60.0 + d.y * 31.0));
            band *= 0.75 + 0.5 * dust;
            vec3 sky = vec3(0.004, 0.006, 0.012);
            vec3 milk = vec3(0.10, 0.11, 0.16) * band * (1.0 + uBandBoost * 2.2);
            vec3 core = vec3(0.16, 0.12, 0.08) * band * uBandBoost *
                        smoothstep(0.4, 1.0, dot(d, normalize(vec3(0.2, -0.05, -1.0))));
            gl_FragColor = vec4(sky + milk + core, 1.0);
        }
    """

    private var prog = 0
    private var aPos = 0; private var uVP = 0; private var uBandBoost = 0
    private lateinit var sphere: Sphere

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uBandBoost = GLES20.glGetUniformLocation(prog, "uBandBoost")
        sphere = Sphere(12, 24)
    }

    fun draw(vpNoTranslation: FloatArray, bandBoost: Float) {
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vpNoTranslation, 0)
        GLES20.glUniform1f(uBandBoost, bandBoost)
        sphere.vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indices)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
