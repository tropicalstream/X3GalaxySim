package com.paleblue.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.paleblue.GlAssets
import kotlin.math.sqrt

/**
 * The world: rail spline (Catmull-Rom through 25 itinerary nodes, u = 0..24),
 * every celestial body with texture-or-procedural fallback, Saturn's rings,
 * and the Sgr A* world position for the lensing pass.
 * World scale: Earth→Sun distance = 320 units = 1 AU.
 */
class SolarSystem(private val ctx: Context) {

    companion object {
        const val MAX_U = 24f
        const val AU_WORLD = 320f
        val SUN_POS = floatArrayOf(0f, 0f, -320f)
        val BLACK_HOLE_POS = floatArrayOf(0f, 0f, -2120f)
    }

    /** Rail nodes u = 0..24 (see MiniMap.WAYPOINTS for the map-space mirror). */
    val nodes = arrayOf(
        floatArrayOf(0f, 0f, 0f),        // 0  Earth LEO
        floatArrayOf(0f, 1f, -60f),      // 1  departure
        floatArrayOf(8f, 0f, -120f),     // 2  Luna
        floatArrayOf(0f, -2f, -190f),    // 3  Venus
        floatArrayOf(-45f, 0f, -300f),   // 4  Sun perihelion (skim)
        floatArrayOf(-20f, 4f, -380f),   // 5  Mars
        floatArrayOf(0f, 0f, -450f),     // 6  asteroid belt
        floatArrayOf(10f, 0f, -530f),    // 7  Jupiter
        floatArrayOf(18f, 2f, -600f),    // 8  Jovian moons
        floatArrayOf(0f, 0f, -680f),     // 9  Saturn rings
        floatArrayOf(10f, -2f, -740f),   // 10 Titan
        floatArrayOf(-10f, 0f, -820f),   // 11 Uranus
        floatArrayOf(0f, 4f, -900f),     // 12 Neptune
        floatArrayOf(-6f, 0f, -980f),    // 13 Pluto
        floatArrayOf(0f, 0f, -1050f),    // 14 Kuiper belt
        floatArrayOf(0f, 0f, -1120f),    // 15 heliopause
        floatArrayOf(0f, 0f, -1250f),    // 16 interstellar (warp)
        floatArrayOf(0f, 0f, -1400f),    // 17 Orion approach
        floatArrayOf(10f, 0f, -1520f),   // 18 inside the nursery
        floatArrayOf(0f, 0f, -1650f),    // 19 TRAPPIST-1
        floatArrayOf(0f, 0f, -1780f),    // 20 HD 189733 b
        floatArrayOf(0f, 0f, -1900f),    // 21 core transit
        floatArrayOf(0f, 0f, -2000f),    // 22 Sgr A* approach
        floatArrayOf(-20f, 0f, -2090f),  // 23 periapsis (lensing peak)
        floatArrayOf(30f, 5f, -2160f)    // 24 the look back
    )

    class Body(
        val name: String, val texAsset: String?, val radius: Float, val pos: FloatArray,
        val baseColor: FloatArray, val atmoColor: FloatArray, val atmoStrength: Float,
        val emissive: Float = 0f, val scrollSpeed: Float = 0f,
        val hasRings: Boolean = false, val lightPos: FloatArray? = null
    ) { var texture = 0 }

