package com.paleblue

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.paleblue.gl.MiniMap

/**
 * GLSurfaceView host + HUD/captions + temple-touchpad gestures.
 * Single tap: start / resume (or cycle audio-mix while the mix HUD is open).
 * Double tap: save tour. Long press (2 s): open the Audio Mix HUD.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var gaze: GazeCamera
    private lateinit var crew: CrewAudio
    private lateinit var sfx: SfxPlayer
    private lateinit var ambient: AmbientPlayer
    private lateinit var director: Director
    private lateinit var gestures: TouchpadGestures
    private val minimap = MiniMap()
    private var mixMenuOpenUntil = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        gaze = GazeCamera(this)
        crew = CrewAudio(this)
        sfx = SfxPlayer(this)
        ambient = AmbientPlayer(this)
        director = Director(this, crew, sfx, ambient, minimap)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(StereoRenderer(this@MainActivity, gaze, minimap, crew))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)

        gestures = TouchpadGestures(
            onSingleTap = { onSingleTap() },
            onDoubleTap = { onDoubleTap() },
            onLongPress = { onLongPress() },
            onSwipeForward = { onSwipeMenu(1) },
            onSwipeBack = { onSwipeMenu(-1) }
        )

        overlay("SINGLE TAP TO FASTEN SEATBELT", 12_000)
        // the Navigator reads the title card aloud (Fish-voiced)
        glView.postDelayed({ director.playBootWelcome() }, 1600)
    }

    // ---------------- gestures ----------------

    private val menuItemCount = 6

    private fun onSingleTap() {
        val now = System.currentTimeMillis()
        when {
            RideState.menuOpen -> selectMenuItem()
            now < mixMenuOpenUntil -> {                       // quick Audio Mix cycler
                RideState.applyMixMode((RideState.mixMode + 1) % 4)
                mixMenuOpenUntil = now + 4000
                overlay(RideState.mixModeLabel(), 4000)
                sfx.play("ui_tap", 0.7f)
            }
            !RideState.started -> {                           // State 1: Fasten Seatbelt
                director.startOrResumeFromSave()
                sfx.play("seatbelt_chime")
                overlay("SEATBELT FASTENED — DEPARTING", 5000)
            }
            RideState.paused -> {                             // State 2: Resume
                director.resume()
                overlay("RESUMED", 2500)
            }
            else -> {
                director.pause()
                overlay("PAUSED — TAP TO RESUME", 6000)
            }
        }
    }

    /** Double tap: open the settings menu, or close it if already open. */
    private fun onDoubleTap() {
        if (!RideState.menuOpen) {
            RideState.menuIndex = 0
            RideState.menuOpen = true
            if (RideState.started && !RideState.paused) director.pause()
            overlay("SWIPE: NAVIGATE · TAP: SELECT · DOUBLE-TAP: EXIT", 5000)
            sfx.play("ui_tap", 0.8f)
            return
        }
        RideState.menuOpen = false
        RideState.restartConfirm = false
        overlay(if (RideState.paused) "PAUSED — TAP TO RESUME" else "", 3000)
        sfx.play("ui_tap", 0.7f)
    }

    private fun onSwipeMenu(delta: Int) {
        if (!RideState.menuOpen) return
        RideState.menuIndex = (RideState.menuIndex + delta + menuItemCount) % menuItemCount
        RideState.restartConfirm = false
        sfx.play("ui_tap", 0.55f)
    }

    private fun selectMenuItem() {
        when (RideState.menuIndex) {
            0 -> {                                            // RESUME TOUR
                RideState.menuOpen = false
                RideState.restartConfirm = false
                if (RideState.started) director.resume()
                overlay("RESUMED", 2000)
            }
            1 -> {                                            // SAVE TOUR
                RideState.restartConfirm = false
                director.saveTour()
                sfx.play("ui_save", 0.8f)
                overlay("TOUR SAVED", 2500)
            }
            2 -> {                                            // RESTART TOUR
                if (!RideState.restartConfirm) {
                    RideState.restartConfirm = true
                    overlay("TAP RESTART AGAIN TO CONFIRM", 3500)
                    sfx.play("ui_tap", 0.7f)
                } else {
                    RideState.menuOpen = false
                    RideState.restartConfirm = false
                    director.restart()
                    overlay("RESTARTING FROM EARTH", 3500)
                }
            }
            3 -> {                                            // SUBTITLES
                RideState.restartConfirm = false
                RideState.subtitlesOn = !RideState.subtitlesOn
                sfx.play("ui_tap", 0.7f)
            }
            4 -> {                                            // AUDIO MIX
                RideState.restartConfirm = false
                RideState.applyMixMode((RideState.mixMode + 1) % 4)
                sfx.play("ui_tap", 0.7f)
            }
            5 -> {                                            // RECENTER VIEW
                RideState.restartConfirm = false
                gaze.recenter()
                overlay("VIEW RECENTERED", 2000)
                sfx.play("ui_tap", 0.7f)
            }
        }
    }

    private fun onLongPress() {
        if (RideState.menuOpen) {                             // close menu (stays paused)
            RideState.menuOpen = false
            RideState.restartConfirm = false
            overlay(if (RideState.paused) "PAUSED — TAP TO RESUME" else "", 4000)
            sfx.play("ui_tap", 0.7f)
            return
        }
        mixMenuOpenUntil = System.currentTimeMillis() + 4000
        overlay(RideState.mixModeLabel() + "\nTAP TO CYCLE", 4000)
        sfx.play("ui_tap", 0.7f)
    }

    private fun overlay(text: String, ms: Long) {
        RideState.overlayText = text
        RideState.overlayUntilMs = System.currentTimeMillis() + ms
    }

    // Route temple-touchpad motion events (cyttsp5 = right arm, cyttsp6 = left arm)
    // into the gesture state machine. Screen touches are routed too so the build
    // is testable on a phone/emulator.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestures.isTouchpad(ev)) return gestures.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.isTouchpad(ev)) return gestures.onTouchEvent(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        glView.onResume()
        gaze.start()
        hideSystemUi()
    }

    override fun onPause() {
        glView.onPause()
        gaze.stop()
        if (RideState.started && !RideState.paused) {
            director.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        director.release()
        crew.release()
        ambient.stop()
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
