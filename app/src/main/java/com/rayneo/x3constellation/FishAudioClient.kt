package com.rayneo.x3constellation

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Fish Audio text-to-speech client (HTTP). Blocking; call off the main thread.
 * Endpoint + payload per https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
 */
object FishAudioClient {
    private const val TAG = "X3Voice"
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"

    /** Returns MP3 bytes for [text] in voice [voiceId], or null on any failure. */
    fun synthesize(apiKey: String, model: String, voiceId: String, text: String): ByteArray? {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Fish: no API key configured")
            return null
        }
        // Try the configured model; if it's rejected, retry without a model header.
        return attempt(apiKey, model, voiceId, text)
            ?: if (model.isNotBlank()) attempt(apiKey, "", voiceId, text) else null
    }

    private fun attempt(apiKey: String, model: String, voiceId: String, text: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                if (model.isNotBlank()) setRequestProperty("model", model)
            }
            val body = JSONObject().apply {
                put("text", text)
                put("format", "mp3")
                if (voiceId.isNotBlank()) put("reference_id", voiceId)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w(TAG, "Fish TTS HTTP $code (model='$model'): ${err?.take(200)}")
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            Log.i(TAG, "Fish TTS ok: ${bytes.size} bytes (model='$model')")
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "Fish TTS error (model='$model'): ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
