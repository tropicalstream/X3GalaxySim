package com.rayneo.x3constellation

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class StereoConstellationRenderer(private val audioEngine: SpaceAudioEngine) : GLSurfaceView.Renderer {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val normal = FloatArray(16)

    private lateinit var sphere: SphereMesh
    private lateinit var moon: SphereMesh
    private lateinit var starMesh: PointMesh
    private lateinit var orionMesh: LineMesh
    private lateinit var asteroidMesh: PointMesh
    private val meteors = MeteorField(48)
    private lateinit var enterpriseMesh: TriMesh
    private lateinit var cockpitMesh: LineMesh
    private lateinit var routeMesh: LineMesh
    private lateinit var shader3d: LitShader
    private lateinit var colorShader: ColorShader

    private var width = 1
    private var height = 1
    private val startNanos = System.nanoTime()
    private var lastFrameNanos = startNanos
    private var routeProgress = 0f
    private var warpLevel = 1
    private var viewMode = 0
    private var craftYaw = 0f
    private var announcedApproach = -1
    private var approachListener: ((String) -> Unit)? = null
    private var viewListener: ((Int) -> Unit)? = null
    private var speedListener: ((Int) -> Unit)? = null

    // Curved flight: real gravity deflection + hard planet-avoidance clearance.
    private var shipX = 0f; private var shipY = 0f; private var shipZ = 0f
    private var velX = 0f; private var velY = 0f; private var velZ = -1f
    private var latX = 0f; private var latY = 0f; private var latZ = 0f      // offset from the rail
    private var latVX = 0f; private var latVY = 0f; private var latVZ = 0f
    private var craftPitch = 0f
    private var flightInit = false

    fun setApproachListener(listener: (String) -> Unit) { approachListener = listener }
    fun setViewListener(listener: (Int) -> Unit) { viewListener = listener }
    fun setSpeedListener(listener: (Int) -> Unit) { speedListener = listener }

    /** Nearest body to the ship right now (for telemetry-driven science reports). */
    fun currentBodyName(): String {
        val p = flightPosition()
        var best = 0; var bestD = Float.MAX_VALUE
        bodies.forEachIndexed { i, b ->
            val dx = b.x - p.x; val dy = b.y - p.y; val dz = b.z - p.z
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) { bestD = d; best = i }
        }
        return bodies[best].name
    }

    // legAu = real heliocentric leg distance (AU) from this body to the NEXT on the tour.
    // Earth->Moon uses the real Earth-Moon distance; the rest use |a_sun - b_sun|.
    private val bodies = listOf(
        Body("Earth", 0f, 0f, -1.25f, 1.45f, floatArrayOf(0.06f, 0.27f, 0.78f, 1f), floatArrayOf(0.25f, 0.95f, 0.62f, 1f), true, 0.00257f),
        Body("Moon", 1.35f, 0.38f, -1.6f, 0.46f, floatArrayOf(0.72f, 0.75f, 0.78f, 1f), floatArrayOf(0.28f, 0.28f, 0.3f, 1f), false, 0.61f),
        Body("Mercury", -2.3f, -0.12f, -3.70f, 0.34f, floatArrayOf(0.54f, 0.49f, 0.42f, 1f), floatArrayOf(0.22f, 0.2f, 0.18f, 1f), false, 0.33f),
        Body("Venus", 2.4f, 0.2f, -5.02f, 0.82f, floatArrayOf(0.87f, 0.67f, 0.35f, 1f), floatArrayOf(0.65f, 0.5f, 0.28f, 1f), false, 0.80f),
        Body("Mars", -2.2f, -0.3f, -8.22f, 0.62f, floatArrayOf(0.78f, 0.24f, 0.13f, 1f), floatArrayOf(0.32f, 0.12f, 0.08f, 1f), false, 3.68f),
        Body("Jupiter", 3.1f, 0.45f, -22.94f, 1.75f, floatArrayOf(0.82f, 0.66f, 0.48f, 1f), floatArrayOf(0.52f, 0.35f, 0.22f, 1f), false, 4.38f),
        Body("Saturn", -3.0f, -0.15f, -40.46f, 1.45f, floatArrayOf(0.78f, 0.68f, 0.45f, 1f), floatArrayOf(0.45f, 0.36f, 0.22f, 1f), false, 9.62f),
        Body("Uranus", 2.6f, 0.3f, -78.94f, 1.08f, floatArrayOf(0.42f, 0.86f, 0.92f, 1f), floatArrayOf(0.22f, 0.48f, 0.56f, 1f), false, 10.85f),
        Body("Neptune", -2.4f, -0.18f, -122.34f, 1.05f, floatArrayOf(0.18f, 0.32f, 0.92f, 1f), floatArrayOf(0.12f, 0.16f, 0.52f, 1f), false, 9.45f),
        Body("Pluto", 1.4f, 0.1f, -160.14f, 0.25f, floatArrayOf(0.62f, 0.54f, 0.48f, 1f), floatArrayOf(0.35f, 0.27f, 0.22f, 1f), false, 0f)
    )

    // Real-distance travel timing. Base seconds-per-AU matched to the old cruise feel,
    // then multiplied by TRAVEL_TIME_SCALE (=10) per the "10x longer" requirement.
    private fun legSeconds(leg: Int): Float {
        val au = bodies[leg.coerceIn(0, bodies.lastIndex)].legAu
        return (au * BASE_SECONDS_PER_AU * TRAVEL_TIME_SCALE).coerceAtLeast(MIN_LEG_SECONDS)
    }

    private fun boostFactor(): Float = warpLevel.toFloat()

    fun accelerateForward() {
        warpLevel = min(warpLevel + 1, 9)
        speedListener?.invoke(warpLevel)
    }

    fun accelerateBack() {
        warpLevel = max(warpLevel - 1, 1)
        speedListener?.invoke(warpLevel)
    }

    fun switchView() {
        viewMode = (viewMode + 1) % VIEW_COUNT
        viewListener?.invoke(viewMode)
    }

    fun telemetry(): String {
        val floor = routeProgress.toInt().coerceIn(0, bodies.lastIndex)
        val nextIdx = (floor + 1).coerceAtMost(bodies.lastIndex)
        val frac = (routeProgress - floor).coerceIn(0f, 1f)
        val legSec = legSeconds(floor)
        val etaSec = (legSec * (1f - frac) / boostFactor()).toInt().coerceAtLeast(0)
        val simSpeed = boostFactor()
        val mode = VIEW_NAMES.getOrElse(viewMode) { "BRIDGE" }
        return "U.S.S. ENTERPRISE   NCC-1701\n" +
            "VIEW $mode   STEREO ACTIVE\n" +
            "DEPARTED ${bodies[floor].name.uppercase()}   APPROACHING ${bodies[nextIdx].name.uppercase()}\n" +
            "LEG ${(frac * 100f).toInt()}%   ETA ${"%02d:%02d".format(etaSec / 60, etaSec % 60)}   ${"%.2f".format(bodies[floor].legAu)} AU\n" +
            "WARP FACTOR ${"%.1f".format(simSpeed)}   HEADING ${craftYaw.toInt()} MARK\n" +
            "TELEMETRY REFRESH 10s   STARFIELD LOCKED"
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0.015f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        shader3d = LitShader()
        colorShader = ColorShader()
        sphere = SphereMesh(48, 32)
        moon = SphereMesh(32, 18)
        starMesh = PointMesh(buildStars())
        asteroidMesh = PointMesh(buildAsteroidBelt())
        orionMesh = LineMesh(buildOrionLines(), lineVertexCount = 16, pointVertexCount = 8)
        enterpriseMesh = TriMesh(buildEnterpriseSolid())
        cockpitMesh = LineMesh(buildCockpitLines(), lineVertexCount = 12, pointVertexCount = 0)
        routeMesh = LineMesh(buildRouteLines(), lineVertexCount = (bodies.size - 1) * 2, pointVertexCount = 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        updateFlight(dt)
        meteors.update(cameraX(), cameraY(), cameraZ(), dt)

        val halfWidth = width / 2
        val eyeAspect = halfWidth.toFloat() / height.toFloat()
        Matrix.perspectiveM(projection, 0, 58f, eyeAspect, 0.2f, 260f)

        drawEye(0, 0f, halfWidth, -0.035f)
        drawEye(halfWidth, 0f, width - halfWidth, 0.035f)
    }

    private fun drawEye(x: Int, y: Float, viewportWidth: Int, eyeOffset: Float) {
        GLES20.glViewport(x, y.toInt(), viewportWidth, height)
        Matrix.setLookAtM(
            view,
            0,
            eyeOffset + cameraX(),
            cameraY(),
            cameraZ(),
            eyeOffset * 0.35f + lookX(),
            lookY(),
            lookZ(),
            0f,
            1f,
            0f
        )

        val seconds = (System.nanoTime() - startNanos) / 1_000_000_000f
        drawStars()
        drawRoute()
        drawSun()
        bodies.forEachIndexed { index, body -> drawBody(body, seconds + index * 7f) }
        drawAsteroids()
        drawOrion()
        when (viewMode) {
            VIEW_BRIDGE -> drawCockpit()
            VIEW_CHASE -> drawEnterprise()
            VIEW_ENGINEERING -> drawWarpCore(seconds)
            VIEW_MEDITATION -> drawEnterprise()
        }
        drawMeteors()
    }

    private fun updateFlight(dt: Float) {
        // Continuous cruise whose pace is set by the real distance of the current leg,
        // scaled 10x. Forward swipe = warp boost, back swipe = brief reverse.
        val floor = routeProgress.toInt().coerceIn(0, bodies.lastIndex)
        val forwardRate = (1f / legSeconds(floor)) * warpLevel
        routeProgress = (routeProgress + forwardRate * dt)
            .coerceIn(0f, bodies.lastIndex.toFloat())

        // Enterprise-computer approach announcement: fire once per leg near arrival.
        val curFloor = routeProgress.toInt().coerceIn(0, bodies.lastIndex)
        val frac = routeProgress - curFloor
        val nextIdx = (curFloor + 1).coerceAtMost(bodies.lastIndex)
        if (nextIdx != curFloor && frac >= APPROACH_FRAC && announcedApproach != nextIdx) {
            announcedApproach = nextIdx
            approachListener?.invoke(bodies[nextIdx].name)
        }

        integrateGravity(dt)
        updateHeading(dt)
    }

    // On-rails pacing position (straight lerp between waypoints, before gravity).
    private fun railPosition(): Vec3 {
        val floor = routeProgress.toInt().coerceIn(0, bodies.lastIndex - 1)
        val t = routeProgress - floor
        val a = bodies[floor]
        val b = bodies[floor + 1]
        return Vec3(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))
    }

    private fun routeDirRaw(): Vec3 {
        val floor = routeProgress.toInt().coerceIn(0, bodies.lastIndex - 1)
        val a = bodies[floor]
        val b = bodies[floor + 1]
        return Vec3(b.x - a.x, b.y - a.y, b.z - a.z).normalized()
    }

    // Mass proportional to volume of the rendered body (realistic relative gravity).
    private fun bodyMass(b: Body): Float = b.radius * b.radius * b.radius

    /**
     * Newtonian inverse-square gravity from every body, applied perpendicular to the
     * travel direction (so pacing stays on the rail) plus a hard clearance clamp so
     * the ship arcs AROUND each planet rather than through it.
     */
    private fun integrateGravity(dt: Float) {
        val rail = railPosition()
        val d = routeDirRaw()
        var sx = rail.x + latX; var sy = rail.y + latY; var sz = rail.z + latZ

        var ax = 0f; var ay = 0f; var az = 0f
        for (b in bodies) {
            val rx = b.x - sx; val ry = b.y - sy; val rz = b.z - sz
            val r2 = rx * rx + ry * ry + rz * rz + 0.25f
            val r = sqrt(r2)
            val f = G * bodyMass(b) / (r2 * r)   // (GM / r^2) * unit vector
            ax += rx * f; ay += ry * f; az += rz * f
        }
        // Remove along-track component so gravity curves the path, not the schedule.
        val along = ax * d.x + ay * d.y + az * d.z
        ax -= along * d.x; ay -= along * d.y; az -= along * d.z
        // Gentle spring back toward the rail between planets.
        ax -= latX * 0.6f; ay -= latY * 0.6f; az -= latZ * 0.6f

        latVX = (latVX + ax * dt) * 0.985f
        latVY = (latVY + ay * dt) * 0.985f
        latVZ = (latVZ + az * dt) * 0.985f
        latX += latVX * dt; latY += latVY * dt; latZ += latVZ * dt

        sx = rail.x + latX; sy = rail.y + latY; sz = rail.z + latZ

        // Hard clearance: never enter a planet's sphere.
        for (b in bodies) {
            val clr = b.radius + 0.55f
            var rx = sx - b.x; var ry = sy - b.y; var rz = sz - b.z
            var dist = sqrt(rx * rx + ry * ry + rz * rz)
            if (dist < clr) {
                if (dist < 1e-3f) {
                    // Degenerate: push perpendicular to travel (cross(d, up)).
                    rx = d.y * 0f - d.z * 1f; ry = d.z * 0f - d.x * 0f; rz = d.x * 1f - d.y * 0f
                    dist = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-3f)
                }
                val inv = clr / dist
                sx = b.x + rx * inv; sy = b.y + ry * inv; sz = b.z + rz * inv
            }
        }
        latX = sx - rail.x; latY = sy - rail.y; latZ = sz - rail.z

        if (flightInit) {
            // Smoothed velocity -> stable heading that still turns through the curve.
            velX += ((sx - shipX) - velX) * 0.25f
            velY += ((sy - shipY) - velY) * 0.25f
            velZ += ((sz - shipZ) - velZ) * 0.25f
        }
        shipX = sx; shipY = sy; shipZ = sz; flightInit = true
    }

    private fun updateHeading(dt: Float) {
        val horiz = sqrt(velX * velX + velZ * velZ)
        if (horiz > 1e-4f) {
            val desiredYaw = atan2(velX, -velZ) * 180f / PI.toFloat()
            var delta = desiredYaw - craftYaw
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            craftYaw += delta * (1f - kotlin.math.exp(-dt * 3f))
        }
        val speed = sqrt(velX * velX + velY * velY + velZ * velZ)
        if (speed > 1e-4f) {
            val desiredPitch = atan2(velY, horiz.coerceAtLeast(1e-4f)) * 180f / PI.toFloat()
            craftPitch += (desiredPitch - craftPitch) * (1f - kotlin.math.exp(-dt * 3f))
        }
    }

    private fun flightPosition(): Vec3 =
        if (flightInit) Vec3(shipX, shipY, shipZ) else railPosition()

    private fun routeDirection(): Vec3 {
        val m = sqrt(velX * velX + velY * velY + velZ * velZ)
        return if (m > 1e-4f) Vec3(velX / m, velY / m, velZ / m) else routeDirRaw()
    }

    private fun cameraX(): Float {
        val p = flightPosition(); val d = routeDirection()
        return when (viewMode) {
            VIEW_CHASE -> p.x - d.x * 2.6f                 // directly behind, tracks the curve
            VIEW_ENGINEERING -> p.x
            VIEW_MEDITATION -> p.x - d.x * 1.3f + 1.4f     // lounge: behind + to the side
            else -> p.x                                    // bridge: at the helm
        }
    }

    private fun cameraY(): Float {
        val p = flightPosition(); val d = routeDirection()
        return when (viewMode) {
            VIEW_CHASE -> p.y - d.y * 2.6f + 0.72f
            VIEW_ENGINEERING -> p.y + 0.12f
            VIEW_MEDITATION -> p.y + 0.66f
            else -> p.y + 0.16f
        }
    }

    private fun cameraZ(): Float {
        val p = flightPosition(); val d = routeDirection()
        return when (viewMode) {
            VIEW_CHASE -> p.z - d.z * 2.6f
            VIEW_ENGINEERING -> p.z + 0.95f
            VIEW_MEDITATION -> p.z - d.z * 1.3f + 2.0f
            else -> p.z + 0.6f
        }
    }

    private fun lookX(): Float {
        val p = flightPosition()
        val d = routeDirection()
        return p.x + d.x * 4f
    }

    private fun lookY(): Float {
        val p = flightPosition()
        val d = routeDirection()
        return p.y + d.y * 3.2f
    }

    private fun lookZ(): Float {
        val p = flightPosition()
        val d = routeDirection()
        return p.z + d.z * 4f
    }

    private fun drawBody(body: Body, seconds: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, body.x, body.y, body.z)
        Matrix.rotateM(model, 0, -18f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, seconds * 3.6f, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, body.radius, body.radius, body.radius)
        drawSphere(if (body.radius < 0.5f) moon else sphere, body.base, body.accent, body.earthBands)
        if (body.name == "Saturn") drawSaturnRing()
    }

    private fun drawSun() {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, -4.2f, 1.4f, 1.8f)
        Matrix.scaleM(model, 0, 2.4f, 2.4f, 2.4f)
        drawSphere(sphere, floatArrayOf(1f, 0.78f, 0.2f, 1f), floatArrayOf(1f, 0.38f, 0.06f, 1f), false)
    }

    private fun drawSphere(mesh: SphereMesh, base: FloatArray, accent: FloatArray, earthBands: Boolean) {
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        Matrix.invertM(normal, 0, model, 0)
        Matrix.transposeM(normal, 0, normal, 0)
        shader3d.use(mvp, model, normal, base, accent, if (earthBands) 1f else 0f)
        mesh.draw(shader3d.positionHandle, shader3d.normalHandle)
    }

    private fun drawStars() {
        // Skybox-style: lock the starfield to the camera and draw depth-free so the
        // stars are ALWAYS visible behind every body and in every view.
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, cameraX(), cameraY(), cameraZ())
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.6f)
        starMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun drawAsteroids() {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 2.4f)
        asteroidMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    private fun drawMeteors() {
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 2.0f)
        meteors.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glDepthMask(true)
    }

    private fun drawOrion() {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.1f, -9.5f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 4.5f)
        orionMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    private fun drawRoute() {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 2.5f)
        routeMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    private fun drawEnterprise() {
        val p = flightPosition()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p.x, p.y - 0.12f, p.z + 0.52f)
        Matrix.rotateM(model, 0, craftYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, craftPitch, 1f, 0f, 0f)   // face along travel (pitch)
        Matrix.scaleM(model, 0, 0.52f, 0.52f, 0.52f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 5.5f)
        // Solid hull: disable back-face culling so all facets render regardless of winding.
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        enterpriseMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun drawWarpCore(seconds: Float) {
        val p = flightPosition()
        val pulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(seconds * 3.2f))
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p.x, p.y - 0.05f, p.z - 0.55f)
        Matrix.scaleM(model, 0, 0.13f, 1.15f, 0.13f)
        drawSphere(
            moon,
            floatArrayOf(0.30f * pulse, 0.68f * pulse, 1f, 1f),
            floatArrayOf(0.55f, 0.9f, 1f, 1f),
            false
        )
    }

    private fun drawCockpit() {
        val p = flightPosition()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p.x, p.y - 0.2f, p.z + 0.36f)
        Matrix.rotateM(model, 0, craftYaw, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, 0.9f, 0.9f, 0.9f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.5f)
        cockpitMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    private fun drawSaturnRing() {
        Matrix.scaleM(model, 0, 2.1f, 0.04f, 2.1f)
        drawSphere(moon, floatArrayOf(0.78f, 0.69f, 0.48f, 0.45f), floatArrayOf(0.5f, 0.4f, 0.24f, 0.45f), false)
    }

    private fun buildStars(): FloatArray {
        val stars = mutableListOf<Float>()
        val seeded = listOf(
            Star(-2.8f, 1.8f, -11f, 0.7f, 0.8f, 1f),
            Star(2.7f, 1.35f, -12f, 1f, 0.82f, 0.64f),
            Star(-3.4f, -0.7f, -13f, 0.72f, 0.86f, 1f),
            Star(3.1f, -1.1f, -14f, 1f, 1f, 0.82f),
            Star(-1.1f, 2.6f, -16f, 1f, 1f, 1f),
            Star(1.6f, 2.4f, -15f, 0.9f, 0.96f, 1f)
        )
        seeded.forEach { stars.addPoint(it.x, it.y, it.z, it.r, it.g, it.b, 0.8f) }
        for (i in 0 until 96) {
            val a = i * 2.39996f
            val radius = 2.4f + (i % 13) * 0.23f
            val x = cos(a) * radius
            val yy = sin(a * 1.31f) * 2.2f
            val z = -10f - (i % 17) * 0.42f
            val tint = 0.65f + (i % 5) * 0.07f
            stars.addPoint(x, yy, z, tint, tint + 0.05f, 1f, 0.42f)
        }
        return stars.toFloatArray()
    }

    // Asteroid belt: scattered rocks between Mars (z ~ -8) and Jupiter (z ~ -23).
    private fun buildAsteroidBelt(): FloatArray {
        val list = mutableListOf<Float>()
        val rnd = java.util.Random(42)
        val z0 = -10f; val z1 = -20f
        for (i in 0 until 260) {
            val x = (rnd.nextFloat() - 0.5f) * 22f
            val y = (rnd.nextFloat() - 0.5f) * 10f
            val z = z0 + (z1 - z0) * rnd.nextFloat()
            val g = 0.40f + rnd.nextFloat() * 0.25f
            list.addPoint(x, y, z, g, g * 0.92f, g * 0.80f, 0.9f)
        }
        return list.toFloatArray()
    }

    private fun buildOrionLines(): FloatArray {
        val betelgeuse = Vec3(-1.6f, 1.15f, 0f)
        val bellatrix = Vec3(1.1f, 1.05f, 0f)
        val alnitak = Vec3(-0.55f, 0.05f, 0f)
        val alnilam = Vec3(0f, 0f, 0f)
        val mintaka = Vec3(0.6f, 0.08f, 0f)
        val saiph = Vec3(-1.1f, -1.35f, 0f)
        val rigel = Vec3(1.35f, -1.42f, 0f)
        val sword = Vec3(0f, -0.82f, 0f)
        val color = floatArrayOf(0.6f, 0.78f, 1f, 0.86f)
        return buildList {
            addLine(betelgeuse, bellatrix, color)
            addLine(betelgeuse, alnitak, color)
            addLine(bellatrix, mintaka, color)
            addLine(alnitak, alnilam, color)
            addLine(alnilam, mintaka, color)
            addLine(alnitak, saiph, color)
            addLine(mintaka, rigel, color)
            addLine(alnilam, sword, color)
            listOf(betelgeuse, bellatrix, alnitak, alnilam, mintaka, saiph, rigel, sword).forEach {
                addPoint(it.x, it.y, it.z, 1f, 1f, 1f, 1f)
            }
        }.toFloatArray()
    }

    private fun buildRouteLines(): FloatArray = buildList {
        val color = floatArrayOf(0.25f, 0.74f, 0.95f, 0.38f)
        bodies.zipWithNext().forEach { (a, b) ->
            addLine(Vec3(a.x, a.y, a.z), Vec3(b.x, b.y, b.z), color)
        }
    }.toFloatArray()

    // Original stylized starship (saucer + engineering hull + neck + twin nacelles).
    // Faces -Z (nose forward). Drawn as solid GL_TRIANGLES via TriMesh.
    private fun buildEnterpriseSolid(): FloatArray = buildList {
        val hull = floatArrayOf(0.80f, 0.85f, 0.95f, 1f)
        val hullDark = floatArrayOf(0.54f, 0.60f, 0.72f, 1f)
        val nacelleCol = floatArrayOf(0.62f, 0.68f, 0.82f, 1f)
        val glow = floatArrayOf(0.35f, 0.65f, 1f, 1f)
        val deflector = floatArrayOf(1f, 0.76f, 0.36f, 1f)

        fun tri(a: Vec3, b: Vec3, c: Vec3, col: FloatArray) {
            addPoint(a.x, a.y, a.z, col[0], col[1], col[2], col[3])
            addPoint(b.x, b.y, b.z, col[0], col[1], col[2], col[3])
            addPoint(c.x, c.y, c.z, col[0], col[1], col[2], col[3])
        }
        fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, col: FloatArray) { tri(a, b, c, col); tri(a, c, d, col) }
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, col: FloatArray, cap: FloatArray) {
            val x0 = cx - hx; val x1 = cx + hx; val y0 = cy - hy; val y1 = cy + hy; val z0 = cz - hz; val z1 = cz + hz
            quad(Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z0), cap)       // front (-z)
            quad(Vec3(x1, y0, z1), Vec3(x0, y0, z1), Vec3(x0, y1, z1), Vec3(x1, y1, z1), col)       // back
            quad(Vec3(x0, y0, z1), Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x0, y1, z1), col)       // left
            quad(Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z0), col)       // right
            quad(Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x0, y1, z1), col)       // top
            quad(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y0, z0), Vec3(x0, y0, z0), col)       // bottom
        }

        // Saucer section (flat disc), plane in XZ, thin in Y, at the nose.
        val segs = 24
        val scz = -0.34f; val rx = 0.5f; val rz = 0.42f; val topY = 0.06f; val botY = -0.03f
        for (i in 0 until segs) {
            val a0 = 2f * PI.toFloat() * i / segs
            val a1 = 2f * PI.toFloat() * (i + 1) / segs
            val e0 = Vec3(cos(a0) * rx, 0f, scz + sin(a0) * rz)
            val e1 = Vec3(cos(a1) * rx, 0f, scz + sin(a1) * rz)
            tri(Vec3(0f, topY, scz), e1, e0, hull)        // domed top
            tri(Vec3(0f, botY, scz), e0, e1, hullDark)    // underside
        }

        // Neck connecting saucer to engineering hull.
        quad(Vec3(-0.07f, -0.02f, scz + 0.30f), Vec3(0.07f, -0.02f, scz + 0.30f),
             Vec3(0.09f, -0.14f, 0.12f), Vec3(-0.09f, -0.14f, 0.12f), hullDark)
        // Engineering hull (elongated) with gold deflector dish at the front.
        box(0f, -0.11f, 0.34f, 0.12f, 0.10f, 0.42f, hull, deflector)

        // Twin warp nacelles with glowing forward caps.
        box(-0.40f, 0.06f, 0.30f, 0.05f, 0.05f, 0.44f, nacelleCol, glow)
        box(0.40f, 0.06f, 0.30f, 0.05f, 0.05f, 0.44f, nacelleCol, glow)

        // Pylons linking hull to each nacelle.
        quad(Vec3(-0.10f, -0.05f, 0.28f), Vec3(-0.36f, 0.03f, 0.30f),
             Vec3(-0.36f, 0.03f, 0.52f), Vec3(-0.10f, -0.05f, 0.52f), hullDark)
        quad(Vec3(0.10f, -0.05f, 0.28f), Vec3(0.36f, 0.03f, 0.30f),
             Vec3(0.36f, 0.03f, 0.52f), Vec3(0.10f, -0.05f, 0.52f), hullDark)
    }.toFloatArray()

    private fun buildCockpitLines(): FloatArray = buildList {
        val glass = floatArrayOf(0.35f, 0.9f, 1f, 0.46f)
        addLine(Vec3(-0.9f, -0.35f, -0.25f), Vec3(-0.35f, 0.35f, -0.75f), glass)
        addLine(Vec3(0.9f, -0.35f, -0.25f), Vec3(0.35f, 0.35f, -0.75f), glass)
        addLine(Vec3(-0.35f, 0.35f, -0.75f), Vec3(0.35f, 0.35f, -0.75f), glass)
        addLine(Vec3(-1.0f, -0.42f, -0.18f), Vec3(1.0f, -0.42f, -0.18f), glass)
        addLine(Vec3(-0.28f, -0.42f, -0.18f), Vec3(-0.15f, -0.58f, -0.2f), floatArrayOf(1f, 0.1f, 0.08f, 0.8f))
        addLine(Vec3(0.28f, -0.42f, -0.18f), Vec3(0.15f, -0.58f, -0.2f), floatArrayOf(0.1f, 0.5f, 1f, 0.8f))
    }.toFloatArray()

    companion object {
        const val VIEW_COUNT = 4
        const val VIEW_BRIDGE = 0      // Sulu's seat at the main viewscreen
        const val VIEW_CHASE = 1       // behind the Enterprise
        const val VIEW_ENGINEERING = 2 // by the warp core
        const val VIEW_MEDITATION = 3  // observation lounge
        private const val BASE_SECONDS_PER_AU = 6.8f
        private const val TRAVEL_TIME_SCALE = 10f
        private const val MIN_LEG_SECONDS = 8f
        private const val APPROACH_FRAC = 0.8f
        private const val G = 0.02f   // gravitational constant (tuned for visible curved flybys)
        val VIEW_NAMES = arrayOf("BRIDGE - HELM", "EXTERNAL - CHASE", "ENGINEERING", "OBSERVATION LOUNGE")
    }

    private data class Body(
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val radius: Float,
        val base: FloatArray,
        val accent: FloatArray,
        val earthBands: Boolean,
        val legAu: Float
    )
    private data class Star(val x: Float, val y: Float, val z: Float, val r: Float, val g: Float, val b: Float)
    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private fun Vec3.normalized(): Vec3 {
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
        return Vec3(x / length, y / length, z / length)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun sinDeg(deg: Float): Float = sin(deg * PI.toFloat() / 180f)
    private fun cosDeg(deg: Float): Float = cos(deg * PI.toFloat() / 180f)

    private fun MutableList<Float>.addPoint(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
        add(x); add(y); add(z); add(r); add(g); add(b); add(a)
    }

    private fun MutableList<Float>.addLine(a: Vec3, b: Vec3, c: FloatArray) {
        addPoint(a.x, a.y, a.z, c[0], c[1], c[2], c[3])
        addPoint(b.x, b.y, b.z, c[0], c[1], c[2], c[3])
    }
}

