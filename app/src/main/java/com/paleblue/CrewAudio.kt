package com.paleblue

import android.content.Context
import android.media.MediaPlayer

/**
 * Crew dialogue engine: priority playback queue + spatial stereo panning +
 * the dialogue mix bus. RULE (gotcha #4): a clip is NEVER cut mid-word —
 * priority only sets queue ORDER. Navigation & Engineering jump ahead of
 * Science; Science waits its turn.
 *
 * Bridge seating (stereo pan): Engineering LEFT, Science RIGHT,
 * Navigation/Captain CENTER. Equal-power-ish per-channel volumes.
 */
class CrewAudio(private val ctx: Context) {

    data class Line(val role: String, val clip: String, val text: String, val seq: Long)

    private val queue = java.util.PriorityQueue<Line>(16) { a, b ->
        val p = priority(a.role) - priority(b.role)
        if (p != 0) p else a.seq.compareTo(b.seq)
    }
    private var player: MediaPlayer? = null
    @Volatile var currentRole: String? = null; private set
    @Volatile var lastLineEndedAt = System.currentTimeMillis(); private set
    @Volatile private var seqCounter = 0L
    private val lock = Object()

    private fun priority(role: String) = when (role.uppercase()) {
        "NAVIGATION" -> 0
        "ENGINEERING" -> 1
        else -> 2                      // SCIENCE yields
    }

    private fun folder(role: String) = when (role.uppercase()) {
        "NAVIGATION" -> "navigation"
        "ENGINEERING" -> "engineering"
        else -> "science"
    }

    private fun pan(role: String): Pair<Float, Float> = when (role.uppercase()) {
        "ENGINEERING" -> 1.0f to 0.30f    // left seat
        "SCIENCE" -> 0.30f to 1.0f        // right seat
        else -> 0.92f to 0.92f            // center
    }

    val isSpeaking: Boolean get() = player?.isPlaying == true

    /** Real playback fraction 0..1 of the current clip — drives caption sync. */
    fun positionFrac(): Float = player?.let { p ->
        runCatching {
            val d = p.duration
            if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
        }.getOrDefault(0f)
    } ?: 0f

    fun enqueue(role: String, clip: String, text: String) {
        synchronized(lock) {
            queue.add(Line(role, clip, text, seqCounter++))
            if (!isSpeaking) playNext()
        }
    }

    fun applyGain() {
        val p = player ?: return
        val role = currentRole ?: return
        val (l, r) = pan(role)
        runCatching { p.setVolume(l * RideState.dialogueGain, r * RideState.dialogueGain) }
    }

    fun pause() = runCatching { if (player?.isPlaying == true) player?.pause() }
    fun resume() = runCatching { player?.start() }

    fun release() {
        synchronized(lock) {
            queue.clear()
            player?.release(); player = null; currentRole = null
        }
    }

    /** Hard stop for RESTART: drop the queue and the current line, clear captions. */
    fun stopAll() {
        synchronized(lock) {
            queue.clear()
            runCatching { player?.stop() }
            player?.release(); player = null; currentRole = null
        }
        RideState.caption = ""
        RideState.captionSegments = emptyList()
        RideState.captionUntilMs = 0L
        lastLineEndedAt = System.currentTimeMillis()
    }

    private fun playNext() {
        val line = queue.poll() ?: return
        // GOTCHA #2: openFd() throws on AAPT-compressed assets — copy to cache, play from file.
        val file = GlAssets.copyAssetToCache(ctx, "voice/${folder(line.role)}/${line.clip}.ogg")
        if (file == null) {                       // missing clip: still show the caption briefly
            RideState.caption = stripTags(line.text)
            RideState.captionSegments = segment(RideState.caption)
            RideState.captionStartedAtMs = System.currentTimeMillis()
            RideState.captionDurationMs = 4000
            RideState.captionUntilMs = System.currentTimeMillis() + 4000
            lastLineEndedAt = System.currentTimeMillis()
            synchronized(lock) { playNext() }
            return
        }
        try {
            player?.release()
            val mp = MediaPlayer()
            player = mp
            currentRole = line.role
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                val (l, r) = pan(line.role)
                mp.setVolume(l * RideState.dialogueGain, r * RideState.dialogueGain)
                // native closed captions, sentence-segmented, synced to playback position
                RideState.caption = stripTags(line.text)
                RideState.captionSegments = segment(RideState.caption)
                RideState.captionStartedAtMs = System.currentTimeMillis()
                RideState.captionDurationMs = mp.duration.toLong().coerceAtLeast(1500L)
                RideState.captionUntilMs = RideState.captionStartedAtMs + RideState.captionDurationMs + 350
                if (!RideState.paused) mp.start()
            }
            mp.setOnCompletionListener {
                lastLineEndedAt = System.currentTimeMillis()
                currentRole = null
                synchronized(lock) { playNext() }
            }
            mp.setOnErrorListener { _, _, _ ->
                lastLineEndedAt = System.currentTimeMillis()
                currentRole = null
                synchronized(lock) { playNext() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            lastLineEndedAt = System.currentTimeMillis()
            currentRole = null
        }
    }

    companion object {
        /** Captions must not show Fish acting tags like [awe] or [gruff, strained]. */
        fun stripTags(text: String): String =
            text.replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("\\s+"), " ").trim()

        /**
         * Cuts the caption into sentence groups (~110 chars max) and assigns each
         * a cumulative end-fraction weighted by character count, so the on-screen
         * text advances at the same rate the voice actually speaks.
         */
        fun segment(text: String): List<Pair<Float, String>> {
            val sentences = Regex("(?<=[.!?…])\\s+").split(text).filter { it.isNotBlank() }
            if (sentences.isEmpty()) return listOf(1f to text)
            val groups = ArrayList<String>()
            var cur = StringBuilder()
            for (s in sentences) {
                if (cur.isNotEmpty() && cur.length + s.length + 1 > 110) {
                    groups.add(cur.toString()); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
            if (cur.isNotEmpty()) groups.add(cur.toString())
            val total = groups.sumOf { it.length }.coerceAtLeast(1).toFloat()
            var acc = 0f
            return groups.map { g ->
                acc += g.length / total
                acc.coerceAtMost(1f) to g
            }
        }
    }
}
