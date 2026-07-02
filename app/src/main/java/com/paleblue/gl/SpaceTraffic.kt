package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets
import com.paleblue.RideState
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Civilian space traffic v3 — a small living ecosystem:
 *  · SLEEK shuttles/couriers, boxy FREIGHTERS with cargo pods, SATELLITES with
 *    solar wings, spiky PROBES, and a menacing ALIEN manta (events only).
 *  · World-anchored trajectories: inclined orbits, Bézier transfer lanes,
 *    S-curve couriers, plus artificial satellites around Earth/Mars/Jupiter/
 *    Neptune. Banking in turns, red/green wingtip strobes, white beacons.
 *  · COLLISION AVOIDANCE: any craft that would pass within [SAFE_R] of the
 *    player is smoothly pushed out along the radial — near misses stay near.
 *  · EVENT CRAFT (RideState.eventKind): rogue freighter crossing the bow,
 *    alien strafing spiral, or an allied cruiser flying formation — all in the
 *    SHIP-RELATIVE frame so the drama works at any rail speed.
 */
class SpaceTraffic {

    companion object {
        const val SAFE_R = 11f

        private fun addTri(tris: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
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

        private fun addBox(t: ArrayList<Float>, cx: Float, cy: Float, cz: Float,
                           hx: Float, hy: Float, hz: Float) {
            val v = arrayOf(
                floatArrayOf(cx - hx, cy - hy, cz - hz), floatArrayOf(cx + hx, cy - hy, cz - hz),
                floatArrayOf(cx + hx, cy + hy, cz - hz), floatArrayOf(cx - hx, cy + hy, cz - hz),
                floatArrayOf(cx - hx, cy - hy, cz + hz), floatArrayOf(cx + hx, cy - hy, cz + hz),
                floatArrayOf(cx + hx, cy + hy, cz + hz), floatArrayOf(cx - hx, cy + hy, cz + hz))
            val f = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(5, 4, 7, 6), intArrayOf(4, 0, 3, 7),
                intArrayOf(1, 5, 6, 2), intArrayOf(3, 2, 6, 7), intArrayOf(4, 5, 1, 0))
            for (q in f) { addTri(t, v[q[0]], v[q[1]], v[q[2]]); addTri(t, v[q[0]], v[q[2]], v[q[3]]) }
        }

        /** Sleek passenger/courier hull (also the player's exterior in DockScene). */
        fun buildShipMesh(): FloatArray {
            val t = ArrayList<Float>()
            val nose = floatArrayOf(0f, 0f, -1.7f)
            val mT = floatArrayOf(0f, 0.34f, -0.1f); val mB = floatArrayOf(0f, -0.32f, -0.1f)
            val mL = floatArrayOf(-0.42f, 0f, -0.1f); val mR = floatArrayOf(0.42f, 0f, -0.1f)
            val tT = floatArrayOf(0f, 0.30f, 1.1f); val tB = floatArrayOf(0f, -0.26f, 1.1f)
            val tL = floatArrayOf(-0.30f, 0.02f, 1.1f); val tR = floatArrayOf(0.30f, 0.02f, 1.1f)
            addTri(t, nose, mT, mR); addTri(t, nose, mR, mB); addTri(t, nose, mB, mL); addTri(t, nose, mL, mT)
            addTri(t, mT, tT, tR); addTri(t, mT, tR, mR); addTri(t, mR, tR, tB); addTri(t, mR, tB, mB)
            addTri(t, mB, tB, tL); addTri(t, mB, tL, mL); addTri(t, mL, tL, tT); addTri(t, mL, tT, mT)
            addTri(t, tT, tB, tL); addTri(t, tT, tR, tB)
            val wL = floatArrayOf(-1.25f, -0.06f, 0.75f); val wR = floatArrayOf(1.25f, -0.06f, 0.75f)
            addTri(t, mL, wL, tL); addTri(t, mR, tR, wR)
            addTri(t, mT, floatArrayOf(0f, 0.78f, 0.85f), tT)
            return t.toFloatArray()
        }

        /** Boxy long-hauler: cab, spine, four cargo pods, engine block. */
        fun buildFreighterMesh(): FloatArray {
            val t = ArrayList<Float>()
            addBox(t, 0f, 0.05f, -1.35f, 0.30f, 0.26f, 0.35f)          // cab
            addTri(t, floatArrayOf(0f, 0.05f, -2.0f),                   // cab nose wedge
                floatArrayOf(-0.30f, -0.18f, -1.7f), floatArrayOf(0.30f, -0.18f, -1.7f))
            addBox(t, 0f, -0.05f, 0.1f, 0.10f, 0.10f, 1.25f)           // spine
            addBox(t, -0.38f, 0.16f, -0.35f, 0.26f, 0.20f, 0.48f)      // pods
            addBox(t, 0.38f, 0.16f, -0.35f, 0.26f, 0.20f, 0.48f)
            addBox(t, -0.38f, 0.16f, 0.65f, 0.26f, 0.20f, 0.48f)
            addBox(t, 0.38f, 0.16f, 0.65f, 0.26f, 0.20f, 0.48f)
            addBox(t, 0f, -0.28f, 0.15f, 0.30f, 0.12f, 0.9f)           // belly tank
            addBox(t, 0f, 0f, 1.45f, 0.34f, 0.30f, 0.22f)              // engine block
            return t.toFloatArray()
        }

        /** Satellite: bus + two long solar wings + antenna boom. */
        fun buildSatelliteMesh(): FloatArray {
            val t = ArrayList<Float>()
            addBox(t, 0f, 0f, 0f, 0.28f, 0.28f, 0.38f)                 // bus
            addBox(t, -1.35f, 0f, 0f, 1.05f, 0.02f, 0.32f)             // port panel
            addBox(t, 1.35f, 0f, 0f, 1.05f, 0.02f, 0.32f)              // starboard panel
            addBox(t, 0f, 0.42f, 0f, 0.03f, 0.16f, 0.03f)              // boom
            addTri(t, floatArrayOf(0f, 0.72f, -0.18f),                  // dish petal
                floatArrayOf(-0.2f, 0.55f, 0.12f), floatArrayOf(0.2f, 0.55f, 0.12f))
            return t.toFloatArray()
        }

        /** Deep-space probe: octahedron core + antenna spike. */
        fun buildProbeMesh(): FloatArray {
            val t = ArrayList<Float>()
            val xp = floatArrayOf(0.4f, 0f, 0f); val xn = floatArrayOf(-0.4f, 0f, 0f)
            val yp = floatArrayOf(0f, 0.4f, 0f); val yn = floatArrayOf(0f, -0.4f, 0f)
            val zp = floatArrayOf(0f, 0f, 0.4f); val zn = floatArrayOf(0f, 0f, -0.4f)
            addTri(t, yp, zn, xp); addTri(t, yp, xp, zp); addTri(t, yp, zp, xn); addTri(t, yp, xn, zn)
            addTri(t, yn, xp, zn); addTri(t, yn, zp, xp); addTri(t, yn, xn, zp); addTri(t, yn, zn, xn)
            addBox(t, 0f, 0f, -0.75f, 0.03f, 0.03f, 0.35f)             // antenna spike
            return t.toFloatArray()
        }

        /** Hostile manta: wide angular wedge, wingtips raked down. Events only. */
        fun buildAlienMesh(): FloatArray {
            val t = ArrayList<Float>()
            val nose = floatArrayOf(0f, 0.02f, -1.9f)
            val hub = floatArrayOf(0f, 0.22f, 0.4f)
            val tail = floatArrayOf(0f, -0.06f, 1.0f)
            val wl = floatArrayOf(-2.1f, -0.35f, 0.9f); val wr = floatArrayOf(2.1f, -0.35f, 0.9f)
            val kl = floatArrayOf(-0.7f, -0.16f, 0.1f); val kr = floatArrayOf(0.7f, -0.16f, 0.1f)
            addTri(t, nose, hub, kl); addTri(t, nose, kr, hub)
            addTri(t, hub, wl, kl); addTri(t, hub, kr, wr)
            addTri(t, kl, wl, tail); addTri(t, kr, tail, wr)
            addTri(t, nose, kl, tail); addTri(t, nose, tail, kr)
            addTri(t, hub, tail, wl); addTri(t, hub, wr, tail)
            return t.toFloatArray()
        }
    }

