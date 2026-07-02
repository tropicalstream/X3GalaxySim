package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * PERSISTENT GALAXY MINIMAP (head-fixed HUD, bottom-right).
 *
 * Shows the ship's location within the galaxy at all times. Because the tour
 * spans 1 AU to 8.2 kpc, the map uses a LOG-RADIAL projection centered on Sol:
 *      r_map = ln(1 + d/d0) / ln(1 + dmax/d0),  d0 = 0.5 AU, dmax = 8.18 kpc.
 * Inner ~35% of the disc is the solar system; the rim is the galactic core.
 * Rendered: procedural spiral-galaxy backdrop (centered on Sgr A*'s bearing),
 * log-distance rings (1 AU / 10 AU / 100 AU / 1 pc / 1 kpc), the full rail
 * path (traveled part bright), and a pulsing ship blip with the Sol marker.
 * A one-line readout under the panel gives location + distance from Sol.
 */
class MiniMap {

    /** progress-unit -> (name, distance-from-Sol in AU, stylized bearing in degrees) */
    data class Waypoint(val u: Float, val name: String, val distAu: Double, val bearingDeg: Float)

    companion object {
        private const val D0 = 0.5
        private const val DMAX_AU = 1.69e9          // ~8.18 kpc
        private val DENOM = ln(1.0 + DMAX_AU / D0)
        const val AU_PER_PC = 206_265.0

        val WAYPOINTS = listOf(
            Waypoint(0f,  "EARTH ORBIT",     1.00,        90f),
            Waypoint(2f,  "LUNA",            1.00,        86f),
            Waypoint(3f,  "VENUS",           0.72,        70f),
            Waypoint(4f,  "SOL PERIHELION",  0.02,        50f),
            Waypoint(5f,  "MARS",            1.52,       100f),
            Waypoint(6f,  "ASTEROID BELT",   2.70,       106f),
            Waypoint(7f,  "JUPITER",         5.20,       115f),
            Waypoint(8f,  "JOVIAN MOONS",    5.25,       117f),
            Waypoint(9f,  "SATURN RINGS",    9.50,       125f),
            Waypoint(10f, "TITAN",           9.55,       127f),
            Waypoint(11f, "URANUS",         19.2,        135f),
            Waypoint(12f, "NEPTUNE",        30.1,        142f),
            Waypoint(13f, "PLUTO",          39.5,        148f),
            Waypoint(14f, "KUIPER BELT",    45.0,        152f),
            Waypoint(15f, "HELIOPAUSE",    120.0,        158f),
            Waypoint(16f, "INTERSTELLAR",  1.0e4,        170f),
            Waypoint(17.5f, "ORION NEBULA", 2.68e8,      200f),
            Waypoint(19f, "TRAPPIST-1",     2.56e6,      215f),
            Waypoint(20f, "HD 189733 b",    4.08e6,      225f),
            Waypoint(21.5f, "GALACTIC CORE TRANSIT", 4.1e8, 245f),
            Waypoint(23f, "SAGITTARIUS A*", 1.69e9,      260f),
            Waypoint(24f, "THE LOOK BACK",  1.68e9,      261f)
        )

        fun rMap(dAu: Double): Float = (ln(1.0 + dAu / D0) / DENOM).toFloat()

        fun toXY(w: Waypoint): FloatArray {
            val r = rMap(w.distAu) * 0.95f
            val a = w.bearingDeg * PI.toFloat() / 180f
            return floatArrayOf(r * cos(a), r * sin(a))
        }

        /** Piecewise interpolation of the ship position (map xy + distance + segment name). */
        fun shipAt(u: Float): Triple<FloatArray, Double, String> {
            val ws = WAYPOINTS
            if (u <= ws.first().u) return Triple(toXY(ws.first()), ws.first().distAu, ws.first().name)
            if (u >= ws.last().u) return Triple(toXY(ws.last()), ws.last().distAu, ws.last().name)
            for (i in 0 until ws.size - 1) {
                val a = ws[i]; val b = ws[i + 1]
                if (u >= a.u && u <= b.u) {
                    val f = if (b.u > a.u) (u - a.u) / (b.u - a.u) else 0f
                    val pa = toXY(a); val pb = toXY(b)
                    val xy = floatArrayOf(pa[0] + (pb[0] - pa[0]) * f, pa[1] + (pb[1] - pa[1]) * f)
                    // interpolate distance in log space for a sane readout
                    val d = Math.exp(ln(a.distAu + 1.0) + (ln(b.distAu + 1.0) - ln(a.distAu + 1.0)) * f) - 1.0
                    return Triple(xy, d, if (f < 0.85f) a.name else b.name)
                }
            }
            return Triple(toXY(ws.last()), ws.last().distAu, ws.last().name)
        }

        fun formatDist(dAu: Double): String = when {
            dAu < 1000.0 -> String.format("SOL +%.2f AU", dAu)
            dAu < AU_PER_PC * 1000.0 -> String.format("SOL +%.2f pc", dAu / AU_PER_PC)
            else -> String.format("SOL +%.2f kpc", dAu / AU_PER_PC / 1000.0)
        }
    }

    private val vsrc = """
        attribute vec2 aPos;           // -1..1 panel space
        uniform vec2 uCenter;          // NDC
        uniform vec2 uScale;           // NDC half extents
        varying vec2 vP;
        void main() { vP = aPos; gl_Position = vec4(uCenter + aPos * uScale, 0.0, 1.0); }
    """
    private val fsrc = """
        precision mediump float;
        varying vec2 vP;               // -1..1, map space (r_map*0.95 disc)
        uniform vec2 uPath[${WAYPOINTS.size}];
        uniform float uPathT[${WAYPOINTS.size}];
        uniform vec2 uShip;
        uniform float uProg;           // ride fraction 0..1
        uniform float uTime;
        uniform float uAlpha;

        float distSeg(vec2 p, vec2 a, vec2 b, out float t) {
            vec2 ab = b - a;
            t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
            return length(p - (a + ab * t));
        }
        void main() {
            float r = length(vP);
            if (r > 1.0) discard;
            float edge = smoothstep(1.0, 0.94, r);
            // backdrop
            vec3 col = vec3(0.012, 0.02, 0.035);
            // procedural spiral galaxy centered on Sgr A* bearing (260 deg, rim)
            vec2 gc = vec2(cos(4.5379), sin(4.5379)) * 0.95;
            vec2 q = vP - gc;
            float gr = length(q);
            float ga = atan(q.y, q.x);
            float arm = 0.5 + 0.5 * cos(2.0 * ga - 5.0 * log(max(gr, 0.05)));
            float disc = exp(-gr * 1.4);
            col += vec3(0.10, 0.09, 0.14) * disc * (0.35 + 0.65 * arm * arm);
            col += vec3(0.35, 0.25, 0.14) * exp(-gr * 9.0);        // core bulge
            // log-distance rings: 1 AU, 10 AU, 100 AU, 1 pc, 1 kpc  (r_map*0.95)
            float rings = 0.0;
            rings += 1.0 - smoothstep(0.0025, 0.006, abs(r - 0.0476));
            rings += 1.0 - smoothstep(0.0025, 0.006, abs(r - 0.1317));
            rings += 1.0 - smoothstep(0.0025, 0.006, abs(r - 0.2296));
            rings += 1.0 - smoothstep(0.0025, 0.006, abs(r - 0.5596));
            rings += 1.0 - smoothstep(0.0025, 0.006, abs(r - 0.8590));
            col += vec3(0.10, 0.16, 0.20) * rings * 0.5;
            // rail path: traveled bright cyan, remaining dim
            float dTrav = 1e9; float dRest = 1e9;
            for (int i = 0; i < ${WAYPOINTS.size - 1}; i++) {
                float t;
                float d = distSeg(vP, uPath[i], uPath[i + 1], t);
                float segT = mix(uPathT[i], uPathT[i + 1], t);
                if (segT <= uProg) dTrav = min(dTrav, d); else dRest = min(dRest, d);
            }
            col += vec3(0.15, 0.55, 0.75) * (1.0 - smoothstep(0.004, 0.014, dTrav));
            col += vec3(0.10, 0.16, 0.22) * (1.0 - smoothstep(0.003, 0.010, dRest));
            // Sol marker (panel center)
            col += vec3(1.0, 0.85, 0.45) * (1.0 - smoothstep(0.008, 0.022, r));
            // ship blip: pulsing
            float ds = length(vP - uShip);
            float pulse = 0.72 + 0.28 * sin(uTime * 5.0);
            col += vec3(0.55, 1.0, 0.85) * (1.0 - smoothstep(0.012, 0.030, ds)) * pulse;
            col += vec3(0.55, 1.0, 0.85) * (1.0 - smoothstep(0.030, 0.075, ds)) * 0.25 * pulse;
            gl_FragColor = vec4(col, uAlpha * edge * 0.92);
        }
    """

    private var prog = 0
    private var aPos = 0
    private var uCenter = 0; private var uScale = 0; private var uShip = 0
    private var uProg = 0; private var uTime = 0; private var uAlpha = 0
    private var uPath = 0; private var uPathT = 0
    private val quad = GlAssets.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val pathFlat = FloatArray(WAYPOINTS.size * 2)
    private val pathT = FloatArray(WAYPOINTS.size)
    private val readout = HudText(texW = 512, texH = 72, textSizePx = 35f)

    // runtime state (set from logic thread; consumed on GL thread)
    @Volatile private var shipX = 0f
    @Volatile private var shipY = 0f
    @Volatile private var progFrac = 0f
    @Volatile private var readoutText = ""

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uCenter = GLES20.glGetUniformLocation(prog, "uCenter")
        uScale = GLES20.glGetUniformLocation(prog, "uScale")
        uShip = GLES20.glGetUniformLocation(prog, "uShip")
        uProg = GLES20.glGetUniformLocation(prog, "uProg")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uPath = GLES20.glGetUniformLocation(prog, "uPath")
        uPathT = GLES20.glGetUniformLocation(prog, "uPathT")
        val maxU = WAYPOINTS.last().u
        WAYPOINTS.forEachIndexed { i, w ->
            val xy = toXY(w)
            pathFlat[i * 2] = xy[0]; pathFlat[i * 2 + 1] = xy[1]
            pathT[i] = w.u / maxU
        }
        readout.init()
    }

    /** Called ~10 Hz from the Director tick. */
    fun update(progressUnits: Float, maxUnits: Float) {
        val (xy, dAu, name) = shipAt(progressUnits)
        shipX = xy[0]; shipY = xy[1]
        progFrac = (progressUnits / maxUnits).coerceIn(0f, 1f)
        readoutText = "$name  ·  ${formatDist(dAu)}"
    }

    /** Draw for one eye. [xShift] gives slight per-eye parallax so the HUD sits at depth. */
    fun draw(eyeAspect: Float, xShift: Float, timeSec: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val halfH = 0.30f
        val cx = 0.60f + xShift
        val cy = -0.42f
        GLES20.glUseProgram(prog)
        GLES20.glUniform2f(uCenter, cx, cy)
        GLES20.glUniform2f(uScale, halfH / eyeAspect, halfH)
        GLES20.glUniform2f(uShip, shipX, shipY)
        GLES20.glUniform1f(uProg, progFrac)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform2fv(uPath, WAYPOINTS.size, pathFlat, 0)
        GLES20.glUniform1fv(uPathT, WAYPOINTS.size, pathT, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
        // one-line readout under the panel
        readout.setText(readoutText, backingBar = false)
        readout.draw(cx, cy - halfH - 0.07f, 0.035f, eyeAspect, alpha)
    }
}
