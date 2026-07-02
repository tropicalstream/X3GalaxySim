package com.paleblue

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Parses the bundled HYG star database (assets/hygdata.csv — codeberg.org/astronexus/hyg)
 * into a point buffer: [dir.xyz, r, g, b, size] per star. Magnitude -> size,
 * B-V color index -> RGB. Falls back to a procedural field if the CSV is absent
 * (gotcha #9: the app must always build & run).
 */
class HygStars(ctx: Context) {
    val buffer: FloatBuffer
    val count: Int

    init {
        val data = ArrayList<Float>(9000 * 7)
        var n = 0
        try {
            BufferedReader(InputStreamReader(ctx.assets.open("hygdata.csv"))).use { br ->
                val header = br.readLine()?.split(",") ?: emptyList()
                val ix = header.indexOf("x"); val iy = header.indexOf("y"); val iz = header.indexOf("z")
                val imag = header.indexOf("mag"); val ici = header.indexOf("ci")
                if (ix < 0 || imag < 0) throw IllegalStateException("unexpected header")
                var line = br.readLine()
                while (line != null && n < 12000) {
                    val c = line.split(",")
                    line = br.readLine()
                    if (c.size <= maxOf(ix, iy, iz, imag, ici)) continue
                    val mag = c[imag].toFloatOrNull() ?: continue
                    if (mag > 6.5f) continue
                    val x = c[ix].toFloatOrNull() ?: continue
                    val y = c[iy].toFloatOrNull() ?: continue
                    val z = c[iz].toFloatOrNull() ?: continue
                    val len = sqrt(x * x + y * y + z * z)
                    if (len < 1e-6f) continue
                    val ci = c.getOrNull(ici)?.toFloatOrNull() ?: 0.5f
                    val rgb = bvToRgb(ci)
                    val bright = 10f.pow((1.2f - mag) * 0.18f).coerceIn(0.18f, 1.6f)
                    data.add(x / len); data.add(y / len); data.add(z / len)
                    data.add(rgb[0] * bright); data.add(rgb[1] * bright); data.add(rgb[2] * bright)
                    data.add((6.8f - mag).coerceIn(1.2f, 7f))
                    n++
                }
            }
        } catch (e: Exception) {
            // Procedural fallback starfield
            val rnd = Random(42)
            repeat(3500) {
                var x: Float; var y: Float; var z: Float; var l: Float
                do {
                    x = rnd.nextFloat() * 2 - 1; y = rnd.nextFloat() * 2 - 1; z = rnd.nextFloat() * 2 - 1
                    l = sqrt(x * x + y * y + z * z)
                } while (l > 1f || l < 1e-3f)
                val ci = rnd.nextFloat() * 1.6f - 0.2f
                val rgb = bvToRgb(ci)
                val b = 0.25f + rnd.nextFloat() * 0.75f
                data.add(x / l); data.add(y / l); data.add(z / l)
                data.add(rgb[0] * b); data.add(rgb[1] * b); data.add(rgb[2] * b)
                data.add(1.2f + rnd.nextFloat() * 3.2f)
                n++
            }
        }
        buffer = GlAssets.floatBuffer(data.toFloatArray())
        count = n
    }

    /** Approximate B-V color index -> linear RGB. */
    private fun bvToRgb(bvIn: Float): FloatArray {
        val bv = bvIn.coerceIn(-0.4f, 2.0f)
        val t = (bv + 0.4f) / 2.4f          // 0 = hot/blue, 1 = cool/red
        val r = (0.62f + 0.55f * t).coerceIn(0f, 1.15f)
        val g = (0.72f + 0.20f * t - 0.30f * t * t).coerceIn(0f, 1f)
        val b = (1.12f - 0.85f * t).coerceIn(0.15f, 1.15f)
        return floatArrayOf(r, g, b)
    }
}
