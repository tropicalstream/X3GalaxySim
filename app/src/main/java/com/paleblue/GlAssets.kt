package com.paleblue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Shader/texture/asset utilities. All GL calls must run on the GL thread. */
object GlAssets {
    private const val TAG = "GlAssets"

    fun compileProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(p))
        }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) + "\n--- src ---\n" + src)
        }
        return s
    }

    /** Loads assets/<path> as a texture; returns 0 on failure (caller falls back to procedural). */
    fun loadTexture(ctx: Context, path: String): Int {
        val bmp: Bitmap = try {
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return 0
        } catch (e: Exception) { return 0 }
        val tex = uploadBitmap(bmp, mipmap = true)
        bmp.recycle()
        return tex
    }

    fun uploadBitmap(bmp: Bitmap, mipmap: Boolean = false): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (mipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }

    fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().apply { put(data); position(0) }

    /**
     * GOTCHA: AssetManager.openFd() throws on AAPT-compressed assets, and MediaPlayer
     * cannot stream from a compressed asset. Copy to cacheDir once and play from a real file.
     */
    fun copyAssetToCache(ctx: Context, assetPath: String): File? {
        return try {
            val out = File(ctx.cacheDir, assetPath.replace('/', '_'))
            if (assetPath.startsWith("voice/") || !out.exists() || out.length() == 0L) {
                ctx.assets.open(assetPath).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Missing audio asset: $assetPath")
            null
        }
    }
}
