package com.paleblue.gl

import android.opengl.GLES20
import android.opengl.Matrix
import com.paleblue.GlAssets
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The intro tableau: the player's ship, the PALE BLUE, docked at an orbital
 * station high over a Type-1 Earth. The pre-launch camera slowly orbits this
 * scene; after departure the dock remains in the world, shrinking astern.
 * Station: habitat ring + spine + docking arm, with slow rotation and
 * blinking pad lights. Ship: the shared hull mesh at liner scale, drives cold.
 */
class DockScene {
    companion object {
        val SHIP_POS = floatArrayOf(11.5f, 1.0f, -13.5f)     // ~11 units off Earth's center
        val STATION_POS = floatArrayOf(14.5f, 2.2f, -16.5f)
    }

    private val vsrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        varying vec3 vN;
        varying vec3 vWorld;
        void main() {
            vN = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            vWorld = (uModel * vec4(aPos, 1.0)).xyz;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vN;
        varying vec3 vWorld;
        uniform vec3 uSunPos;
        uniform vec3 uCamPos;
        uniform float uSunlight;
        uniform vec3 uHull;
        void main() {
            vec3 N = normalize(vN);
            vec3 L = normalize(uSunPos - vWorld);
            vec3 V = normalize(uCamPos - vWorld);
            float ndl = max(dot(N, L), 0.0);
            float rim = pow(1.0 - max(dot(N, V), 0.0), 2.5);
            vec3 col = uHull * (0.16 + 0.95 * ndl * min(uSunlight, 1.5))
                     + vec3(0.30, 0.45, 0.62) * rim * 0.38
                     + vec3(0.9, 0.75, 0.4) * 0.05;         // warm window spill
            gl_FragColor = vec4(col, 1.0);
        }
    """

    private var prog = 0
    private var aPos = 0; private var aNormal = 0
    private var uMVP = 0; private var uModel = 0; private var uSunPos = 0; private var uCamPos = 0
    private var uSunlight = 0; private var uHull = 0
    private lateinit var shipMesh: FloatBuffer
    private var shipVerts = 0
    private lateinit var stationMesh: FloatBuffer
    private var stationVerts = 0
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
        uHull = GLES20.glGetUniformLocation(prog, "uHull")
        val ship = SpaceTraffic.buildShipMesh()
        shipMesh = GlAssets.floatBuffer(ship)
        shipVerts = ship.size / 6
        val st = buildStation()
        stationMesh = GlAssets.floatBuffer(st)
        stationVerts = st.size / 6
    }

    /** Habitat ring (torus approx) + spine + docking arm reaching toward the ship. */
    private fun buildStation(): FloatArray {
        val tris = ArrayList<Float>()
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
            nx /= l; ny /= l; nz /= l
            for (p in arrayOf(a, b, c)) {
                tris.add(p[0]); tris.add(p[1]); tris.add(p[2])
                tris.add(nx); tris.add(ny); tris.add(nz)
            }
        }
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
            val v = arrayOf(
                floatArrayOf(cx - hx, cy - hy, cz - hz), floatArrayOf(cx + hx, cy - hy, cz - hz),
                floatArrayOf(cx + hx, cy + hy, cz - hz), floatArrayOf(cx - hx, cy + hy, cz - hz),
                floatArrayOf(cx - hx, cy - hy, cz + hz), floatArrayOf(cx + hx, cy - hy, cz + hz),
                floatArrayOf(cx + hx, cy + hy, cz + hz), floatArrayOf(cx - hx, cy + hy, cz + hz))
            val f = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(5, 4, 7, 6), intArrayOf(4, 0, 3, 7),
                intArrayOf(1, 5, 6, 2), intArrayOf(3, 2, 6, 7), intArrayOf(4, 5, 1, 0))
            for (q in f) { tri(v[q[0]], v[q[1]], v[q[2]]); tri(v[q[0]], v[q[2]], v[q[3]]) }
        }
        // habitat ring: 18 segments of box-tube, radius 3.4
        val segs = 18
        for (i in 0 until segs) {
            val a0 = 2.0 * Math.PI * i / segs
            val a1 = 2.0 * Math.PI * (i + 1) / segs
            val x0 = (cos(a0) * 3.4).toFloat(); val y0 = (sin(a0) * 3.4).toFloat()
            val x1 = (cos(a1) * 3.4).toFloat(); val y1 = (sin(a1) * 3.4).toFloat()
            // thin connecting boxes approximating the torus tube
            box((x0 + x1) / 2f, (y0 + y1) / 2f, 0f, 0.42f, 0.42f, 0.26f)
        }
        // spine + hub + docking arm (arm reaches -X toward the ship)
        box(0f, 0f, 0f, 0.55f, 0.55f, 1.6f)                 // hub
        box(0f, 0f, 0f, 0.22f, 3.4f, 0.22f)                 // vertical spine through ring
        box(-2.4f, -0.5f, 0.4f, 2.0f, 0.14f, 0.14f)         // docking arm
        box(-4.4f, -0.5f, 0.4f, 0.3f, 0.3f, 0.3f)           // docking clamp
        return tris.toFloatArray()
    }

    /** Visible whenever we're near home (intro + departure look-backs). */
    fun draw(vp: FloatArray, camPos: FloatArray, sunPos: FloatArray, sunlight: Float, timeSec: Float,
             shipDeparted: Boolean) {
        val d = dist(camPos, STATION_POS)
        if (d > 280f) return
        GLES20.glUseProgram(prog)
        GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
        GLES20.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES20.glUniform1f(uSunlight, sunlight)

        // station: slow majestic roll
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, STATION_POS[0], STATION_POS[1], STATION_POS[2])
        Matrix.rotateM(model, 0, 24f, 0.2f, 0.3f, 1f)
        Matrix.rotateM(model, 0, timeSec * 2.4f, 0f, 0f, 1f)
        drawMesh(vp, stationMesh, stationVerts, floatArrayOf(0.55f, 0.58f, 0.64f))

        // the player's ship, hard-docked to the clamp until departure
        if (!shipDeparted) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, SHIP_POS[0], SHIP_POS[1], SHIP_POS[2])
            Matrix.rotateM(model, 0, 58f, 0f, 1f, 0f)        // nose toward the clamp
            Matrix.rotateM(model, 0, timeSec * 0f, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, 2.6f, 2.6f, 2.6f)
            drawMesh(vp, shipMesh, shipVerts, floatArrayOf(0.42f, 0.52f, 0.68f))  // pale blue hull
        }
    }

    private fun drawMesh(vp: FloatArray, mesh: FloatBuffer, verts: Int, hull: FloatArray) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3fv(uHull, 1, hull, 0)
        mesh.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, mesh)
        GLES20.glEnableVertexAttribArray(aPos)
        mesh.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, mesh)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
