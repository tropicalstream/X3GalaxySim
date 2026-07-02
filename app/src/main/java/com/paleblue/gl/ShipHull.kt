package com.paleblue.gl

import android.opengl.GLES20
import android.opengl.Matrix
import com.paleblue.GlAssets
import java.nio.FloatBuffer

/**
 * The visible front of the ship — replaces the old head-fixed window frame.
 * Two compact illuminated rudders anchored to the SHIP (rail heading), not the
 * head. They give a subtle bridge/vehicle anchor without blocking the sky.
 * The fins lengthen with speed and pulse red/blue like navigation strobes.
 */
class ShipHull {
    private val vsrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform float uEngine;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        varying vec3 vN;
        varying vec3 vWorld;
        varying vec3 vShip;
        void main() {
            vec3 p = aPos;
            if (p.y < -0.9) {
                float stretch = 1.0 + min(uEngine, 1.4) * 0.34;
                p.z = -0.72 + (p.z + 0.72) * stretch;
                p.y = -0.78 + (p.y + 0.78) * (1.0 + min(uEngine, 1.4) * 0.16);
            }
            vN = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            vWorld = (uModel * vec4(p, 1.0)).xyz;
            vShip = p;
            gl_Position = uMVP * vec4(p, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vN;
        varying vec3 vWorld;
        varying vec3 vShip;
        uniform vec3 uSunPos;
        uniform vec3 uCamPos;
        uniform float uSunlight;
        uniform float uTime;
        uniform float uEngine;
        void main() {
            vec3 N = normalize(vN);
            vec3 L = normalize(uSunPos - vWorld);
            vec3 V = normalize(uCamPos - vWorld);
            float ndl = max(dot(N, L), 0.0);
            float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
            vec3 base = vec3(0.10, 0.11, 0.13);                      // gunmetal
            float side = step(0.0, vShip.x);
            float flash = 0.45 + 0.55 * pow(0.5 + 0.5 * sin(uTime * 7.0 + side * 3.14159), 3.0);
            vec3 strobe = mix(vec3(1.0, 0.06, 0.03), vec3(0.12, 0.35, 1.0), side) * flash * (0.55 + uEngine);
            vec3 col = base * (0.22 + 0.9 * ndl * min(uSunlight, 1.5))
                     + vec3(0.25, 0.45, 0.65) * rim * 0.35           // starlight rim
                     + strobe;
            gl_FragColor = vec4(col, 1.0);
        }
    """
    private val glowV = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() { vUV = aUV; gl_Position = uMVP * vec4(aPos, 1.0); }
    """
    private val glowF = """
        precision mediump float;
        varying vec2 vUV;
        uniform float uIntensity;
        uniform float uWarp;         // 0 impulse (amber-cyan) .. 1 warp (blue-white)
        void main() {
            float r = length(vUV - 0.5) * 2.0;
            float core = smoothstep(1.0, 0.0, r);
            vec3 impulse = vec3(0.35, 0.75, 1.0);
            vec3 warp = vec3(0.75, 0.85, 1.2);
            vec3 col = mix(impulse, warp, uWarp) * core * core * uIntensity * 2.2;
            gl_FragColor = vec4(col, core * uIntensity);
        }
    """

    private var prog = 0; private var gProg = 0
    private var aPos = 0; private var aNormal = 0
    private var uMVP = 0; private var uModel = 0; private var uSunPos = 0; private var uCamPos = 0; private var uSunlight = 0; private var uTime = 0; private var uEngine = 0
    private var gaPos = 0; private var gaUV = 0; private var guMVP = 0; private var guIntensity = 0; private var guWarp = 0
    private lateinit var hull: FloatBuffer
    private var hullVerts = 0
    private lateinit var glow: FloatBuffer
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uSunPos = GLES20.glGetUniformLocation(prog, "uSunPos")
        uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        uSunlight = GLES20.glGetUniformLocation(prog, "uSunlight")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
        uEngine = GLES20.glGetUniformLocation(prog, "uEngine")
        gProg = GlAssets.compileProgram(glowV, glowF)
        gaPos = GLES20.glGetAttribLocation(gProg, "aPos")
        gaUV = GLES20.glGetAttribLocation(gProg, "aUV")
        guMVP = GLES20.glGetUniformLocation(gProg, "uMVP")
        guIntensity = GLES20.glGetUniformLocation(gProg, "uIntensity")
        guWarp = GLES20.glGetUniformLocation(gProg, "uWarp")
        buildMesh()
    }

    /** Ship space: -Z forward, +Y up, camera at origin (bridge). */
    private fun buildMesh() {
        val tris = ArrayList<Float>()
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-5f)
            nx /= l; ny /= l; nz /= l
            for (p in arrayOf(a, b, c)) { tris.add(p[0]); tris.add(p[1]); tris.add(p[2]); tris.add(nx); tris.add(ny); tris.add(nz) }
        }
        // --- two short lower rudders, one below each HUD corner ---
        for (s in intArrayOf(-1, 1)) {
            val x = s * 0.95f
            val rootA = floatArrayOf(x - s * 0.12f, -0.78f, -0.72f)
            val rootB = floatArrayOf(x + s * 0.12f, -0.78f, -0.72f)
            val tipA = floatArrayOf(x + s * 0.26f, -1.18f, -1.45f)
            val tipB = floatArrayOf(x - s * 0.05f, -1.05f, -1.32f)
            val keel = floatArrayOf(x + s * 0.05f, -1.28f, -1.62f)
            tri(rootA, tipA, tipB)
            tri(rootA, keel, tipA)
            tri(rootB, tipB, keel)
            tri(rootA, rootB, keel)
        }
        hull = GlAssets.floatBuffer(tris.toFloatArray())
        hullVerts = tris.size / 6
        // engine glow quads at nacelle exits, facing aft (+Z): [x,y,z,u,v]
        val g = ArrayList<Float>()
        for (s in intArrayOf(-1, 1)) {
            val cx = s * 0.98f; val cy = -1.13f; val cz = -1.48f; val r = 0.18f
            for (p in arrayOf(
                floatArrayOf(cx - r, cy - r, cz, 0f, 0f), floatArrayOf(cx + r, cy - r, cz, 1f, 0f),
                floatArrayOf(cx - r, cy + r, cz, 0f, 1f), floatArrayOf(cx + r, cy - r, cz, 1f, 0f),
                floatArrayOf(cx + r, cy + r, cz, 1f, 1f), floatArrayOf(cx - r, cy + r, cz, 0f, 1f)))
                g.addAll(p.toList())
        }
        glow = GlAssets.floatBuffer(g.toFloatArray())
    }

    /**
     * [shipYaw]/[shipPitch] orient the hull to the rail heading (+ reframe);
     * the user's gaze does NOT move the ship — that's the whole point.
     */
    fun draw(vp: FloatArray, camPos: FloatArray, shipYaw: Float, shipPitch: Float,
             sunPos: FloatArray, sunlight: Float, engineIntensity: Float, warp: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camPos[0], camPos[1], camPos[2])
        Matrix.rotateM(model, 0, -Math.toDegrees(shipYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(shipPitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
        GLES20.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES20.glUniform1f(uSunlight, sunlight)
        GLES20.glUniform1f(uTime, System.nanoTime() / 1.0e9f.toFloat())
        GLES20.glUniform1f(uEngine, engineIntensity.coerceIn(0f, 1.6f))
        hull.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, hull)
        GLES20.glEnableVertexAttribArray(aPos)
        hull.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, hull)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, hullVerts)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)

        // engine glow (additive)
        GLES20.glUseProgram(gProg)
        GLES20.glUniformMatrix4fv(guMVP, 1, false, mvp, 0)
        GLES20.glUniform1f(guIntensity, engineIntensity.coerceIn(0f, 1.6f))
        GLES20.glUniform1f(guWarp, warp.coerceIn(0f, 1f))
        glow.position(0)
        GLES20.glVertexAttribPointer(gaPos, 3, GLES20.GL_FLOAT, false, 20, glow)
        GLES20.glEnableVertexAttribArray(gaPos)
        glow.position(3)
        GLES20.glVertexAttribPointer(gaUV, 2, GLES20.GL_FLOAT, false, 20, glow)
        GLES20.glEnableVertexAttribArray(gaUV)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 12)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(gaPos)
        GLES20.glDisableVertexAttribArray(gaUV)
    }
}
