package com.paleblue

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.paleblue.gl.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Side-by-side dual-eye stereoscopic renderer (OpenGL ES 2.0).
 * Camera position comes from the rail (Director-driven); orientation =
 * rail heading * cue reframe * IMU gaze (full 360°). The ship's bow and
 * nacelles are world-anchored so looking around always tells you where
 * the ship is pointing; streaming dust gives real motion parallax.
 */
class StereoRenderer(
    private val ctx: Context,
    private val gaze: GazeCamera,
    private val minimap: MiniMap,
    private val crew: CrewAudio
) : GLSurfaceView.Renderer {

    companion object { const val EYE_OFFSET = 0.032f }

    private val world = SolarSystem(ctx)
    private val planetShader = PlanetShader()
    private val starShader = StarShader()
    private val lensingShader = LensingShader()
    private val shieldShader = ShieldShader()
    private val skybox = Skybox()
    private val nebula = Nebula()
    private val shipHull = ShipHull()
    private val hudRudders = HudRudders()
    private val dust = DustField()
    private val traffic = SpaceTraffic()
    private val dockScene = DockScene()
    private val rocks = RockField()
    private val letterbox = Letterbox()
    private val menuText = HudText(1100, 640, 52f, maxChars = 26, maxLines = 9)
    private val telemetry = TelemetryHud()
    private val captions = HudText(1600, 640, 88f, maxChars = 34, maxLines = 4)
    private val overlay = HudText(1024, 256, 58f, maxChars = 28, maxLines = 3)
    private val welcome = HudText(1800, 720, 83f, maxChars = 34, maxLines = 5)
    private val sceneTitle = HudText(1024, 192, 72f)
    private val sceneSub = HudText(1024, 128, 38f)
    private lateinit var sphere: Sphere
    private lateinit var shieldSphere: Sphere
    private var hyg: HygStars? = null
    private var ngc: OpenNgc? = null

    private var width = 0; private var height = 0
    private var fbo = 0; private var fboTex = 0; private var fboDepth = 0
    private var timeSec = 0f
    private var lastNs = 0L
    private var baseYaw = 0f; private var basePitch = 0f
    private var letterboxAmt = 0f
    private var introBlend = 1f          // 1 = exterior dock view, 0 = on the bridge
    private val prevCamPos = FloatArray(3)
    private var havePrev = false
    private val shipVel = FloatArray(3)
    private var smoothSpeed = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val viewNoTrans = FloatArray(16)
    private val vpNoTrans = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        sphere = Sphere(28, 56)
        shieldSphere = Sphere(18, 36)
        planetShader.init(); starShader.init(); lensingShader.init(); shieldShader.init()
        skybox.init(); nebula.init(); shipHull.init(); hudRudders.init(); dust.init()
        traffic.init(world); dockScene.init(); rocks.init(world); letterbox.init()
        telemetry.init(); captions.init(); overlay.init(); welcome.init(); sceneTitle.init(); sceneSub.init()
        menuText.init()
        minimap.init()
        world.initGl()
        hyg = HygStars(ctx)
        ngc = OpenNgc(ctx)
        lastNs = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        makeFbo(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        timeSec += dt

        // Ease cue reframing (no hard cuts) & decay shield impacts & letterbox
        baseYaw += (RideState.baseYawTarget - baseYaw) * (1f - exp(-dt * 1.6f))
        basePitch += (RideState.basePitchTarget - basePitch) * (1f - exp(-dt * 1.6f))
        RideState.impact *= exp(-dt * 1.6f)
        letterboxAmt += (RideState.letterboxTarget - letterboxAmt) * (1f - exp(-dt * 3.5f))

        var camPos = world.camPosAt(RideState.progress)
        val tangent = world.tangentAt(RideState.progress)

        // INTRO: before departure the camera orbits the ship docked at Earth;
        // on start it glides onto the bridge over ~4.5 s (no hard cut).
        if (RideState.started) introBlend = (introBlend - dt / 4.5f).coerceAtLeast(0f)
        var introYaw = 0f; var introPitch = 0f
        val introE = introBlend * introBlend * (3f - 2f * introBlend)
        if (introE > 0.002f) {
            val sp = DockScene.SHIP_POS
            val ang = timeSec * 0.10f
            val introEye = floatArrayOf(
                sp[0] + sin(ang) * 10.5f,
                sp[1] + 2.4f + sin(timeSec * 0.07f) * 1.1f,
                sp[2] + cos(ang) * 10.5f)
            val ix = sp[0] - introEye[0]; val iy = sp[1] - introEye[1]; val iz = sp[2] - introEye[2]
            val il = sqrt(ix * ix + iy * iy + iz * iz).coerceAtLeast(1e-4f)
            introYaw = atan2(ix / il, -iz / il)
            introPitch = asin((iy / il).coerceIn(-1f, 1f))
            camPos = floatArrayOf(
                camPos[0] + (introEye[0] - camPos[0]) * introE,
                camPos[1] + (introEye[1] - camPos[1]) * introE,
                camPos[2] + (introEye[2] - camPos[2]) * introE)
        }

        // ship velocity in world units/s -> dust parallax + engine glow
        if (havePrev && dt > 1e-4f) {
            shipVel[0] = (camPos[0] - prevCamPos[0]) / dt
            shipVel[1] = (camPos[1] - prevCamPos[1]) / dt
            shipVel[2] = (camPos[2] - prevCamPos[2]) / dt
            val sp = sqrt(shipVel[0] * shipVel[0] + shipVel[1] * shipVel[1] + shipVel[2] * shipVel[2])
            smoothSpeed += (sp - smoothSpeed) * 0.08f
        }
        prevCamPos[0] = camPos[0]; prevCamPos[1] = camPos[1]; prevCamPos[2] = camPos[2]
        havePrev = true

        // real physics, emotional arc: inverse-square sunlight from ship→Sun distance
        val ds = dist(camPos, SolarSystem.SUN_POS) / SolarSystem.AU_WORLD
        RideState.sunDistAu = ds
        val sunlight = (1f / (ds * ds)).coerceIn(0.015f, 4f)

        // ship heading (rail + reframe) — bias the neutral view toward the next
        // point of interest, while gaze rotates the HEAD freely around it.
        val poi = world.pointOfInterestAt(RideState.progress)
        val aim = if (poi != null) {
            val ax = poi[0] - camPos[0]
            val ay = poi[1] - camPos[1]
            val az = poi[2] - camPos[2]
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-5f)
            floatArrayOf(ax / al, ay / al, az / al)
        } else tangent
        val tYaw = atan2(aim[0], -aim[2])
        val tPitch = asin(aim[1].coerceIn(-1f, 1f))
        var shipYaw = tYaw + baseYaw
        var shipPitch = (tPitch + basePitch).coerceIn(-0.9f, 0.9f)
        if (introE > 0.002f) {
            shipYaw = lerpAngle(shipYaw, introYaw, introE)
            shipPitch += (introPitch - shipPitch) * introE
        }
        // Apply the RayNeo-remapped gaze in the focused camera's local basis.
        // Simple Euler addition cross-couples again when POI focus adds pitch.
        val baseDir = floatArrayOf(
            sin(shipYaw) * cos(shipPitch),
            sin(shipPitch),
            -cos(shipYaw) * cos(shipPitch)
        )
        val baseRight = norm(cross(baseDir, floatArrayOf(0f, 1f, 0f)))
        val baseUp = norm(cross(baseRight, baseDir))
        val gy = gaze.viewYaw
        val gp = gaze.viewPitch.coerceIn(-1.35f, 1.35f)
        val dir = norm(floatArrayOf(
            baseDir[0] * cos(gy) * cos(gp) + baseRight[0] * sin(gy) * cos(gp) + baseUp[0] * sin(gp),
            baseDir[1] * cos(gy) * cos(gp) + baseRight[1] * sin(gy) * cos(gp) + baseUp[1] * sin(gp),
            baseDir[2] * cos(gy) * cos(gp) + baseRight[2] * sin(gy) * cos(gp) + baseUp[2] * sin(gp)
        ))
        val right = norm(cross(dir, baseUp))

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val half = width / 2
        val eyeAspect = half.toFloat() / height
        Matrix.perspectiveM(projection, 0, 58f, eyeAspect, 0.2f, 1500f)

        val lensing = RideState.lensing
        if (lensing > 0.01f && fbo != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            drawWorldEye(0, half, camPos, dir, right, tangent, sunlight, -EYE_OFFSET, shipYaw, shipPitch, dt * 0.5f)
            drawWorldEye(half, width - half, camPos, dir, right, tangent, sunlight, +EYE_OFFSET, shipYaw, shipPitch, dt * 0.5f)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            drawLensedEye(0, half, camPos, dir, right, eyeAspect, -EYE_OFFSET)
            drawHudEye(0, half, eyeAspect, -1f)
            drawLensedEye(half, width - half, camPos, dir, right, eyeAspect, +EYE_OFFSET)
            drawHudEye(half, width - half, eyeAspect, +1f)
        } else {
            drawWorldEye(0, half, camPos, dir, right, tangent, sunlight, -EYE_OFFSET, shipYaw, shipPitch, dt * 0.5f)
            drawHudEye(0, half, eyeAspect, -1f)
            drawWorldEye(half, width - half, camPos, dir, right, tangent, sunlight, +EYE_OFFSET, shipYaw, shipPitch, dt * 0.5f)
            drawHudEye(half, width - half, eyeAspect, +1f)
        }
    }

    // ---------------- world pass ----------------
    private fun drawWorldEye(x: Int, viewportW: Int, camPos: FloatArray, dir: FloatArray,
                             right: FloatArray, tangent: FloatArray, sunlight: Float,
                             eyeOffset: Float, shipYaw: Float, shipPitch: Float, dustDt: Float) {
        GLES20.glViewport(x, 0, viewportW, height)
        val ex = camPos[0] + right[0] * eyeOffset
        val ey = camPos[1] + right[1] * eyeOffset
        val ez = camPos[2] + right[2] * eyeOffset
        val cx = ex + dir[0] - right[0] * eyeOffset * 0.35f
        val cy = ey + dir[1] - right[1] * eyeOffset * 0.35f
        val cz = ez + dir[2] - right[2] * eyeOffset * 0.35f
        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy, cz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        System.arraycopy(view, 0, viewNoTrans, 0, 16)
        viewNoTrans[12] = 0f; viewNoTrans[13] = 0f; viewNoTrans[14] = 0f
        Matrix.multiplyMM(vpNoTrans, 0, projection, 0, viewNoTrans, 0)

        val bandBoost = smooth(16f, 23f, RideState.progress)
        skybox.draw(vpNoTrans, bandBoost)
        hyg?.let { starShader.draw(it.buffer, it.count, vpNoTrans, RideState.beta, tangent) }
        ngc?.buffer?.let { starShader.draw(it, ngc!!.count, vpNoTrans, RideState.beta, tangent) }
        if (dist(camPos, world.nebulaCenter) < 500f) {
            val up = norm(cross(right, dir))
            nebula.draw(vp, world.nebulaCenter, right, up, world.nebulaSize, timeSec)
        }
        // motion parallax: streaming near-space dust (per-eye dt halved — updated twice/frame)
        dust.updateAndDraw(vp, floatArrayOf(ex, ey, ez), shipVel, dustDt, RideState.beta)
        // world-anchored traffic: same craft no matter where you look
        traffic.draw(vp, floatArrayOf(ex, ey, ez), SolarSystem.SUN_POS, sunlight, timeSec)
        // home dock: the intro tableau, and a landmark shrinking astern after launch
        dockScene.draw(vp, floatArrayOf(ex, ey, ez), SolarSystem.SUN_POS, sunlight, timeSec,
            shipDeparted = RideState.progress > 0.05f)
        // tumbling rocks & debris (belt/Kuiper dense, strays elsewhere)
        rocks.draw(vp, floatArrayOf(ex, ey, ez), SolarSystem.SUN_POS, sunlight, timeSec)

        world.drawBodies(planetShader, sphere, vp, floatArrayOf(ex, ey, ez), sunlight, timeSec)

        // World-anchored ship hull is intentionally not drawn in the forward
        // view; head-fixed rudders below the HUD provide the vehicle anchor
        // without floating away from the panels as the user looks around.

        if (RideState.impact > 0.01f) {
            val model = FloatArray(16)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, camPos[0], camPos[1], camPos[2])
            Matrix.rotateM(model, 0, Math.toDegrees(atan2(dir[0], -dir[2]).toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(model, 0, 1.9f, 1.3f, 2.8f)
            val mvp = FloatArray(16)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            val iy = RideState.impactYaw
            val impactDir = floatArrayOf(sin(iy), 0.15f, -cos(iy))
            shieldShader.draw(shieldSphere, mvp, model, floatArrayOf(ex, ey, ez),
                impactDir, RideState.impact, timeSec)
        }
    }

    // ---------------- lensing pass ----------------
    private fun drawLensedEye(x: Int, viewportW: Int, camPos: FloatArray, dir: FloatArray,
                              right: FloatArray, eyeAspect: Float, eyeOffset: Float) {
        GLES20.glViewport(x, 0, viewportW, height)
        val bh = SolarSystem.BLACK_HOLE_POS
        val ex = camPos[0] + right[0] * eyeOffset
        val ey = camPos[1] + right[1] * eyeOffset
        val ez = camPos[2] + right[2] * eyeOffset
        Matrix.setLookAtM(view, 0, ex, ey, ez,
            ex + dir[0], ey + dir[1], ez + dir[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(bh[0], bh[1], bh[2], 1f), 0)
        var strength = RideState.lensing
        val center = if (clip[3] > 0.05f) {
            floatArrayOf(clip[0] / clip[3] * 0.5f + 0.5f, clip[1] / clip[3] * 0.5f + 0.5f)
        } else { strength *= 0.15f; floatArrayOf(0.5f, 0.5f) }
        val uvRect = floatArrayOf(x.toFloat() / width, 0f, viewportW.toFloat() / width, 1f)
        lensingShader.draw(fboTex, uvRect, center, strength, eyeAspect)
    }

    // ---------------- HUD pass (head-fixed) ----------------
    private fun drawHudEye(x: Int, viewportW: Int, eyeAspect: Float, eyeSign: Float) {
        GLES20.glViewport(x, 0, viewportW, height)
        val hudAlpha = RideState.hudAlpha * (1f - letterboxAmt * 0.65f)
        val parallax = eyeSign * 0.006f

        minimap.draw(eyeAspect, parallax, timeSec, hudAlpha)
        // rudders belong to the bridge — hidden while the intro camera is outside
        hudRudders.draw(eyeAspect, parallax, timeSec, hudAlpha * (1f - introBlend),
            0.25f + RideState.beta * 1.2f)

        // science telemetry panel (left)
        telemetry.setLines(listOf(
            Triple("VEL", RideState.speedText, 0),
            Triple("γ-DIL", RideState.gammaText, 1),
            Triple("NEXT", RideState.nextPoiName, 2),
            Triple("DIST", RideState.nextPoiDistText, 0),
            Triple("ETA", RideState.nextPoiEtaText, 0),
            Triple("HULL", RideState.hullTempText, 1),
            Triple("SHLD", "${RideState.shieldPct.toInt()} %", if (RideState.shieldPct < 80f) 2 else 3)
        ))
        telemetry.draw(eyeAspect, parallax, hudAlpha)

        val nowMs = System.currentTimeMillis()
        if (!RideState.started) {
            welcome.setText("X3 GALAXY SIM — PROJECT PALE BLUE\nYour ship, docked at Earth Station Aurora.\nTap the right arm to fasten seatbelt and board.\nLook around freely — the tour guides itself.", backingBar = true)
            welcome.draw(parallax, 0.48f, 0.29f, eyeAspect, 0.95f)
        }
        val capFade = fadeAlpha(nowMs, RideState.captionUntilMs)
        if (RideState.subtitlesOn && capFade > 0f && RideState.caption.isNotBlank()) {
            captions.setText(captionPage(nowMs), backingBar = true)
            captions.draw(parallax, -0.44f, 0.22f, eyeAspect, capFade * RideState.hudAlpha)
        }
        val ovFade = fadeAlpha(nowMs, RideState.overlayUntilMs)
        if (ovFade > 0f && RideState.overlayText.isNotBlank()) {
            overlay.setText(RideState.overlayText, backingBar = true)
            overlay.draw(parallax, 0.15f, 0.09f, eyeAspect, ovFade)
        }

        // settings menu: swipe=move · tap=select · double-tap=exit
        if (RideState.menuOpen) {
            val items = listOf(
                "RESUME TOUR",
                "SAVE TOUR",
                if (RideState.restartConfirm) "CONFIRM RESTART?" else "RESTART TOUR",
                "SUBTITLES: " + if (RideState.subtitlesOn) "ON" else "OFF",
                RideState.mixModeLabel(),
                "RECENTER VIEW")
            val body = StringBuilder("— SETTINGS —")
            items.forEachIndexed { i, s ->
                body.append('\n').append(if (i == RideState.menuIndex) "▶ $s" else "· $s")
            }
            menuText.setText(body.toString(), backingBar = true)
            menuText.draw(parallax, 0.06f, 0.34f, eyeAspect, 0.97f)
        }

        // cut-scene: letterbox + chapter card (independent of hudAlpha)
        letterbox.draw(letterboxAmt)
        val scFade = fadeAlpha(nowMs, RideState.sceneUntilMs)
        if (scFade > 0f && RideState.sceneTitle.isNotBlank()) {
            sceneTitle.setText(RideState.sceneTitle, backingBar = false)
            sceneTitle.draw(parallax, 0.46f, 0.105f, eyeAspect, scFade)
            sceneSub.setText(RideState.sceneSub, backingBar = false)
            sceneSub.draw(parallax, 0.30f, 0.055f, eyeAspect, scFade * 0.9f)
        }
    }

    private fun fadeAlpha(now: Long, until: Long): Float =
        if (until <= 0L) 0f else ((until + 400 - now) / 400f).coerceIn(0f, 1f)

    /**
     * SUBTITLE SYNC: segments are sentence groups pre-cut by CrewAudio; the
     * current one is chosen by the MediaPlayer's REAL playback position
     * (pause-proof, latency-proof), with wall-clock as a fallback.
     */
    private fun captionPage(now: Long): String {
        val segs = RideState.captionSegments
        if (segs.isEmpty()) return RideState.caption
        var frac = crew.positionFrac()
        if (frac <= 0f) {
            val dur = RideState.captionDurationMs.coerceAtLeast(1200L)
            frac = ((now - RideState.captionStartedAtMs).coerceIn(0L, dur)).toFloat() / dur
        }
        for (seg in segs) if (frac <= seg.first) return seg.second
        return segs.last().second
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return a + d * t
    }

    // ---------------- helpers ----------------
    private fun makeFbo(w: Int, h: Int) {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(fboDepth), 0)
        }
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glGenTextures(1, ids, 0); fboTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenRenderbuffers(1, ids, 0); fboDepth = ids[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fboDepth)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER, fboDepth)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
