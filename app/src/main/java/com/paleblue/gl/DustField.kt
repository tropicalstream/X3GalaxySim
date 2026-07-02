package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * THE FEELING OF SPEED. A field of near-space dust motes that stream past the
 * bridge with real parallax, driven by the ship's actual rail velocity.
 * At cruise they drift by like snow in headlights; at warp (uBeta > 0) they
 * stretch into the classic light-streaks. Recycled in a box around the camera
 * so the supply never runs out.
 */
class DustField(private val count: Int = 900) {
    companion object { const val HALF = 110f }

    private val vsrc = """
        uniform mat4 uVP;
        attribute vec4 aPosA;          // world xyz + alpha (head=1, tail=0)
        varying float vA;
        void main() {
            vA = aPosA.w;
            gl_Position = uVP * vec4(aPosA.xyz, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying float vA;
        uniform float uBright;
        uniform float uWarp;
        void main() {
            vec3 cruise = vec3(0.55, 0.62, 0.75);
            vec3 warp = vec3(0.65, 0.80, 1.25);
            gl_FragColor = vec4(mix(cruise, warp, uWarp) * uBright, vA * uBright);
        }
    """

    private var prog = 0
    private var aPosA = 0; private var uVP = 0; private var uBright = 0; private var uWarp = 0
    private val offsets = FloatArray(count * 3)      // relative to camera
    private val sizes = FloatArray(count)
    private lateinit var lines: FloatBuffer          // count * 2 verts * 4 floats
    private val scratch = FloatArray(count * 8)

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPosA = GLES20.glGetAttribLocation(prog, "aPosA")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uBright = GLES20.glGetUniformLocation(prog, "uBright")
        uWarp = GLES20.glGetUniformLocation(prog, "uWarp")
        val rnd = Random(11)
        for (i in 0 until count) {
            offsets[i * 3] = rnd.nextFloat() * 2 * HALF - HALF
            offsets[i * 3 + 1] = rnd.nextFloat() * 2 * HALF - HALF
            offsets[i * 3 + 2] = rnd.nextFloat() * 2 * HALF - HALF
            sizes[i] = 0.5f + rnd.nextFloat()
        }
        lines = ByteBuffer.allocateDirect(count * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    private fun wrap(v: Float): Float {
        var x = v
        while (x > HALF) x -= 2 * HALF
        while (x < -HALF) x += 2 * HALF
        return x
    }

    /**
     * [vel] = ship velocity in world units/s; [beta] = v/c warp factor.
     * Streak length and brightness grow with both, so cruising reads as gentle
     * drift and warp reads as a starfield tunnel.
     */
    fun updateAndDraw(vp: FloatArray, camPos: FloatArray, vel: FloatArray, dt: Float, beta: Float) {
        val speed = sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2])
        // streak: opposite of motion, scaled by speed and warp
        val streak = (speed * 0.14f * (1f + beta * 26f)).coerceIn(0.06f, 26f)
        var sx = 0f; var sy = 0f; var sz = 0f
        if (speed > 1e-4f) { sx = -vel[0] / speed * streak; sy = -vel[1] / speed * streak; sz = -vel[2] / speed * streak }
        var si = 0
        for (i in 0 until count) {
            var ox = offsets[i * 3] - vel[0] * dt
            var oy = offsets[i * 3 + 1] - vel[1] * dt
            var oz = offsets[i * 3 + 2] - vel[2] * dt
            ox = wrap(ox); oy = wrap(oy); oz = wrap(oz)
            offsets[i * 3] = ox; offsets[i * 3 + 1] = oy; offsets[i * 3 + 2] = oz
            // fade with distance from camera so motes pop near the glass
            val d2 = ox * ox + oy * oy + oz * oz
            val fade = (1f - d2 / (HALF * HALF)).coerceIn(0f, 1f) * sizes[i]
            val hx = camPos[0] + ox; val hy = camPos[1] + oy; val hz = camPos[2] + oz
            scratch[si++] = hx; scratch[si++] = hy; scratch[si++] = hz; scratch[si++] = fade
            scratch[si++] = hx + sx * sizes[i]; scratch[si++] = hy + sy * sizes[i]
            scratch[si++] = hz + sz * sizes[i]; scratch[si++] = 0f
        }
        lines.position(0); lines.put(scratch); lines.position(0)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform1f(uBright, (0.30f + speed * 0.25f + beta * 0.9f).coerceIn(0.25f, 1f))
        GLES20.glUniform1f(uWarp, beta.coerceIn(0f, 1f))
        GLES20.glVertexAttribPointer(aPosA, 4, GLES20.GL_FLOAT, false, 16, lines)
        GLES20.glEnableVertexAttribArray(aPosA)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count * 2)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPosA)
    }
}
