package com.paleblue

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.paleblue.gl.MiniMap
import com.paleblue.gl.SolarSystem
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.random.Random

/**
 * Timeline orchestrator — the cinematic engine that owns pacing.
 * Data-driven from assets/timeline.json (single source of truth shared with the
 * TTS generator): keyframes map elapsed-ms -> rail progress; cues fire dialogue
 * (priority queue), sfx, ambient beds, reframes and shader params; a filler pool
 * guarantees the bridge never goes dead for more than ~10 s.
 * Ticks at ~10 Hz on its own thread.
 */
class Director(
    private val ctx: Context,
    private val crew: CrewAudio,
    private val sfx: SfxPlayer,
    private val ambient: AmbientPlayer,
    private val minimap: MiniMap
) {
    companion object {
        private const val TICK_MS = 100L
        private const val SILENCE_MS = 10_000L      // dead-bridge threshold
        private const val IMMINENT_MS = 12_000L     // don't filler if a line is coming
        private const val PREFS = "pale_blue"
        private const val C_AU_PER_S = 1.0 / 499.005    // light speed in AU/s
        private const val KM_PER_AU = 1.496e8
        private const val LY_PER_AU = 1.0 / 63241.1
    }

    private class Cue(val json: JSONObject) {
        val t = json.optLong("t", -1)
        val isDialogue = json.has("role") && json.has("clip") && json.has("text")
    }

    private var duration = 3_600_000L
    private var keyTimes = longArrayOf(0)
    private var keyProgress = floatArrayOf(0f)
    private val cues = ArrayList<Cue>()
    private val fillerPool = ArrayList<JSONObject>()
    private val fillerUnused = ArrayList<JSONObject>()
    private var cueIdx = 0
    private var elapsed = 0L
    private var hudTarget = 1f
    private val rnd = Random(System.nanoTime())

    // telemetry state
    private val rail = SolarSystem(ctx)          // pure rail math; GL never initialized here
    private var lastDistAu = 1.0
    private var lastProgressU = 0f
    private var speedAuS = 0.0                   // EMA
    private var tickCount = 0L

    // random intense events (5–15 min apart; aliens rarer)
    private var boot: JSONObject? = null
    private var events: JSONObject? = null
    private var nextEventElapsed = 0L
    private var lastAlienElapsed = -99_999_999L
    private var savedBeta = 0f

    // NO LINE EVER REPEATS: played-clip ledger + the Science officer's ongoing
    // history chronicle, which substitutes for any would-be repeat.
    private val playedClips = HashSet<String>()
    private val chronicle = ArrayList<JSONObject>()
    private var chronicleIdx = 0
    private var arrivals: JSONObject? = null
    private val firedArrivals = HashSet<String>()
    private var lastPoiName: String? = null

    private val thread = HandlerThread("Director").apply { start() }
    private val handler = Handler(thread.looper)
    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, TICK_MS) }
    }

    init {
        loadTimeline()
        handler.post(ticker)
    }

    private fun loadTimeline() {
        try {
            val text = ctx.assets.open("timeline.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            duration = root.optLong("durationMs", 3_600_000L)
            RideState.durationMs = duration
            val kf: JSONArray = root.getJSONArray("keyframes")
            keyTimes = LongArray(kf.length()); keyProgress = FloatArray(kf.length())
            for (i in 0 until kf.length()) {
                val pair = kf.getJSONArray(i)
                keyTimes[i] = pair.getLong(0)
                keyProgress[i] = pair.getDouble(1).toFloat()
            }
            val cs = root.getJSONArray("cues")
            for (i in 0 until cs.length()) cues.add(Cue(cs.getJSONObject(i)))
            cues.sortBy { it.t }
            val fl = root.optJSONArray("fillers") ?: JSONArray()
            for (i in 0 until fl.length()) fillerPool.add(fl.getJSONObject(i))
            fillerUnused.addAll(fillerPool.shuffled(rnd))
            boot = root.optJSONObject("boot")
            events = root.optJSONObject("events")
            arrivals = root.optJSONObject("arrivals")
            root.optJSONArray("chronicle")?.let { ch ->
                for (i in 0 until ch.length()) chronicle.add(ch.getJSONObject(i))
            }
            nextEventElapsed = 300_000L + rnd.nextLong(600_000L)   // first crisis 5–15 min in
        } catch (e: Exception) {
            android.util.Log.e("Director", "timeline.json failed to load", e)
        }
    }

    // ---------------- public controls (from gestures) ----------------

    /** Navigator reads the title screen aloud (Fish-voiced, plays pre-start). */
    fun playBootWelcome() {
        boot?.let { crew.enqueue(it.getString("role"), it.getString("clip"), it.getString("text")) }
    }

    /** Settings-menu RESTART: rewind the whole ride to the dock. */
    fun restart() {
        crew.stopAll()
        ambient.stop()
        elapsed = 0L
        cueIdx = 0
        fillerUnused.clear(); fillerUnused.addAll(fillerPool.shuffled(rnd))
        hudTarget = 1f
        nextEventElapsed = 300_000L + rnd.nextLong(600_000L)
        lastAlienElapsed = -99_999_999L
        playedClips.clear()
        chronicleIdx = 0
        firedArrivals.clear()
        lastPoiName = null
        RideState.progress = 0f
        RideState.elapsedMs = 0L
        RideState.beta = 0f; RideState.lensing = 0f; RideState.impact = 0f
        RideState.caption = ""; RideState.captionUntilMs = 0L
        RideState.captionSegments = emptyList()
        RideState.sceneUntilMs = 0L; RideState.letterboxTarget = 0f
        RideState.hudAlpha = 1f
        RideState.shieldPct = 100f
        RideState.eventKind = 0
        RideState.finished = false
        RideState.started = true
        RideState.paused = false
    }

    fun startOrResumeFromSave() {
        if (RideState.started) return
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("elapsedMs", 0L)
        if (saved in 1 until duration - 60_000) {
            elapsed = saved
            catchUpStateTo(saved)
        }
        RideState.started = true
        RideState.paused = false
    }

    fun pause() {
        RideState.paused = true
        crew.pause(); ambient.pause()
    }

    fun resume() {
        RideState.paused = false
        crew.resume(); ambient.resume()
    }

    /** Double tap: serialize current timestamp + rail coordinate to local storage. */
    fun saveTour() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("elapsedMs", elapsed)
            .putFloat("progress", RideState.progress)
            .apply()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ---------------- tick ----------------

    private fun tick() {
        // bus gains follow the audio-mix cycler every tick
        crew.applyGain(); ambient.applyGain()
        // ease HUD alpha (finale fade)
        RideState.hudAlpha += (hudTarget - RideState.hudAlpha) * (1f - exp(-TICK_MS / 1000f * 1.2f))

        if (!RideState.started || RideState.paused || RideState.finished) return
        elapsed += TICK_MS
        RideState.elapsedMs = elapsed
        if (elapsed >= duration) { RideState.finished = true; return }

        RideState.progress = progressAt(elapsed)
        minimap.update(RideState.progress, SolarSystem.MAX_U)

        while (cueIdx < cues.size && cues[cueIdx].t <= elapsed) {
            fire(cues[cueIdx].json, playAudio = true)
            cueIdx++
        }
        maybeFillSilence()

        // retract cut-scene letterbox when the card expires; regen shields
        if (RideState.letterboxTarget > 0f && System.currentTimeMillis() > RideState.sceneUntilMs) {
            RideState.letterboxTarget = 0f
        }
        RideState.shieldPct = (RideState.shieldPct + 0.06f).coerceAtMost(100f)

        maybeFireEvent()
        updateTelemetry()
    }

    // ---------------- random intense events ----------------

    private fun maybeFireEvent() {
        if (events == null) return
        if (RideState.eventKind != 0) {
            // clear the visual slot once its window has passed
            if (System.currentTimeMillis() > RideState.eventStartWallMs + RideState.eventDurMs) {
                RideState.eventKind = 0
            }
            return
        }
        if (elapsed < nextEventElapsed) return
        // never trample the Sgr A* climax or the finale
        if (RideState.lensing > 0.25f || elapsed > duration - 420_000L) {
            nextEventElapsed = elapsed + 120_000L
            return
        }
        nextEventElapsed = elapsed + 300_000L + rnd.nextLong(600_000L)   // 5–15 min to the next one
        var kinds = listOf("near_miss", "near_miss", "engine_trouble", "engine_trouble",
            "debris_strike", "alien")
        if (elapsed - lastAlienElapsed < 1_200_000L || elapsed < 480_000L) {
            kinds = kinds.filter { it != "alien" }                        // hostiles are rare
        }
        val kind = kinds[rnd.nextInt(kinds.size)]
        val withAlly = kind == "alien" || rnd.nextFloat() < 0.45f         // nations lend a hand
        // ship heading right now, for the ship-relative event choreography
        val t = rail.tangentAt(RideState.progress)
        RideState.eventShipYaw = kotlin.math.atan2(t[0], -t[2])
        when (kind) {
            "near_miss" -> {
                startEventVisual(1, 14_000L)
                sfx.play("alarm_klaxon")
                after(1_500) { RideState.impact = 0.55f; sfx.play("whoosh_flyby") }
                speakVariant("near_miss")
                if (withAlly) after(15_000) { allyArrives() }
            }
            "engine_trouble" -> {
                savedBeta = RideState.beta
                sfx.play("thruster_sputter")
                after(400) { RideState.beta = savedBeta * 0.25f }
                after(2_500) { sfx.play("alarm_soft") }
                after(9_000) { sfx.play("thruster_sputter") }
                after(22_000) { sfx.play("engine_ignite"); RideState.beta = savedBeta }
                speakVariant("engine_trouble")
                if (withAlly) after(12_000) { allyArrives() }
            }
            "debris_strike" -> {
                sfx.play("shield_impact")
                RideState.impact = 1.0f
                RideState.impactYaw = rnd.nextFloat() * 6.28f
                RideState.shieldPct = (RideState.shieldPct - 14f).coerceAtLeast(35f)
                after(2_800) { sfx.play("ring_ping"); RideState.impact = 0.5f }
                speakVariant("debris_strike")
                if (withAlly) after(11_000) { allyArrives() }
            }
            "alien" -> {
                lastAlienElapsed = elapsed
                // long, VISIBLE encounter: fire only before the Captain's hail —
                // the second half is diplomacy, and the guns stay silent
                startEventVisual(2, 44_000L)
                sfx.play("alarm_klaxon")
                for (d in longArrayOf(3_000, 8_000, 13_000)) {
                    after(d) { sfx.play("weapon_zap"); RideState.impact = 0.9f
                        RideState.impactYaw = rnd.nextFloat() * 6.28f
                        RideState.shieldPct = (RideState.shieldPct - 6f).coerceAtLeast(30f) }
                }
                after(26_000) { sfx.play("comm_chirp") }   // they answer the hail
                speakVariant("alien")
                after(45_000) { allyArrives() }            // escort joins after the peace
            }
        }
    }

    private fun startEventVisual(kind: Int, durMs: Long) {
        RideState.eventSeed = rnd.nextInt(1 shl 20)
        RideState.eventDurMs = durMs
        RideState.eventStartWallMs = System.currentTimeMillis()
        RideState.eventKind = kind
    }

    /** An allied national vessel (CNSA/ESA/ISRO/JAXA) flies escort + hails. */
    private fun allyArrives() {
        val allies = events?.optJSONArray("ally") ?: return
        if (allies.length() == 0) return
        val v = allies.getJSONObject(rnd.nextInt(allies.length()))
        RideState.eventSeed = v.optInt("seed", 0)
        RideState.eventDurMs = 50_000L
        RideState.eventStartWallMs = System.currentTimeMillis()
        RideState.eventKind = 3
        sfx.play("comm_chirp")
        speakLines(v.getJSONArray("lines"))
    }

    private fun speakVariant(type: String) {
        val variants = events?.optJSONArray(type) ?: return
        if (variants.length() == 0) return
        speakLines(variants.getJSONArray(rnd.nextInt(variants.length())))
    }

    private fun speakLines(lines: JSONArray) {
        for (i in 0 until lines.length()) {
            val l = lines.getJSONObject(i)
            after(l.optLong("d", 0L)) {
                enqueueUnique(l.getString("role"), l.getString("clip"), l.getString("text"))
            }
        }
    }

    private fun after(ms: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, ms)
    }

    // ---------------- science telemetry ----------------

    private fun updateTelemetry() {
        tickCount++
        val u = RideState.progress
        val dNow = MiniMap.shipAt(u).second                     // real distance from Sol (AU)
        // narrative speed: real radial rate blended with world-space rail rate
        val radial = Math.abs(dNow - lastDistAu) / (TICK_MS / 1000.0)
        val a = rail.camPosAt(lastProgressU); val b = rail.camPosAt(u)
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val worldAuS = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()) /
                SolarSystem.AU_WORLD / (TICK_MS / 1000.0)
        speedAuS += (maxOf(radial, worldAuS) - speedAuS) * 0.12
        lastDistAu = dNow; lastProgressU = u

        if (tickCount % 5 != 0L) return                         // text at 2 Hz is plenty
        RideState.speedText = formatSpeed(speedAuS)
        val beta = RideState.beta.toDouble().coerceIn(0.0, 0.9999)
        RideState.gammaText = String.format("%.3f", 1.0 / Math.sqrt(1.0 - beta * beta))
        val tempK = (278.6 / Math.sqrt(dNow.coerceAtLeast(0.004))).coerceIn(3.0, 2600.0)
        RideState.hullTempText = if (dNow > 200) "2.7 K (CMB)" else "${tempK.toInt()} K"

        // next point of interest: live distance + ETA
        val next = MiniMap.WAYPOINTS.firstOrNull { it.u > u + 0.05f }
        val nextName = next?.name ?: "HOME"
        // countdown landed: the POI we were counting toward is now HERE —
        // dialogue + view snap to it together
        if (lastPoiName != null && lastPoiName != nextName && elapsed > 5_000L) {
            fireArrival(lastPoiName!!)
        }
        lastPoiName = nextName
        if (next == null) {
            RideState.nextPoiName = "HOME"
            RideState.nextPoiDistText = "—"
            RideState.nextPoiEtaText = "--:--"
        } else {
            RideState.nextPoiName = next.name
            RideState.nextPoiDistText = formatDistDelta(Math.abs(next.distAu - dNow))
            val etaMs = (timeForProgress(next.u) - elapsed).coerceAtLeast(0L)
            RideState.nextPoiEtaText = String.format("%02d:%02d", etaMs / 60000, etaMs / 1000 % 60)
        }
    }

    /** Inverts the keyframe table: first ride-time at which rail progress reaches [u]. */
    private fun timeForProgress(u: Float): Long {
        if (u <= keyProgress.first()) return keyTimes.first()
        for (i in 0 until keyProgress.size - 1) {
            if (keyProgress[i + 1] >= u && keyProgress[i] <= u) {
                val span = keyProgress[i + 1] - keyProgress[i]
                val f = if (span > 1e-5f) (u - keyProgress[i]) / span else 1f
                return keyTimes[i] + ((keyTimes[i + 1] - keyTimes[i]) * f).toLong()
            }
        }
        return keyTimes.last()
    }

    private fun formatSpeed(auS: Double): String {
        if (!RideState.started || auS < 1e-9) return "HOLDING"
        val c = auS / C_AU_PER_S
        return when {
            c < 0.0005 -> String.format("%.1f km/s", auS * KM_PER_AU)
            c < 1.0 -> String.format("%.3f c", c)
            c < 1000.0 -> String.format("%.1f c", c)
            c < 1e6 -> String.format("%,.0f c", c)
            else -> String.format("%.2f Mc", c / 1e6)
        }
    }

    private fun formatDistDelta(dAu: Double): String = when {
        dAu < 0.004 -> String.format("%,.0f km", dAu * KM_PER_AU)
        dAu < 1000.0 -> String.format("%.2f AU", dAu)
        dAu * LY_PER_AU < 1000.0 -> String.format("%.1f ly", dAu * LY_PER_AU)
        else -> String.format("%.1f kly", dAu * LY_PER_AU / 1000.0)
    }

    private fun progressAt(ms: Long): Float {
        if (keyTimes.isEmpty()) return 0f
        if (ms <= keyTimes.first()) return keyProgress.first()
        if (ms >= keyTimes.last()) return keyProgress.last()
        var i = 0
        while (i < keyTimes.size - 1 && keyTimes[i + 1] < ms) i++
        val t0 = keyTimes[i]; val t1 = keyTimes[i + 1]
        val f = (ms - t0).toFloat() / (t1 - t0).coerceAtLeast(1)
        // smoothstep between keyframes: linger = flat segments, transit = steep, always eased
        val s = f * f * (3f - 2f * f)
        return keyProgress[i] + (keyProgress[i + 1] - keyProgress[i]) * s
    }

    // ---------------- cue firing ----------------

    private fun fire(c: JSONObject, playAudio: Boolean) {
        c.optString("reframe").takeIf { it.isNotEmpty() }?.let { r ->
            when (r) {
                "forward" -> { RideState.baseYawTarget = 0f; RideState.basePitchTarget = 0f }
                "left" -> RideState.baseYawTarget = -0.65f
                "right" -> RideState.baseYawTarget = 0.65f
                "up" -> { RideState.basePitchTarget = 0.38f }
                "down" -> { RideState.basePitchTarget = -0.25f }
                "back" -> RideState.baseYawTarget = 3.05f     // the Look Back
            }
        }
        c.optJSONObject("shader")?.let { s ->
            if (s.has("beta")) RideState.beta = s.optDouble("beta", 0.0).toFloat()
            if (s.has("lensing")) RideState.lensing = s.optDouble("lensing", 0.0).toFloat()
            if (s.has("impact")) {
                val v = s.optDouble("impact", 0.0).toFloat()
                RideState.impact = v
                RideState.impactYaw = rnd.nextFloat() * 6.28f
                RideState.shieldPct = (RideState.shieldPct - v * 12f).coerceAtLeast(38f)
            }
        }
        if (c.has("hud")) hudTarget = c.optDouble("hud", 1.0).toFloat()
        // cut-scene chapter card: letterbox in, title up, chime
        c.optJSONObject("scene")?.let { s ->
            RideState.sceneTitle = s.optString("title", "")
            RideState.sceneSub = s.optString("sub", "")
            RideState.sceneUntilMs = System.currentTimeMillis() + 7000
            RideState.letterboxTarget = 1f
            if (playAudio) sfx.play("scene_chime", 0.9f)
        }
        if (playAudio) {
            c.optString("sfx").takeIf { it.isNotEmpty() }?.let { sfx.play(it) }
            c.optString("ambient").takeIf { it.isNotEmpty() }?.let { ambient.set(it) }
            if (c.has("role") && c.has("clip") && c.has("text")) {
                enqueueUnique(c.getString("role"), c.getString("clip"), c.getString("text"))
            }
        }
    }

    /**
     * THE NO-REPEAT RULE: every clip plays at most once per tour. Any line that
     * would repeat is replaced by the next episode of the Science officer's
     * chronicle — the ongoing story of how humanity outgrew feudalism,
     * exploitation, the Third World War, capitalism's blind spots, and the
     * warming century, guided by Sagan's pale-blue-dot ethic.
     */
    private fun enqueueUnique(role: String, clip: String, text: String) {
        if (playedClips.add(clip)) {
            crew.enqueue(role, clip, text)
        } else {
            nextChronicleEpisode()
        }
    }

    private fun nextChronicleEpisode() {
        if (chronicle.isEmpty() || chronicleIdx >= chronicle.size) return   // saga told in full
        val ep = chronicle[chronicleIdx++]
        val clip = ep.getString("clip")
        if (playedClips.add(clip)) {
            crew.enqueue(ep.getString("role"), clip, ep.getString("text"))
        }
    }

    /** Telemetry countdown reached a POI: call it out, tell the guest where to look. */
    private fun fireArrival(name: String) {
        if (!firedArrivals.add(name)) return
        val a = arrivals?.optJSONObject(name) ?: return
        // visuals match: neutral view re-centers on the POI (pointOfInterestAt),
        // so square the reframe to forward before the callout lands
        RideState.baseYawTarget = 0f
        RideState.basePitchTarget = 0f
        enqueueUnique(a.getString("role"), a.getString("clip"), a.getString("text"))
    }

    /** After resuming a saved tour: re-apply state cues without replaying old audio. */
    private fun catchUpStateTo(ms: Long) {
        var lastAmbient: String? = null
        cueIdx = 0
        while (cueIdx < cues.size && cues[cueIdx].t <= ms) {
            val j = cues[cueIdx].json
            fire(j, playAudio = false)
            j.optString("ambient").takeIf { it.isNotEmpty() }?.let { lastAmbient = it }
            // skipped lines count as heard — the no-repeat ledger stays honest
            if (j.has("clip")) playedClips.add(j.getString("clip"))
            cueIdx++
        }
        lastAmbient?.let { ambient.set(it) }
        RideState.progress = progressAt(ms)
    }

    // ---------------- silence filler ----------------

    private fun maybeFillSilence() {
        if (crew.isSpeaking) return
        val now = System.currentTimeMillis()
        if (now - crew.lastLineEndedAt < SILENCE_MS) return
        // is a scripted line imminent?
        var i = cueIdx
        while (i < cues.size && cues[i].t <= elapsed + IMMINENT_MS) {
            if (cues[i].isDialogue) return
            i++
        }
        if (fillerUnused.isEmpty()) {
            // filler pool spent: NEVER reshuffle-repeat — continue the chronicle instead
            nextChronicleEpisode()
            return
        }
        val f = fillerUnused.removeAt(fillerUnused.size - 1)
        enqueueUnique(f.optString("role", "SCIENCE"), f.getString("clip"), f.getString("text"))
    }
}
