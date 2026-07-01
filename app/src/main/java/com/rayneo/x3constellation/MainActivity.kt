package com.rayneo.x3constellation

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var sceneView: X3ConstellationView
    private val audioEngine = SpaceAudioEngine()
    private val crewVoices by lazy { CrewVoices(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var telemetryView: TextView

    // Telemetry stays on screen and refreshes every 10 seconds.
    private val telemetryTicker = object : Runnable {
        override fun run() {
            telemetryView.text = sceneView.telemetry()
            uiHandler.postDelayed(this, 10_000)
        }
    }

    // Science officer reports a relevant fact about the nearest body every 30 seconds.
    private val scienceTicker = object : Runnable {
        override fun run() {
            crewVoices.speak(CrewVoices.Role.SCIENCE, scienceReport(sceneView.currentBodyName()))
            uiHandler.postDelayed(this, 30_000)
        }
    }

    // Navigation keeps up a steady stream of helm chatter.
    private val navigationTicker = object : Runnable {
        override fun run() {
            crewVoices.speak(CrewVoices.Role.NAVIGATION, navReport())
            uiHandler.postDelayed(this, 32_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sceneView = X3ConstellationView(this, audioEngine)
        sceneView.setApproachListener { planet -> uiHandler.post { onApproach(planet) } }
        sceneView.setViewListener { mode -> uiHandler.post { onViewChanged(mode) } }
        sceneView.setSpeedListener { warp -> uiHandler.post { onSpeedChanged(warp) } }
        telemetryView = TextView(this).apply {
            setTextColor(Color.rgb(36, 226, 167))
            setShadowLayer(8f, 0f, 0f, Color.rgb(30, 220, 255))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 18, 24, 18)
            alpha = 0.88f
        }
        val root = FrameLayout(this)
        root.addView(sceneView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            telemetryView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        crewVoices.init()
        audioEngine.start()
        sceneView.onResume()
        uiHandler.post(telemetryTicker)
        // Immediate confirmation the voice path works (Fish clip or TTS).
        uiHandler.postDelayed({
            crewVoices.speak(
                CrewVoices.Role.NAVIGATION,
                "[emphasis] All decks, this is the bridge. We are clear of Earth orbit... course laid in for the grand tour. Take us out.",
                "depart_earth"
            )
        }, 2500)
        uiHandler.postDelayed(scienceTicker, 18_000)
        uiHandler.postDelayed(navigationTicker, 9_000)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(telemetryTicker)
        uiHandler.removeCallbacks(scienceTicker)
        uiHandler.removeCallbacks(navigationTicker)
        sceneView.onPause()
        audioEngine.stop()
        super.onPause()
    }

    override fun onDestroy() {
        crewVoices.shutdown()
        super.onDestroy()
    }

    /**
     * Approaching a planet is a telemetry event: all three departments report,
     * staggered, and are heard in EVERY view (not tied to the current camera).
     */
    private fun onApproach(planet: String) {
        val key = planet.lowercase()
        crewVoices.speak(
            CrewVoices.Role.NAVIGATION,
            "Helm here — [emphasis] $planet, dead ahead. Adjusting heading, easing us into the flyby.",
            "approach_$key"
        )
        uiHandler.postDelayed({
            crewVoices.speak(CrewVoices.Role.SCIENCE, scienceReport(planet), "fact_$key")
        }, 5000)
        uiHandler.postDelayed({
            crewVoices.speak(
                CrewVoices.Role.ENGINEERING,
                "Engineering — trimming the nacelles as we round $planet. [chuckle] Core's purring.",
                "core_$key"
            )
        }, 10000)
    }

    /** View change only affects ambience; crew voices are telemetry-driven, not view-driven. */
    private fun onViewChanged(mode: Int) {
        audioEngine.setAmbience(mode)
    }

    /** Engineering calls out the new warp factor whenever speed changes (swipe). */
    private fun onSpeedChanged(warp: Int) {
        val line = when {
            warp >= 8 -> "Warp $warp! [emphasis] She's giving all she's got, Captain."
            warp >= 5 -> "Aye, warp $warp — engines answering smartly."
            warp <= 1 -> "Easing back to warp $warp. [chuckle] A gentle cruise, then."
            else -> "Warp $warp, steady as she goes."
        }
        crewVoices.speak(CrewVoices.Role.ENGINEERING, line)
    }

    private val scienceReports: Map<String, List<String>> = mapOf(
        "moon" to listOf(
            "Luna. Tidally locked, so she only ever shows us one face. Surface temperature swings from a hundred and twenty Celsius in daylight to minus one seventy in shadow.",
            "The Moon drifts away from Earth about four centimeters a year. [emphasis] Slowly leaving us behind."
        ),
        "mercury" to listOf(
            "Mercury. Ambient surface temperature runs from four hundred and thirty Celsius by day to minus one eighty by night, the widest swing in the system.",
            "No atmosphere, no moons. A scarred iron world barely larger than our own Moon."
        ),
        "venus" to listOf(
            "Venus. A steady four hundred and sixty five Celsius at the surface, hot enough to melt lead, beneath clouds of sulfuric acid.",
            "A day on Venus is longer than its year. And [emphasis] it spins backwards."
        ),
        "mars" to listOf(
            "Mars. Ambient temperature averages minus sixty three Celsius. Thin carbon dioxide air, and two small moons, Phobos and Deimos.",
            "Olympus Mons rises twenty two kilometers. [emphasis] Three times the height of Everest."
        ),
        "jupiter" to listOf(
            "Jupiter. Cloud top temperature near minus one hundred and ten Celsius. Ninety five known moons, and a storm wider than Earth.",
            "Its magnetic field is immense. If our eyes could see it, it would loom larger than the full Moon."
        ),
        "saturn" to listOf(
            "Saturn. Cloud temperature around minus one hundred and forty Celsius. The rings are nearly pure water ice, some chunks the size of houses.",
            "Its density is so low, [chuckle] Saturn would float in water, if you could find a tub big enough."
        ),
        "uranus" to listOf(
            "Uranus. An ice giant near minus two hundred Celsius, tipped a full ninety eight degrees. It rolls around the sun on its side.",
            "Twenty seven moons, all named for characters from Shakespeare and Pope."
        ),
        "neptune" to listOf(
            "Neptune. About minus two hundred and one Celsius, with the fastest winds in the system, over two thousand kilometers an hour.",
            "So distant it has completed only one orbit since its discovery in eighteen forty six."
        ),
        "pluto" to listOf(
            "Pluto. A frigid minus two hundred and twenty nine Celsius, with a heart shaped glacier of nitrogen ice.",
            "Its moon Charon is so large the two worlds orbit a point in the empty space between them."
        ),
        "earth" to listOf(
            "Earth. The only world we know that wears liquid water on its surface. [emphasis] Home.",
            "From here the atmosphere is a thin blue line. Fragile, and beautiful."
        )
    )

    private fun scienceReport(planet: String): String =
        scienceReports[planet.lowercase()]?.random()
            ?: "Sensors nominal. Deep space is quiet. Cosmic background holding at two point seven kelvin."

    private fun navReport(): String {
        val near = sceneView.currentBodyName()
        return listOf(
            "Helm, steady on course. Nearest body, $near, holding in the forward viewport.",
            "Navigation. Star fixes are clean, drift is nil. [emphasis] Smooth sailing.",
            "Adjusting trim for the local gravity well — she answers beautifully.",
            "Debris to starboard. Logging a few meteors on the scope.",
            "Course confirmed for the outer system. $near abeam, and pulling away.",
            "All ahead, Captain. The road is long, and the stars are patient."
        ).random()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
