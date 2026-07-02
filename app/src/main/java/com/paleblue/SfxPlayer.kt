package com.paleblue

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** One-shot bridge sound effects via SoundPool, on the sfx mix bus. */
class SfxPlayer(private val ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
        .build()
    private val loaded = HashMap<String, Int>()
    private val ready = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(ready) { ready.add(sampleId) }
        }
    }

    /** Plays assets/sfx/<name>.ogg (copied to cache once). Preloads on first use. */
    fun play(name: String, volume: Float = 1f) {
        val gain = volume * RideState.sfxGain
        val id = loaded[name] ?: run {
            val f = GlAssets.copyAssetToCache(ctx, "sfx/$name.ogg") ?: return
            val sid = pool.load(f.absolutePath, 1)
            loaded[name] = sid
            // will play on load-complete (first trigger of a sound)
            pool.setOnLoadCompleteListener { p, sampleId, status ->
                if (status == 0) {
                    synchronized(ready) { ready.add(sampleId) }
                    if (sampleId == sid && gain > 0.01f) p.play(sampleId, gain, gain, 1, 0, 1f)
                }
            }
            return
        }
        if (gain > 0.01f) pool.play(id, gain, gain, 1, 0, 1f)
    }

    fun release() = pool.release()
}
