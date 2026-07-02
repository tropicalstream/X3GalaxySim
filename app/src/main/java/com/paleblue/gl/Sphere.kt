package com.paleblue.gl

import com.paleblue.GlAssets
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/** UV sphere: interleaved [pos.xyz, normal.xyz, uv.xy], indexed triangles. */
class Sphere(stacks: Int = 24, slices: Int = 48) {
    val vertices: FloatBuffer
    val indices: ShortBuffer
    val indexCount: Int
    val stride = 8 * 4

    init {
        val verts = FloatArray((stacks + 1) * (slices + 1) * 8)
        var vi = 0
        for (i in 0..stacks) {
            val phi = Math.PI * i / stacks               // 0..PI from north pole
            val y = cos(phi).toFloat()
            val r = sin(phi).toFloat()
            for (j in 0..slices) {
                val theta = 2.0 * Math.PI * j / slices
                val x = (r * cos(theta)).toFloat()
                val z = (r * sin(theta)).toFloat()
                verts[vi++] = x; verts[vi++] = y; verts[vi++] = z          // position (unit)
                verts[vi++] = x; verts[vi++] = y; verts[vi++] = z          // normal
                verts[vi++] = 1f - j.toFloat() / slices                    // u (equirectangular)
                verts[vi++] = i.toFloat() / stacks                         // v
            }
        }
        val idx = ShortArray(stacks * slices * 6)
        var ii = 0
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = (i * (slices + 1) + j).toShort()
            val b = ((i + 1) * (slices + 1) + j).toShort()
            idx[ii++] = a; idx[ii++] = b; idx[ii++] = (a + 1).toShort()
            idx[ii++] = b; idx[ii++] = (b + 1).toShort(); idx[ii++] = (a + 1).toShort()
        }
        vertices = GlAssets.floatBuffer(verts)
        indices = GlAssets.shortBuffer(idx)
        indexCount = idx.size
    }
}
