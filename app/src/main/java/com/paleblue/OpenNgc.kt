package com.paleblue

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Parses the bundled OpenNGC catalog (assets/openngc.csv, semicolon-separated —
 * github.com/mattiaverga/OpenNGC) into soft, large point sprites for nebulae,
 * clusters and the Milky Way's deep-sky texture. Silent no-op if absent.
 */
class OpenNgc(ctx: Context) {
    val buffer: FloatBuffer?
    val count: Int

    init {
        val data = ArrayList<Float>()
        var n = 0
        try {
            BufferedReader(InputStreamReader(ctx.assets.open("openngc.csv"))).use { br ->
                val header = br.readLine()?.split(";") ?: emptyList()
                val ira = header.indexOf("RA"); val idec = header.indexOf("Dec")
                val itype = header.indexOf("Type"); val imag = header.indexOf("V-Mag")
                var line = br.readLine()
                while (line != null && n < 400) {
                    val c = line.split(";")
                    line = br.readLine()
                    if (c.size <= maxOf(ira, idec, itype)) continue
                    val mag = c.getOrNull(imag)?.toFloatOrNull() ?: continue
                    if (mag > 9.5f) continue
                    val ra = parseRa(c[ira]) ?: continue
                    val dec = parseDec(c[idec]) ?: continue
                    val type = c[itype]
                    val dir = floatArrayOf(
                        (cos(dec) * cos(ra)).toFloat(),
                        sin(dec).toFloat(),
                        (cos(dec) * sin(ra)).toFloat())
                    val col = when {
                        type.contains("Neb") -> floatArrayOf(0.45f, 0.30f, 0.55f)   // nebulae: violet
                        type.contains("GCl") -> floatArrayOf(0.55f, 0.50f, 0.35f)   // globulars: gold
                        type.contains("OCl") -> floatArrayOf(0.35f, 0.45f, 0.60f)   // open clusters: blue
                        type == "G" -> floatArrayOf(0.40f, 0.40f, 0.45f)            // galaxies
                        else -> floatArrayOf(0.30f, 0.32f, 0.38f)
                    }
                    val fade = ((10f - mag) / 6f).coerceIn(0.15f, 1f) * 0.5f
                    data.add(dir[0]); data.add(dir[1]); data.add(dir[2])
                    data.add(col[0] * fade); data.add(col[1] * fade); data.add(col[2] * fade)
                    data.add(9f + (9.5f - mag))
                    n++
                }
            }
        } catch (e: Exception) { /* optional asset */ }
        buffer = if (n > 0) GlAssets.floatBuffer(data.toFloatArray()) else null
        count = n
    }

    private fun parseRa(s: String): Double? {
        val p = s.split(":")
        if (p.size < 2) return null
        val h = p[0].toDoubleOrNull() ?: return null
        val m = p[1].toDoubleOrNull() ?: 0.0
        val sec = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        return (h + m / 60 + sec / 3600) / 24.0 * 2.0 * Math.PI
    }

    private fun parseDec(s: String): Double? {
        val neg = s.startsWith("-")
        val p = s.removePrefix("-").removePrefix("+").split(":")
        if (p.isEmpty()) return null
        val d = p[0].toDoubleOrNull() ?: return null
        val m = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val sec = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val deg = (d + m / 60 + sec / 3600) * (if (neg) -1 else 1)
        return Math.toRadians(deg)
    }
}