private class SphereMesh(stacks: Int, slices: Int) {
    private val vertices: FloatBuffer
    private val vertexCount: Int

    init {
        val data = mutableListOf<Float>()
        for (stack in 0 until stacks) {
            val phi0 = PI.toFloat() * stack / stacks
            val phi1 = PI.toFloat() * (stack + 1) / stacks
            for (slice in 0..slices) {
                val theta = 2f * PI.toFloat() * slice / slices
                addSphereVertex(data, phi0, theta)
                addSphereVertex(data, phi1, theta)
            }
        }
        vertexCount = data.size / 6
        vertices = data.toFloatBuffer()
    }

    fun draw(positionHandle: Int, normalHandle: Int) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertices.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, vertices)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private fun addSphereVertex(data: MutableList<Float>, phi: Float, theta: Float) {
        val x = sin(phi) * cos(theta)
        val y = cos(phi)
        val z = sin(phi) * sin(theta)
        data.add(x); data.add(y); data.add(z)
        data.add(x); data.add(y); data.add(z)
    }
}

private class PointMesh(data: FloatArray) {
    private val vertices = data.toFloatBuffer()
    private val count = data.size / 7

    fun draw(positionHandle: Int, colorHandle: Int) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertices.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

private class TriMesh(data: FloatArray) {
    private val vertices = data.toFloatBuffer()
    private val count = data.size / 7

