package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Deflector shield: translucent ellipsoid around the ship. On particle impacts
 * (Jupiter radiation belts, solar flares) a Fresnel hex grid flares near the
 * impact direction, driven by uImpact which decays each frame.
 */
class ShieldShader {
    private val vsrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec2 aUV;
        varying vec3 vNormal;
        varying vec3 vWorldPos;
        varying vec2 vUV;
        void main() {
            vNormal = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            vWorldPos = (uModel * vec4(aPos, 1.0)).xyz;
            vUV = aUV;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vNormal;
        varying vec3 vWorldPos;
        varying vec2 vUV;
        uniform vec3 uCamPos;
        uniform vec3 uImpactDir;       // world-space direction of the hit
        uniform float uImpact;         // 0..1, decaying
        uniform float uTime;
        // hex grid distance in UV space
        float hexDist(vec2 p) {
            p = abs(p);
            return max(dot(p, normalize(vec2(1.0, 1.732))), p.x);
        }
        void main() {
            vec3 N = normalize(vNormal);
            vec3 V = normalize(uCamPos - vWorldPos);
            float fres = pow(1.0 - abs(dot(N, V)), 2.5);
            vec2 g = vUV * vec2(42.0, 24.0);
            vec2 cell = fract(g) - 0.5;
            float hex = smoothstep(0.42, 0.5, hexDist(cell));
            float local = pow(max(dot(N, normalize(uImpactDir)), 0.0), 9.0);
            float flare = uImpact * local;
            float pulse = 0.5 + 0.5 * sin(uTime * 9.0 + g.x);
            vec3 col = vec3(0.35, 0.75, 1.0) * (fres * 0.10 + flare * (0.5 + hex * 1.6) * pulse);
            float alpha = clamp(fres * 0.06 + flare * (0.25 + hex * 0.75), 0.0, 0.9);
            gl_FragColor = vec4(col, alpha);
        }
    """

    private var prog = 0
    private var aPos = 0; private var aNormal = 0; private var aUV = 0
    private var uMVP = 0; private var uModel = 0; private var uCamPos = 0
    private var uImpactDir = 0; private var uImpact = 0; private var uTime = 0

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        uImpactDir = GLES20.glGetUniformLocation(prog, "uImpactDir")
        uImpact = GLES20.glGetUniformLocation(prog, "uImpact")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
    }

    fun draw(sphere: Sphere, mvp: FloatArray, model: FloatArray, camPos: FloatArray,
             impactDir: FloatArray, impact: Float, time: Float) {
        if (impact < 0.01f) return
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES20.glUniform3fv(uImpactDir, 1, impactDir, 0)
        GLES20.glUniform1f(uImpact, impact)
        GLES20.glUniform1f(uTime, time)
        sphere.vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        sphere.vertices.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aNormal)
        sphere.vertices.position(6)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indices)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aUV)
    }
}