    val bodies = listOf(
        Body("Sun", "textures/sun.jpg", 30f, SUN_POS,
            floatArrayOf(1.00f, 0.72f, 0.28f), floatArrayOf(1f, 0.6f, 0.2f), 0.8f, emissive = 1f, scrollSpeed = 0.004f),
        Body("Earth", "textures/earth.jpg", 5f, floatArrayOf(6f, -1f, -6f),
            floatArrayOf(0.16f, 0.32f, 0.55f), floatArrayOf(0.35f, 0.60f, 1.0f), 1.0f),
        Body("Luna", "textures/moon.jpg", 1.3f, floatArrayOf(14f, 2f, -128f),
            floatArrayOf(0.42f, 0.41f, 0.39f), floatArrayOf(0.1f, 0.1f, 0.12f), 0.1f),
        Body("Venus", "textures/venus.jpg", 3.6f, floatArrayOf(-9f, 0f, -200f),
            floatArrayOf(0.78f, 0.65f, 0.42f), floatArrayOf(0.9f, 0.75f, 0.4f), 0.7f, scrollSpeed = 0.010f),
        Body("Mars", "textures/mars.jpg", 2.2f, floatArrayOf(-28f, 2f, -390f),
            floatArrayOf(0.62f, 0.32f, 0.18f), floatArrayOf(0.7f, 0.4f, 0.25f), 0.25f),
        Body("Phobos", null, 0.35f, floatArrayOf(-25.2f, 2.6f, -388.2f),
            floatArrayOf(0.38f, 0.35f, 0.32f), floatArrayOf(0.2f, 0.2f, 0.2f), 0.05f),
        Body("Deimos", null, 0.25f, floatArrayOf(-31.4f, 3.2f, -393.5f),
            floatArrayOf(0.42f, 0.40f, 0.37f), floatArrayOf(0.2f, 0.2f, 0.2f), 0.05f),
        Body("Jupiter", "textures/jupiter.jpg", 22f, floatArrayOf(34f, 0f, -560f),
            floatArrayOf(0.66f, 0.54f, 0.42f), floatArrayOf(0.75f, 0.62f, 0.45f), 0.45f, scrollSpeed = 0.016f),
        Body("Io", null, 1.0f, floatArrayOf(26f, 4f, -596f),
            floatArrayOf(0.75f, 0.68f, 0.32f), floatArrayOf(0.6f, 0.55f, 0.2f), 0.15f),
        Body("Europa", null, 0.9f, floatArrayOf(12f, 0f, -612f),
            floatArrayOf(0.72f, 0.70f, 0.66f), floatArrayOf(0.5f, 0.55f, 0.6f), 0.15f),
        Body("Saturn", "textures/saturn.jpg", 18f, floatArrayOf(20f, -2f, -722f),
            floatArrayOf(0.72f, 0.63f, 0.45f), floatArrayOf(0.8f, 0.7f, 0.5f), 0.35f, scrollSpeed = 0.012f, hasRings = true),
        Body("Titan", null, 1.5f, floatArrayOf(2f, 0f, -752f),
            floatArrayOf(0.72f, 0.55f, 0.28f), floatArrayOf(0.85f, 0.6f, 0.3f), 0.6f),
        Body("Uranus", "textures/uranus.jpg", 9f, floatArrayOf(-28f, 4f, -840f),
            floatArrayOf(0.48f, 0.72f, 0.75f), floatArrayOf(0.5f, 0.85f, 0.9f), 0.5f, scrollSpeed = 0.006f),
        Body("Neptune", "textures/neptune.jpg", 8.5f, floatArrayOf(16f, 6f, -930f),
            floatArrayOf(0.22f, 0.36f, 0.75f), floatArrayOf(0.3f, 0.5f, 1.0f), 0.55f, scrollSpeed = 0.010f),
        Body("Triton", null, 0.9f, floatArrayOf(12f, 8.5f, -924f),
            floatArrayOf(0.62f, 0.66f, 0.68f), floatArrayOf(0.5f, 0.6f, 0.7f), 0.2f),
        Body("Pluto", null, 1.1f, floatArrayOf(-13f, 2f, -990f),
            floatArrayOf(0.58f, 0.50f, 0.44f), floatArrayOf(0.4f, 0.4f, 0.45f), 0.12f),
        Body("Charon", null, 0.55f, floatArrayOf(-10.4f, 2.8f, -987.6f),
            floatArrayOf(0.48f, 0.44f, 0.42f), floatArrayOf(0.3f, 0.3f, 0.35f), 0.08f),
        // ---- beyond the heliopause: bodies lit by their own stars ----
        Body("Protostar", null, 3f, floatArrayOf(40f, 12f, -1560f),
            floatArrayOf(1.0f, 0.55f, 0.30f), floatArrayOf(1f, 0.5f, 0.3f), 0.9f, emissive = 1f),
        Body("TRAPPIST-1", null, 6f, floatArrayOf(0f, 0f, -1706f),
            floatArrayOf(0.95f, 0.35f, 0.22f), floatArrayOf(1f, 0.4f, 0.2f), 0.8f, emissive = 1f, scrollSpeed = 0.006f),
        Body("TRAPPIST-1e", null, 1.0f, floatArrayOf(-11f, 2f, -1688f),
            floatArrayOf(0.35f, 0.45f, 0.50f), floatArrayOf(0.5f, 0.6f, 0.7f), 0.5f,
            lightPos = floatArrayOf(0f, 0f, -1706f)),
        Body("TRAPPIST-1f", null, 1.1f, floatArrayOf(-16f, -1f, -1697f),
            floatArrayOf(0.40f, 0.42f, 0.38f), floatArrayOf(0.5f, 0.55f, 0.5f), 0.4f,
            lightPos = floatArrayOf(0f, 0f, -1706f)),
        Body("TRAPPIST-1g", null, 1.2f, floatArrayOf(-8f, 4f, -1716f),
            floatArrayOf(0.55f, 0.58f, 0.62f), floatArrayOf(0.55f, 0.65f, 0.8f), 0.5f,
            lightPos = floatArrayOf(0f, 0f, -1706f)),
        Body("HD 189733 b", null, 7f, floatArrayOf(16f, 0f, -1812f),
            floatArrayOf(0.10f, 0.20f, 0.55f), floatArrayOf(0.25f, 0.45f, 1.0f), 1.0f, scrollSpeed = 0.045f,
            lightPos = floatArrayOf(120f, 30f, -1780f))
    )

