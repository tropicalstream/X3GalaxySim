package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Space rocks & debris. Tumbling, irregular asteroid chunks scattered along
 * the corridor — dense through the main belt (u≈5.5–6.6) and the Kuiper belt
 * (u≈13–15), occasional strays elsewhere — plus small glinting debris shards
 * drifting near Earth's shipping lanes. Deterministic (seeded), world-anchored.
 */
class RockField {
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
        uniform float uSunlight;
        uniform vec3 uTint;
        void main() {
            vec3 N = normalize(vN);
            vec3 L = normalize(uSunPos - vWorld);
            float ndl = max(dot(N, L), 0.0);
            vec3 col = uTint * (0.10 + 0.95 * ndl * min(uSunlight, 1.5));
            gl_FragColor = vec4(col, 1.0);
        }
    """

    private class Rock(val pos: FloatArray, val scale: Float, val axis: FloatArray,
                       val rate: Float, val tint: FloatArray)

    private val rocks = ArrayList<Rock>()
    private var prog = 0
    private var aPos = 0; private var aNormal = 0
    private var uMVP = 0; private var uModel = 0; private var uSunPos = 0; private var uSunlight = 0; private var uTint = 0
    private lateinit var mesh: FloatBuffer
    private var meshVerts = 0
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun init(world: SolarSystem) {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uSunPos = GLES20.glGetUniformLocation(prog, "uSunPos")
        uSunlight = GLES20.glGetUniformLocation(prog, "uSunlight")
        uTint = GLES20.glGetUniformLocation(prog, "uTint")
        val m = buildRockMesh()
        mesh = GlAssets.floatBuffer(m)
        meshVerts = m.size / 6

        val rnd = Random(4242)
        fun scatter(u0: Float, u1: Float, count: Int, minS: Float, maxS: Float, grey: Boolean) {
            repeat(count) {
                val u = u0 + rnd.nextFloat() * (u1 - u0)
                val rail = world.camPosAt(u)
                val pos = floatArrayOf(
                    rail[0] + (rnd.nextFloat() * 2 - 1) * 65f,
                    rail[1] + (rnd.nextFloat() * 2 - 1) * 28f,
                    rail[2] + (rnd.nextFloat() * 2 - 1) * 65f)
                // keep a clear channel around the rail itself
                val d = dist(pos, rail)
                if (d < 14f) { pos[0] += 18f; pos[1] += 6f }
                // and never inside a planet or star
                for (b in world.bodies) {
                    val db = dist(pos, b.pos)
                    val clear = b.radius * 1.3f + 4f
                    if (db < clear && db > 1e-3f) {
                        val k = clear / db
                        pos[0] = b.pos[0] + (pos[0] - b.pos[0]) * k
                        pos[1] = b.pos[1] + (pos[1] - b.pos[1]) * k
                        pos[2] = b.pos[2] + (pos[2] - b.pos[2]) * k
                    }
                }
                val axis = norm(floatArrayOf(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1))
                val g = 0.35f + rnd.nextFloat() * 0.25f
                val tint = if (grey) floatArrayOf(g, g * 0.97f, g * 0.92f)
                           else floatArrayOf(g * 0.9f, g * 0.95f, g * 1.05f)   // icy
                rocks += Rock(pos, minS + rnd.nextFloat() * (maxS - minS), axis,
                    4f + rnd.nextFloat() * 14f, tint)
            }
        }
        scatter(5.4f, 6.7f, 22, 0.5f, 3.2f, grey = true)      // main belt
        scatter(13.0f, 15.1f, 16, 0.6f, 3.8f, grey = false)   // Kuiper (icy)
        scatter(0.4f, 5.0f, 6, 0.3f, 1.2f, grey = true)       // inner-system strays
        scatter(9.0f, 12.5f, 6, 0.4f, 1.6f, grey = false)     // outer strays
        // debris shards near Earth's shipping lanes
        val earth = world.bodies.first { it.name == "Earth" }.pos
        repeat(14) {
            val a = rnd.nextFloat() * 6.283f
            val rr = 9f + rnd.nextFloat() * 14f
            rocks += Rock(floatArrayOf(
                earth[0] + kotlin.math.cos(a) * rr,
                earth[1] + (rnd.nextFloat() * 2 - 1) * 5f,
                earth[2] + kotlin.math.sin(a) * rr),
                0.07f + rnd.nextFloat() * 0.18f,
                norm(floatArrayOf(rnd.nextFloat() * 2 - 1, 1f, rnd.nextFloat() * 2 - 1)),
                20f + rnd.nextFloat() * 40f,
                floatArrayOf(0.55f, 0.57f, 0.62f))            // metallic glints
        }
    }

    /** Jittered octahedron, subdivided once: cheap, lumpy, convincing. */
    private fun buildRockMesh(): FloatArray {
        val rnd = Random(7)
        fun j(v: FloatArray): FloatArray {
            val l = 0.75f + rnd.nextFloat() * 0.5f
            return floatArrayOf(v[0] * l, v[1] * l, v[2] * l)
        }
        val xp = floatArrayOf(1f, 0f, 0f); val xn = floatArrayOf(-1f, 0f, 0f)
        val yp = floatArrayOf(0f, 1f, 0f); val yn = floatArrayOf(0f, -1f, 0f)
        val zp = floatArrayOf(0f, 0f, 1f); val zn = floatArrayOf(0f, 0f, -1f)
        val faces = arrayOf(
            arrayOf(yp, zn, xp), arrayOf(yp, xp, zp), arrayOf(yp, zp, xn), arrayOf(yp, xn, zn),
            arrayOf(yn, xp, zn), arrayOf(yn, zp, xp), arrayOf(yn, xn, zp), arrayOf(yn, zn, xn))
        val tris = ArrayList<Float>()
        fun mid(a: FloatArray, b: FloatArray) = floatArrayOf(
            (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)
        fun emit(a: FloatArray, b: FloatArray, c: FloatArray) {
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
        for (fc in faces) {
            val a = j(fc[0]); val b = j(fc[1]); val c = j(fc[2])
            val ab = j(norm(mid(a, b))); val bc = j(norm(mid(b, c))); val ca = j(norm(mid(c, a)))
            emit(a, ab, ca); emit(ab, b, bc); emit(ca, bc, c); emit(ab, bc, ca)
        }
        return tris.toFloatArray()
    }

    fun draw(vp: FloatArray, camPos: FloatArray, sunPos: FloatArray, sunlight: Float, timeSec: Float) {
        var bound = false
        for (rock in rocks) {
            val d = dist(rock.pos, camPos)
            if (d > 220f) continue
            if (!bound) {
                GLES20.glUseProgram(prog)
                GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
                GLES20.glUniform1f(uSunlight, sunlight)
                mesh.position(0)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, mesh)
                GLES20.glEnableVertexAttribArray(aPos)
                mesh.position(3)
                GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, mesh)
                GLES20.glEnableVertexAttribArray(aNormal)
                bound = true
            }
            android.opengl.Matrix.setIdentityM(model, 0)
            android.opengl.Matrix.translateM(model, 0, rock.pos[0], rock.pos[1], rock.pos[2])
            android.opengl.Matrix.rotateM(model, 0, timeSec * rock.rate,
                rock.axis[0], rock.axis[1], rock.axis[2])
            android.opengl.Matrix.scaleM(model, 0, rock.scale, rock.scale, rock.scale)
            android.opengl.Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glUniform3fv(uTint, 1, rock.tint, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, meshVerts)
        }
        if (bound) {
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aNormal)
        }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    private fun norm(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