    // ---------- shaders ----------
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
        uniform float uFade;
        uniform float uEmissive;       // alien hulls smolder
        void main() {
            vec3 N = normalize(vN);
            vec3 L = normalize(uSunPos - vWorld);
            vec3 V = normalize(uCamPos - vWorld);
            float ndl = max(dot(N, L), 0.0);
            float rim = pow(1.0 - max(dot(N, V), 0.0), 2.5);
            vec3 col = uHull * (0.18 + 1.0 * ndl * min(uSunlight, 1.6))
                     + vec3(0.30, 0.45, 0.62) * rim * 0.4
                     + uHull * 0.10
                     + vec3(0.2, 1.0, 0.35) * uEmissive * (0.35 + rim);
            gl_FragColor = vec4(col * uFade, 1.0);
        }
    """
    private val pvsrc = """
        uniform mat4 uVP;
        attribute vec4 aPosSize;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            vColor = aColor;
            gl_Position = uVP * vec4(aPosSize.xyz, 1.0);
            gl_PointSize = aPosSize.w;
        }
    """
    private val pfsrc = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            float a = smoothstep(0.5, 0.08, length(gl_PointCoord - vec2(0.5)));
            gl_FragColor = vec4(vColor.rgb, vColor.a * a);
        }
    """

    // ---------- routes ----------
    private class Route(
        val kind: Int,                    // 0 orbit, 1 bezier lane, 2 S-curve
        val mesh: Int,                    // 0 sleek, 1 freighter, 2 satellite, 3 probe
        val a: FloatArray, val b: FloatArray = a, val c: FloatArray = a,
        val radius: Float = 0f, val incl: Float = 0f, val node: Float = 0f,
        val period: Float = 60f, val phase: Float = 0f, val dir: Float = 1f,
        val scale: Float = 1f, val hull: FloatArray, val sAmp: Float = 0f
    )

    private val routes = ArrayList<Route>()
    private val obstacles = ArrayList<FloatArray>()   // [x, y, z, radius] of every body
    private var prog = 0; private var pProg = 0
    private var aPos = 0; private var aNormal = 0
    private var uVP = 0; private var uModel = 0; private var uSunPos = 0; private var uCamPos = 0
    private var uSunlight = 0; private var uHull = 0; private var uFade = 0; private var uEmissive = 0
    private var paPosSize = 0; private var paColor = 0; private var puVP = 0
    private val meshes = arrayOfNulls<FloatBuffer>(5)
    private val meshVerts = IntArray(5)
    private val pointData = FloatArray(96 * 8)
    private lateinit var pointBuf: FloatBuffer
    private val model = FloatArray(16)
    private val mvpM = FloatArray(16)

    private val STEEL = floatArrayOf(0.62f, 0.66f, 0.72f)
    private val WHITE = floatArrayOf(0.80f, 0.80f, 0.84f)
    private val RUST = floatArrayOf(0.72f, 0.48f, 0.34f)
    private val TEAL = floatArrayOf(0.42f, 0.68f, 0.66f)
    private val GOLD = floatArrayOf(0.78f, 0.66f, 0.38f)
    private val ALIEN_HULL = floatArrayOf(0.08f, 0.14f, 0.10f)
    private val ALLY_HULLS = arrayOf(
        floatArrayOf(0.85f, 0.28f, 0.22f),   // CNSA red
        floatArrayOf(0.30f, 0.42f, 0.80f),   // ESA blue
        floatArrayOf(0.90f, 0.62f, 0.25f),   // ISRO saffron
        floatArrayOf(0.85f, 0.85f, 0.88f))   // JAXA white

    fun init(world: SolarSystem) {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        uVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uSunPos = GLES20.glGetUniformLocation(prog, "uSunPos")
        uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        uSunlight = GLES20.glGetUniformLocation(prog, "uSunlight")
        uHull = GLES20.glGetUniformLocation(prog, "uHull")
        uFade = GLES20.glGetUniformLocation(prog, "uFade")
        uEmissive = GLES20.glGetUniformLocation(prog, "uEmissive")
        pProg = GlAssets.compileProgram(pvsrc, pfsrc)
        paPosSize = GLES20.glGetAttribLocation(pProg, "aPosSize")
        paColor = GLES20.glGetAttribLocation(pProg, "aColor")
        puVP = GLES20.glGetUniformLocation(pProg, "uVP")
        val ms = arrayOf(buildShipMesh(), buildFreighterMesh(), buildSatelliteMesh(),
            buildProbeMesh(), buildAlienMesh())
        for (i in ms.indices) { meshes[i] = GlAssets.floatBuffer(ms[i]); meshVerts[i] = ms[i].size / 6 }
        pointBuf = GlAssets.floatBuffer(pointData)

        // NOTHING FLIES THROUGH A PLANET: register every body as a hard obstacle
        for (b in world.bodies) {
            obstacles.add(floatArrayOf(b.pos[0], b.pos[1], b.pos[2], b.radius))
        }

        fun body(name: String) = world.bodies.first { it.name == name }.pos
        val earth = body("Earth"); val luna = body("Luna"); val venus = body("Venus")
        val mars = body("Mars"); val jup = body("Jupiter"); val io = body("Io")
        val eur = body("Europa"); val sat = body("Saturn"); val titan = body("Titan")
        val nep = body("Neptune"); val trap = body("TRAPPIST-1")
        // --- crewed traffic ---
        routes += Route(0, 0, earth, radius = 8.5f, incl = 0.45f, node = 0.4f, period = 42f,
            scale = 0.55f, hull = WHITE)
        routes += Route(0, 0, earth, radius = 12.5f, incl = 1.15f, node = 2.1f, period = 74f,
            dir = -1f, scale = 1.2f, hull = STEEL, phase = 0.31f)
        routes += Route(1, 1, earth, luna, floatArrayOf(34f, 16f, -64f), period = 95f,
            scale = 1.5f, hull = RUST, phase = 0.12f)
        routes += Route(1, 1, luna, earth, floatArrayOf(-16f, -10f, -70f), period = 110f,
            scale = 1.3f, hull = GOLD, phase = 0.55f)
        routes += Route(0, 3, luna, radius = 3.6f, incl = 0.8f, node = 1.2f, period = 26f,
            scale = 0.5f, hull = TEAL, phase = 0.7f)
        routes += Route(0, 0, venus, radius = 6.5f, incl = 0.95f, node = 0.2f, period = 48f,
            dir = -1f, scale = 0.8f, hull = WHITE, phase = 0.42f)
        routes += Route(2, 0, floatArrayOf(30f, 12f, -336f), floatArrayOf(-70f, -6f, -430f),
            period = 70f, scale = 0.9f, hull = STEEL, sAmp = 9f, phase = 0.2f)
        routes += Route(0, 1, jup, radius = 30f, incl = 0.28f, node = 2.8f, period = 120f,
            scale = 2.2f, hull = GOLD, phase = 0.05f)
        routes += Route(1, 0, io, eur, floatArrayOf(20f, 10f, -604f), period = 55f,
            scale = 0.6f, hull = TEAL, phase = 0.44f)
        routes += Route(0, 0, sat, radius = 25f, incl = 0.06f, node = 0f, period = 90f,
            scale = 1.1f, hull = WHITE, phase = 0.6f)
        routes += Route(1, 1, sat, titan, floatArrayOf(30f, 12f, -740f), period = 80f,
            scale = 1.2f, hull = RUST, phase = 0.83f)
        routes += Route(2, 1, floatArrayOf(-30f, 8f, -950f), floatArrayOf(24f, -6f, -1060f),
            period = 130f, scale = 1.7f, hull = STEEL, sAmp = 12f, phase = 0.5f)
        routes += Route(0, 3, trap, radius = 14f, incl = 0.7f, node = 1.7f, period = 66f,
            scale = 0.7f, hull = TEAL, phase = 0.25f)
        // --- artificial satellites: planets wear their hardware ---
        routes += Route(0, 2, earth, radius = 7.2f, incl = 1.45f, node = 0.9f, period = 33f,
            scale = 0.45f, hull = STEEL, phase = 0.15f)                 // polar comms sat
        routes += Route(0, 2, earth, radius = 10.4f, incl = 0.12f, node = 0f, period = 58f,
            scale = 0.55f, hull = GOLD, phase = 0.62f)                  // geostationary relay
        routes += Route(0, 2, mars, radius = 4.2f, incl = 0.6f, node = 1.9f, period = 30f,
            scale = 0.4f, hull = WHITE, phase = 0.4f)                   // areo-survey sat
        routes += Route(0, 2, jup, radius = 26f, incl = 0.9f, node = 1.1f, period = 105f,
            dir = -1f, scale = 0.8f, hull = TEAL, phase = 0.8f)         // radiation observer
        routes += Route(0, 2, nep, radius = 11f, incl = 0.5f, node = 2.4f, period = 62f,
            scale = 0.6f, hull = STEEL, phase = 0.33f)                  // Triton relay
    }

    // ---------- path math ----------
    private fun posAt(r: Route, tSec: Float, out: FloatArray) {
        when (r.kind) {
            0 -> {
                val th = r.dir * 2f * PI.toFloat() * (tSec / r.period + r.phase)
                val nx = sin(r.incl) * cos(r.node); val ny = cos(r.incl); val nz = sin(r.incl) * sin(r.node)
                var ux = -nz; var uy = 0f; var uz = nx
                val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-4f)
                ux /= ul; uy /= ul; uz /= ul
                val vx = ny * uz - nz * uy; val vy = nz * ux - nx * uz; val vz = nx * uy - ny * ux
                out[0] = r.a[0] + r.radius * (cos(th) * ux + sin(th) * vx)
                out[1] = r.a[1] + r.radius * (cos(th) * uy + sin(th) * vy)
                out[2] = r.a[2] + r.radius * (cos(th) * uz + sin(th) * vz)
            }
            1 -> {
                val s = frac(tSec / r.period + r.phase)
                val m = 1f - s
                for (k in 0..2) out[k] = m * m * r.a[k] + 2f * m * s * r.c[k] + s * s * r.b[k]
            }
            else -> {
                val s = frac(tSec / r.period + r.phase)
                val dx = r.b[0] - r.a[0]; val dy = r.b[1] - r.a[1]; val dz = r.b[2] - r.a[2]
                val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
                val px = -dz / l; val pz = dx / l
                val weave = sin(s * 4f * PI.toFloat()) * r.sAmp
                val lift = sin(s * 2f * PI.toFloat()) * r.sAmp * 0.35f
                out[0] = r.a[0] + dx * s + px * weave
                out[1] = r.a[1] + dy * s + lift
                out[2] = r.a[2] + dz * s + pz * weave
            }
        }
    }

    private fun frac(x: Float) = x - kotlin.math.floor(x)
    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Smoothly slides a position out of any body it would intersect. */
    private fun pushOutOfBodies(p: FloatArray, margin: Float) {
        for (o in obstacles) {
            val dx = p[0] - o[0]; val dy = p[1] - o[1]; val dz = p[2] - o[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val clear = o[3] * 1.25f + margin
            if (d < clear && d > 1e-4f) {
                val k = clear / d
                p[0] = o[0] + dx * k; p[1] = o[1] + dy * k; p[2] = o[2] + dz * k
            }
        }
    }

    private fun seamFade(r: Route, tSec: Float): Float {
        if (r.kind == 0) return 1f
        val s = frac(tSec / r.period + r.phase)
        return (smooth(0f, 0.06f, s) * smooth(1f, 0.94f, s)).coerceIn(0f, 1f)
    }

    // ---------- shared craft renderer ----------
    private fun buildModel(pos: FloatArray, f: FloatArray, bank: Float, scale: Float) {
        var r0 = -f[2]; var r1 = 0f; var r2 = f[0]
        val rl = sqrt(r0 * r0 + r1 * r1 + r2 * r2).coerceAtLeast(1e-4f)
        r0 /= rl; r1 /= rl; r2 /= rl
        var u0 = r1 * f[2] - r2 * f[1]; var u1 = r2 * f[0] - r0 * f[2]; var u2 = r0 * f[1] - r1 * f[0]
        val cb = cos(bank); val sb = sin(bank)
        val upx = u0 * cb + r0 * sb; val upy = u1 * cb + r1 * sb; val upz = u2 * cb + r2 * sb
        r0 = f[1] * upz - f[2] * upy; r1 = f[2] * upx - f[0] * upz; r2 = f[0] * upy - f[1] * upx
        model[0] = r0 * scale; model[1] = r1 * scale; model[2] = r2 * scale; model[3] = 0f
        model[4] = upx * scale; model[5] = upy * scale; model[6] = upz * scale; model[7] = 0f
        model[8] = -f[0] * scale; model[9] = -f[1] * scale; model[10] = -f[2] * scale; model[11] = 0f
        model[12] = pos[0]; model[13] = pos[1]; model[14] = pos[2]; model[15] = 1f
    }

    private fun renderCraft(vp: FloatArray, meshIdx: Int, camPos: FloatArray, sunPos: FloatArray,
                            sunlight: Float, hull: FloatArray, fade: Float, emissive: Float) {
        val mesh = meshes[meshIdx] ?: return
        GLES20.glUseProgram(prog)
        android.opengl.Matrix.multiplyMM(mvpM, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uVP, 1, false, mvpM, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
        GLES20.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES20.glUniform1f(uSunlight, sunlight)
        GLES20.glUniform3fv(uHull, 1, hull, 0)
        GLES20.glUniform1f(uFade, fade)
        GLES20.glUniform1f(uEmissive, emissive)
        mesh.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, mesh)
        GLES20.glEnableVertexAttribArray(aPos)
        mesh.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, mesh)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, meshVerts[meshIdx])
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun localToWorld(lx: Float, ly: Float, lz: Float, out: FloatArray) {
        out[0] = model[0] * lx + model[4] * ly + model[8] * lz + model[12]
        out[1] = model[1] * lx + model[5] * ly + model[9] * lz + model[13]
        out[2] = model[2] * lx + model[6] * ly + model[10] * lz + model[14]
    }

    private var pi = 0
    private fun putPoint(p: FloatArray, size: Float, r: Float, g: Float, b: Float, a: Float) {
        if (pi + 8 > pointData.size) return
        pointData[pi++] = p[0]; pointData[pi++] = p[1]; pointData[pi++] = p[2]; pointData[pi++] = size
        pointData[pi++] = r; pointData[pi++] = g; pointData[pi++] = b; pointData[pi++] = a
    }

    private fun flushPoints(vp: FloatArray) {
        if (pi == 0) return
        pointBuf.position(0); pointBuf.put(pointData, 0, pi); pointBuf.position(0)
        GLES20.glUseProgram(pProg)
        GLES20.glUniformMatrix4fv(puVP, 1, false, vp, 0)
        pointBuf.position(0)
        GLES20.glVertexAttribPointer(paPosSize, 4, GLES20.GL_FLOAT, false, 32, pointBuf)
        GLES20.glEnableVertexAttribArray(paPosSize)
        pointBuf.position(4)
        GLES20.glVertexAttribPointer(paColor, 4, GLES20.GL_FLOAT, false, 32, pointBuf)
        GLES20.glEnableVertexAttribArray(paColor)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pi / 8)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(paPosSize)
        GLES20.glDisableVertexAttribArray(paColor)
        pi = 0
    }

    // ---------- scheduled traffic ----------
    private val p0 = FloatArray(3); private val p1 = FloatArray(3); private val p2 = FloatArray(3)
    private val tmp = FloatArray(3)

    fun draw(vp: FloatArray, camPos: FloatArray, sunPos: FloatArray, sunlight: Float, timeSec: Float) {
        for ((idx, r) in routes.withIndex()) {
            posAt(r, timeSec, p1)
            pushOutOfBodies(p1, r.scale * 1.6f)
            var dx = p1[0] - camPos[0]; var dy = p1[1] - camPos[1]; var dz = p1[2] - camPos[2]
            var d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 230f) continue
            // COLLISION AVOIDANCE: soft radial push keeps everyone off the bridge
            if (d < SAFE_R) {
                val push = (SAFE_R - d) / d.coerceAtLeast(0.5f)
                p1[0] += dx * push; p1[1] += dy * push; p1[2] += dz * push
                d = SAFE_R
            }
            val fade = smooth(230f, 150f, d) * seamFade(r, timeSec)
            if (fade < 0.05f) continue
            posAt(r, timeSec - 0.4f, p0); posAt(r, timeSec + 0.4f, p2)
            pushOutOfBodies(p0, r.scale * 1.6f); pushOutOfBodies(p2, r.scale * 1.6f)
            var fx = p2[0] - p0[0]; var fy = p2[1] - p0[1]; var fz = p2[2] - p0[2]
            val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-4f)
            fx /= fl; fy /= fl; fz /= fl
            val ax = (p2[0] - 2f * p1[0] + p0[0]); val az = (p2[2] - 2f * p1[2] + p0[2])
            val bank = if (r.mesh == 2) 0f else ((ax * -fz + az * fx) * 2.2f).coerceIn(-0.7f, 0.7f)
            buildModel(p1, floatArrayOf(fx, fy, fz), bank, r.scale)
            renderCraft(vp, r.mesh, camPos, sunPos, sunlight, r.hull, fade, 0f)

            val ph = idx * 1.7f
            val sizeBase = (140f / d.coerceAtLeast(8f)).coerceIn(2.5f, 10f)
            if (r.mesh != 2) {          // satellites don't strobe like airliners
                val redOn = if (sin(timeSec * 4f + ph) > 0.35f) 1f else 0.12f
                localToWorld(-1.25f, -0.06f, 0.75f, tmp)
                putPoint(tmp, sizeBase, 1f, 0.12f, 0.08f, redOn * fade)
                val grnOn = if (sin(timeSec * 4f + ph + PI.toFloat()) > 0.35f) 1f else 0.12f
                localToWorld(1.25f, -0.06f, 0.75f, tmp)
                putPoint(tmp, sizeBase, 0.15f, 1f, 0.25f, grnOn * fade)
                localToWorld(0f, 0f, 1.35f, tmp)
                putPoint(tmp, sizeBase * 1.6f, 0.45f, 0.8f, 1f, 0.8f * fade)
            } else {                    // satellites: slow amber telemetry blink
                val on = if (sin(timeSec * 1.6f + ph) > 0.6f) 1f else 0.08f
                localToWorld(0f, 0.45f, 0f, tmp)
                putPoint(tmp, sizeBase * 0.8f, 1f, 0.75f, 0.3f, on * fade)
            }
            val bt = frac((timeSec + ph) / 2.2f)
            val beacon = if (bt < 0.06f || (bt > 0.14f && bt < 0.20f)) 1f else 0.05f
            localToWorld(0f, 0.6f, 0.4f, tmp)
            putPoint(tmp, sizeBase * 0.9f, 1f, 1f, 1f, beacon * fade)
        }
        drawEvent(vp, camPos, sunPos, sunlight, timeSec)
        flushPoints(vp)
    }

    // ---------- event craft (ship-relative frame: drama at any rail speed) ----------
    private fun drawEvent(vp: FloatArray, camPos: FloatArray, sunPos: FloatArray,
                          sunlight: Float, timeSec: Float) {
        val kind = RideState.eventKind
        if (kind == 0) return
        val now = System.currentTimeMillis()
        val dur = RideState.eventDurMs.coerceAtLeast(1000L)
        val p = ((now - RideState.eventStartWallMs).toFloat() / dur)
        if (p < 0f || p > 1f) return
        val yaw = RideState.eventShipYaw
        val f = floatArrayOf(sin(yaw), 0f, -cos(yaw))
        val r = floatArrayOf(cos(yaw), 0f, sin(yaw))
        fun at(fw: Float, rt: Float, up: Float) = floatArrayOf(
            camPos[0] + f[0] * fw + r[0] * rt,
            camPos[1] + up + f[1] * fw,
            camPos[2] + f[2] * fw + r[2] * rt)
        when (kind) {
            1 -> {  // rogue hauler slashing across the bow, closest ~14 units
                val pos = lerp(at(70f, 52f, 9f), at(-42f, -48f, -7f), p)
                pushOutOfBodies(pos, 4f)
                val vel = dirBetween(at(70f, 52f, 9f), at(-42f, -48f, -7f))
                buildModel(pos, vel, -0.5f, 2.8f)
                renderCraft(vp, 1, camPos, sunPos, sunlight, RUST, 1f, 0f)
                localToWorld(0f, 0f, 1.45f, tmp)
                putPoint(tmp, 9f, 1f, 0.6f, 0.2f, 0.9f)
            }
            2 -> {  // alien manta: LARGE and unmistakable; guns fall silent once
                    // the Captain's hail lands (p > 0.5) and diplomacy takes over
                val a = p * 4f * PI.toFloat()
                val rad = 26f - 15f * sin(p * PI.toFloat())      // sweeps in to ~11 units
                val pos = at(sin(a) * rad, cos(a) * rad, 5f * cos(a * 0.5f))
                pushOutOfBodies(pos, 5f)
                val aN = (p + 0.02f) * 4f * PI.toFloat()
                val radN = 26f - 15f * sin((p + 0.02f) * PI.toFloat())
                val posN = at(sin(aN) * radN, cos(aN) * radN, 5f * cos(aN * 0.5f))
                buildModel(pos, dirBetween(pos, posN), 0.4f, 3.6f)
                renderCraft(vp, 4, camPos, sunPos, sunlight, ALIEN_HULL, 1f,
                    0.6f + 0.4f * sin(timeSec * 9f))
                // green drive aura — visible across the whole encounter
                localToWorld(0f, 0.1f, 0.5f, tmp)
                putPoint(tmp, 17f, 0.3f, 1f, 0.4f, 0.95f)
                localToWorld(-2.1f, -0.35f, 0.9f, tmp)
                putPoint(tmp, 8f, 0.25f, 0.9f, 0.35f, 0.8f)
                localToWorld(2.1f, -0.35f, 0.9f, tmp)
                putPoint(tmp, 8f, 0.25f, 0.9f, 0.35f, 0.8f)
                // weapon bolts ONLY before the negotiation (p < 0.5), aimed off-shield
                if (p < 0.5f && frac(p * 6f) < 0.25f) {
                    val bt = frac(p * 6f) / 0.25f
                    val bolt = lerp(pos, at(-6f, 9f, -3f), bt)
                    putPoint(bolt, 7f, 0.35f, 1f, 0.3f, (1f - bt) * 0.95f)
                }
                // after the peace: running lights blink a slow farewell pattern
                if (p > 0.72f) {
                    val on = if (sin(timeSec * 3f) > -0.2f) 0.9f else 0.1f
                    localToWorld(0f, 0.25f, -1.2f, tmp)
                    putPoint(tmp, 10f, 0.6f, 1f, 0.8f, on)
                }
            }
            3 -> {  // allied cruiser: approach -> hold formation abeam -> burn ahead
                val hull = ALLY_HULLS[RideState.eventSeed % ALLY_HULLS.size]
                val form = at(-2f, 15f, 1.5f)
                val pos = when {
                    p < 0.18f -> lerp(at(-70f, 45f, 6f), form, smooth(0f, 1f, p / 0.18f))
                    p < 0.85f -> floatArrayOf(form[0] + r[0] * sin(timeSec * 0.7f) * 0.6f,
                        form[1] + sin(timeSec * 0.5f) * 0.4f,
                        form[2] + r[2] * sin(timeSec * 0.7f) * 0.6f)
                    else -> lerp(form, at(160f, 30f, 8f), smooth(0f, 1f, (p - 0.85f) / 0.15f))
                }
                pushOutOfBodies(pos, 4f)
                buildModel(pos, f, 0f, 2.6f)
                renderCraft(vp, 1, camPos, sunPos, sunlight, hull, 1f, 0f)
                localToWorld(0f, 0f, 1.45f, tmp)
                putPoint(tmp, 8f, 0.5f, 0.8f, 1f, 0.85f)
                localToWorld(0f, 0.5f, -1.0f, tmp)   // hailing light
                putPoint(tmp, 6f, 1f, 1f, 1f, if (sin(timeSec * 6f) > 0f) 0.9f else 0.1f)
            }
        }
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
        a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    private fun dirBetween(a: FloatArray, b: FloatArray): FloatArray {
        val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-4f)
        return floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }
}
