package com.rayneo.x3constellation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class SpaceAudioEngine {
    @Volatile private var running = false
    @Volatile private var thrust = 0f
    @Volatile private var reversePulse = 0f
    @Volatile private var tapPulse = 0f
    @Volatile private var ambienceMode = 0
    private var audioThread: Thread? = null

    /** 0=bridge, 1=chase, 2=engineering, 3=meditation (see renderer VIEW_* constants). */
    fun setAmbience(mode: Int) { ambienceMode = mode }

    fun start() {
        if (running) return
        running = true
        audioThread = thread(name = "x3-space-audio") { renderLoop() }
    }

    fun stop() {
        running = false
        audioThread?.join(350)
        audioThread = null
    }

    fun warp() {
        thrust = 1f
    }

    fun reverse() {
        reversePulse = 1f
    }

    fun tap() {
        tapPulse = 1f
    }

    private fun renderLoop() {
        val sampleRate = 44_100
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = ShortArray(1024)
        var phase = 0.0
        var lfo = 0.0
        var padA = 0.0
        var padB = 0.0
        var subPhase = 0.0
        var medS = 0.0   // smoothed meditation gain
        var engS = 0.0   // smoothed engineering gain
        track.play()
        while (running) {
            val medTarget = if (ambienceMode == 3) 1.0 else 0.0
            val engTarget = if (ambienceMode == 2) 1.0 else 0.0
            for (i in buffer.indices step 2) {
                thrust *= 0.9992f
                reversePulse *= 0.9984f
                tapPulse *= 0.992f
                medS += (medTarget - medS) * 0.0006
                engS += (engTarget - engS) * 0.0006
                phase += 2.0 * PI * (42.0 + thrust * 55.0 - reversePulse * 18.0) / sampleRate
                lfo += 2.0 * PI * 0.11 / sampleRate
                padA += 2.0 * PI * 110.0 / sampleRate
                padB += 2.0 * PI * 164.81 / sampleRate
                subPhase += 2.0 * PI * 32.0 / sampleRate
                // No constant engine drone. Only transient warp whoosh + swipe blips,
                // and view ambience (engineering rumble, meditation pad).
                val whoosh = sin(phase * 0.7) * thrust * 0.10
                val alert = sin(phase * 9.0) * tapPulse * 0.12
                val reverse = sin(phase * 0.55) * reversePulse * 0.10
                val rumble = sin(subPhase) * 0.10 * engS                             // engineering core (only in that view)
                val pad = (sin(padA) + sin(padB) * 0.7) * 0.055 * medS * (0.6 + 0.4 * sin(lfo * 0.35)) // meditation pad
                val left = (whoosh + alert + reverse + rumble + pad).coerceIn(-0.5, 0.5)
                val right = (whoosh + alert - reverse + rumble + pad).coerceIn(-0.5, 0.5)
                buffer[i] = (left * Short.MAX_VALUE).toInt().toShort()
                buffer[i + 1] = (right * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(buffer, 0, buffer.size)
        }
        track.stop()
        track.release()
    }
}
