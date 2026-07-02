package com.paleblue

import android.content.Context
import android.media.MediaPlayer

/**
 * Looping ambient bed (full-stereo), one active track at a time with a short
 * manual crossfade. Cue-driven: {"ambient": "warp_drone"} or {"ambient": "stop"}.
 * Note: the ship hum was removed by design — the bridge idles in silence.
 */
class AmbientPlayer(private val ctx: Context) {
    private var current: MediaPlayer? = null
    private var fading: MediaPlayer? = null
    @Volatile private var baseVol = 0.8f

    fun set(name: String) {
        if (name == "stop") { stop(); return }
        val file = GlAssets.copyAssetToCache(ctx, "ambient/$name.ogg") ?: return
        runCatching {
            fading?.release()
            fading = current                       // old bed fades in applyGain()
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.isLooping = true
            mp.setOnPreparedListener {
                mp.setVolume(0f, 0f)
                if (!RideState.paused) mp.start()
            }
            mp.prepareAsync()
            current = mp
        }
    }

    /** Called every Director tick: eases the crossfade and applies the ambient bus gain. */
    fun applyGain() {
        val g = baseVol * RideState.ambientGain
        current?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.setVolume(g, g)   // fast attack is fine for beds
            }
        }
        fading?.let { mp ->
            runCatching { mp.setVolume(0f, 0f); mp.release() }
            fading = null
        }
    }

    fun pause() = runCatching { if (current?.isPlaying == true) current?.pause() }
    fun resume() = runCatching { current?.start() }
    fun stop() {
        runCatching { current?.release() }; current = null
        runCatching { fading?.release() }; fading = null
    }
}
