package com.paleblue

/**
 * Single shared, lock-free state blackboard between the Director (logic thread),
 * the GL renderer, and the audio engines. All writes are @Volatile primitives.
 */
object RideState {
    // Rail
    @Volatile var progress = 0f            // 0..N rail units (interpolated from keyframes)
    @Volatile var elapsedMs = 0L
    @Volatile var durationMs = 3_600_000L
    @Volatile var started = false
    @Volatile var paused = false
    @Volatile var finished = false

    // Cue-driven shader intensities
    @Volatile var beta = 0f                // v/c — Doppler shift
    @Volatile var lensing = 0f             // 0..1 — Sgr A* gravitational lensing
    @Volatile var impact = 0f              // shield impact flare, decays in renderer
    @Volatile var impactYaw = 0f           // approximate direction of last impact (radians)

    // Camera re-framing (eased by renderer, radians)
    @Volatile var baseYawTarget = 0f
    @Volatile var basePitchTarget = 0f

    // HUD
    @Volatile var hudAlpha = 1f            // finale fades HUD to 0
    @Volatile var caption = ""
    @Volatile var captionStartedAtMs = 0L
    @Volatile var captionDurationMs = 0L
    @Volatile var captionUntilMs = 0L      // wall-clock deadline for current caption
    @Volatile var captionSegments: List<Pair<Float, String>> = emptyList()  // (endFrac, text)
    @Volatile var overlayText = ""         // transient center message ("FASTEN SEATBELT", audio-mix)
    @Volatile var overlayUntilMs = 0L

    // Cut-scene chapter cards (letterbox + title), cue-driven
    @Volatile var sceneTitle = ""
    @Volatile var sceneSub = ""
    @Volatile var sceneUntilMs = 0L
    @Volatile var letterboxTarget = 0f

    // Science telemetry (computed by Director, rendered by TelemetryHud)
    @Volatile var speedText = "HOLDING"
    @Volatile var gammaText = "1.000"
    @Volatile var nextPoiName = "LUNA"
    @Volatile var nextPoiDistText = "—"
    @Volatile var nextPoiEtaText = "--:--"
    @Volatile var hullTempText = "291 K"
    @Volatile var shieldPct = 100f

    // Settings menu (double-tap) + subtitles
    @Volatile var menuOpen = false
    @Volatile var menuIndex = 0
    @Volatile var menuMode = 0             // 0 main settings, 1 segment picker
    @Volatile var segmentIndex = 0         // highlighted waypoint in the picker
    @Volatile var restartConfirm = false
    @Volatile var subtitlesOn = true

    // Random intense events (Director schedules; SpaceTraffic renders)
    @Volatile var eventKind = 0            // 0 none, 1 rogue hauler, 2 alien, 3 allied escort
    @Volatile var eventStartWallMs = 0L
    @Volatile var eventDurMs = 0L
    @Volatile var eventSeed = 0
    @Volatile var eventShipYaw = 0f        // ship heading captured at trigger

    // Audio mix buses (0f or 1f), cycled by long-press menu
    @Volatile var dialogueGain = 1f
    @Volatile var sfxGain = 1f
    @Volatile var ambientGain = 1f
    @Volatile var mixMode = 0              // 0 full, 1 dlg+amb, 2 amb+sfx, 3 mute

    // Physics feedback from renderer (ship→Sun distance in AU, for inverse-square light)
    @Volatile var sunDistAu = 1.0f

    fun applyMixMode(mode: Int) {
        mixMode = mode
        when (mode) {
            0 -> { dialogueGain = 1f; sfxGain = 1f; ambientGain = 1f }
            1 -> { dialogueGain = 1f; sfxGain = 0f; ambientGain = 1f }
            2 -> { dialogueGain = 0f; sfxGain = 1f; ambientGain = 1f }
            else -> { dialogueGain = 0f; sfxGain = 0f; ambientGain = 0f }
        }
    }

    fun mixModeLabel(): String = when (mixMode) {
        0 -> "AUDIO MIX: FULL"
        1 -> "AUDIO MIX: DIALOGUE + AMBIENT"
        2 -> "AUDIO MIX: AMBIENT + SFX"
        else -> "AUDIO MIX: MUTE ALL"
    }
}