    fun draw(positionHandle: Int, colorHandle: Int) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertices.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

private class MeteorField(private val count: Int) {
    private val px = FloatArray(count); private val py = FloatArray(count); private val pz = FloatArray(count)
    private val vx = FloatArray(count); private val vy = FloatArray(count); private val vz = FloatArray(count)
    private val data = FloatArray(count * 2 * 7)
    private val buffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val rnd = java.util.Random(7)
    private var seeded = false

    private fun respawn(i: Int, cx: Float, cy: Float, cz: Float) {
        px[i] = cx + (rnd.nextFloat() - 0.5f) * 40f
        py[i] = cy + (rnd.nextFloat() - 0.5f) * 24f
        pz[i] = cz - 30f - rnd.nextFloat() * 45f       // spawn ahead of the camera (-z)
        vx[i] = (rnd.nextFloat() - 0.5f) * 2f
        vy[i] = (rnd.nextFloat() - 0.5f) * 2f
        vz[i] = 6f + rnd.nextFloat() * 12f             // drift back past the camera (+z)
    }

    fun update(cx: Float, cy: Float, cz: Float, dt: Float) {
        if (!seeded) { for (i in 0 until count) respawn(i, cx, cy, cz); seeded = true }
        for (i in 0 until count) {
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            if (pz[i] > cz + 8f) respawn(i, cx, cy, cz)
            val o = i * 14
            data[o] = px[i]; data[o + 1] = py[i]; data[o + 2] = pz[i]
            data[o + 3] = 1f; data[o + 4] = 0.95f; data[o + 5] = 0.8f; data[o + 6] = 0.9f      // bright head
            data[o + 7] = px[i] - vx[i] * 0.14f; data[o + 8] = py[i] - vy[i] * 0.14f; data[o + 9] = pz[i] - vz[i] * 0.14f
            data[o + 10] = 0.7f; data[o + 11] = 0.8f; data[o + 12] = 1f; data[o + 13] = 0f     // faded tail
        }
        buffer.position(0); buffer.put(data); buffer.position(0)
    }