    val nebulaCenter = floatArrayOf(0f, 12f, -1540f)
    val nebulaSize = 130f

    private val rings = Rings()

    fun initGl() {
        bodies.forEach { it.texture = GlAssets.loadTexture(ctx, it.texAsset ?: "") }
        rings.init()
    }

    // ---------- rail math ----------
    private fun node(i: Int) = nodes[i.coerceIn(0, nodes.size - 1)]

    /** Catmull-Rom position on the rail, u in [0, 24]. */
    fun camPosAt(u: Float): FloatArray {
        val uu = u.coerceIn(0f, MAX_U)
        val i = uu.toInt().coerceAtMost(nodes.size - 2)
        val t = uu - i
        val p0 = node(i - 1); val p1 = node(i); val p2 = node(i + 1); val p3 = node(i + 2)
        val out = FloatArray(3)
        for (k in 0..2) {
            val t2 = t * t; val t3 = t2 * t
            out[k] = 0.5f * ((2f * p1[k]) + (-p0[k] + p2[k]) * t +
                    (2f * p0[k] - 5f * p1[k] + 4f * p2[k] - p3[k]) * t2 +
                    (-p0[k] + 3f * p1[k] - 3f * p2[k] + p3[k]) * t3)
        }
        return out
    }

    /** Forward direction of travel (normalized spline tangent). */
    fun tangentAt(u: Float): FloatArray {
        val a = camPosAt((u - 0.02f).coerceAtLeast(0f))
        val b = camPosAt((u + 0.02f).coerceAtMost(MAX_U))
        val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-5f)
        return floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }

    fun pointOfInterestAt(u: Float): FloatArray? {
        val targets = listOf(
            0.0f to "Earth", 2.0f to "Luna", 3.0f to "Venus", 4.0f to "Sun",
            5.0f to "Mars", 7.0f to "Jupiter", 8.0f to "Europa", 9.0f to "Saturn",
            10.0f to "Titan", 11.0f to "Uranus", 12.0f to "Neptune", 13.0f to "Pluto",
            18.0f to "Protostar", 19.0f to "TRAPPIST-1", 20.0f to "HD 189733 b"
        )
        val name = targets.firstOrNull { it.first >= u - 0.12f }?.second ?: return null
        return bodies.firstOrNull { it.name == name }?.pos
    }

    // ---------- drawing ----------
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun drawBodies(planetShader: PlanetShader, sphere: Sphere, vp: FloatArray,
                   camPos: FloatArray, sunlight: Float, timeSec: Float) {
        for (b in bodies) {
            val dx = b.pos[0] - camPos[0]; val dy = b.pos[1] - camPos[1]; val dz = b.pos[2] - camPos[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > 600f + b.radius) continue          // cull far legs of the tour
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, b.pos[0], b.pos[1], b.pos[2])
            Matrix.rotateM(model, 0, timeSec * 1.2f, 0f, 1f, 0f)   // slow spin
            Matrix.scaleM(model, 0, b.radius, b.radius, b.radius)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            val light = b.lightPos ?: SUN_POS
            val localSunlight = if (b.lightPos != null) 1.1f else sunlight
            planetShader.draw(sphere, mvp, model, b.texture, b.baseColor,
                light, camPos, localSunlight, b.atmoColor, b.atmoStrength,
                b.emissive, timeSec * b.scrollSpeed)
            if (b.hasRings) rings.draw(vp, b.pos, b.radius, camPos, light, localSunlight)
        }
    }
}

/** Saturn's ice rings: translucent annulus with radial bands (threaded at u≈9). */
class Rings {
    private val vsrc = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute float aR;            // 0 inner .. 1 outer
        varying float vR;
        varying vec3 vWorld;
        uniform mat4 uModel;
        void main() {
            vR = aR;
            vWorld = (uModel * vec4(aPos, 1.0)).xyz;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying float vR;
        varying vec3 vWorld;
        uniform vec3 uSunPos;
        uniform float uSunlight;
        float hash(float x) { return fract(sin(x * 91.17) * 43758.5453); }
        void main() {
            float bands = 0.55 + 0.45 * sin(vR * 90.0) * (0.6 + 0.4 * hash(floor(vR * 46.0)));
            float gap = smoothstep(0.62, 0.60, vR) + smoothstep(0.66, 0.68, vR);  // Cassini division
            float a = bands * min(gap, 1.0) * smoothstep(0.0, 0.06, vR) * smoothstep(1.0, 0.94, vR);
            vec3 col = vec3(0.75, 0.70, 0.58) * (0.25 + 0.75 * uSunlight);
            gl_FragColor = vec4(col, a * 0.55);
        }
    """

    private var prog = 0
    private var aPos = 0; private var aR = 0
    private var uMVP = 0; private var uModel = 0; private var uSunPos = 0; private var uSunlight = 0
    private lateinit var verts: java.nio.FloatBuffer
    private var vertCount = 0
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aR = GLES20.glGetAttribLocation(prog, "aR")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uSunPos = GLES20.glGetUniformLocation(prog, "uSunPos")
        uSunlight = GLES20.glGetUniformLocation(prog, "uSunlight")
        // triangle strip annulus, unit radii 1.35 -> 2.35, XZ plane, [x,y,z,r] interleaved
        val seg = 96
        val data = FloatArray((seg + 1) * 2 * 4)
        var vi = 0
        for (j in 0..seg) {
            val a = 2.0 * Math.PI * j / seg
            val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
            data[vi++] = c * 1.35f; data[vi++] = 0f; data[vi++] = s * 1.35f; data[vi++] = 0f
            data[vi++] = c * 2.35f; data[vi++] = 0f; data[vi++] = s * 2.35f; data[vi++] = 1f
        }
        verts = GlAssets.floatBuffer(data)
        vertCount = (seg + 1) * 2
    }

    fun draw(vp: FloatArray, center: FloatArray, planetRadius: Float,
             camPos: FloatArray, sunPos: FloatArray, sunlight: Float) {
        GLES20.glUseProgram(prog)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, center[0], center[1], center[2])
        Matrix.rotateM(model, 0, 12f, 1f, 0f, 0.3f)      // ring plane tilt
        Matrix.scaleM(model, 0, planetRadius, planetRadius, planetRadius)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
        GLES20.glUniform1f(uSunlight, sunlight.coerceIn(0f, 1.4f))
        verts.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aPos)
        verts.position(3)
        GLES20.glVertexAttribPointer(aR, 1, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aR)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertCount)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aR)
    }
}