    fun draw(positionHandle: Int, colorHandle: Int) {
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count * 2)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

private class LineMesh(
    data: FloatArray,
    private val lineVertexCount: Int,
    private val pointVertexCount: Int
) {
    private val vertices = data.toFloatBuffer()

    fun draw(positionHandle: Int, colorHandle: Int) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertices.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, vertices)
        GLES20.glEnableVertexAttribArray(colorHandle)
        if (lineVertexCount > 0) GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVertexCount)
        if (pointVertexCount > 0) GLES20.glDrawArrays(GLES20.GL_POINTS, lineVertexCount, pointVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

private class LitShader {
    private val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec3 aNormal;
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform mat4 uNormal;
        varying vec3 vNormal;
        varying vec3 vWorld;
        void main() {
            vNormal = normalize((uNormal * vec4(aNormal, 0.0)).xyz);
            vWorld = (uModel * vec4(aPosition, 1.0)).xyz;
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
        """,
        """
        precision mediump float;
        uniform vec4 uBase;
        uniform vec4 uAccent;
        uniform float uEarthBands;
        varying vec3 vNormal;
        varying vec3 vWorld;
        void main() {
            vec3 light = normalize(vec3(-0.35, 0.25, 0.9));
            float diffuse = max(dot(vNormal, light), 0.0);
            float rim = pow(1.0 - max(dot(vNormal, vec3(0.0, 0.0, 1.0)), 0.0), 2.0);
            float continents = smoothstep(0.2, 0.82, sin(vNormal.x * 9.0 + vNormal.y * 4.0) * sin(vNormal.z * 7.0));
            vec3 color = mix(uBase.rgb, uAccent.rgb, continents * uEarthBands);
            color = color * (0.18 + diffuse * 0.82) + vec3(0.20, 0.55, 0.95) * rim * uEarthBands;
            gl_FragColor = vec4(color, uBase.a);
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    private val normalMatrixHandle = GLES20.glGetUniformLocation(program, "uNormal")
    private val baseHandle = GLES20.glGetUniformLocation(program, "uBase")
    private val accentHandle = GLES20.glGetUniformLocation(program, "uAccent")
    private val earthBandsHandle = GLES20.glGetUniformLocation(program, "uEarthBands")

    fun use(mvp: FloatArray, model: FloatArray, normal: FloatArray, base: FloatArray, accent: FloatArray, earthBands: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normal, 0)
        GLES20.glUniform4fv(baseHandle, 1, base, 0)
        GLES20.glUniform4fv(accentHandle, 1, accent, 0)
        GLES20.glUniform1f(earthBandsHandle, earthBands)
    }
}

private class ColorShader {
    private val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec4 aColor;
        uniform mat4 uMvp;
        uniform float uPointSize;
        varying vec4 vColor;
        void main() {
            vColor = aColor;
            gl_Position = uMvp * vec4(aPosition, 1.0);
            gl_PointSize = uPointSize;
        }
        """,
        """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")

    fun use(mvp: FloatArray, pointSize: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform1f(pointSizeHandle, pointSize)
    }
}

private fun FloatArray.toFloatBuffer(): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(this)
    buffer.position(0)
    return buffer
}

private fun List<Float>.toFloatBuffer(): FloatBuffer = toFloatArray().toFloatBuffer()

private fun compileProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    require(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source.trimIndent())
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    require(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    return shader
}
