package com.paleblue.gl

import android.opengl.GLES20
import com.paleblue.GlAssets

/**
 * Blinn-Phong + Fresnel atmosphere rim, GLSL ES 1.00.
 * Sunlight is dimmed on the CPU with a real inverse-square factor (uSunlight).
 * Gas giants scroll their cloud UVs over time (uScroll).
 */
class PlanetShader {
    private val vsrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec2 aUV;
        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec2 vUV;
        void main() {
            vec4 wp = uModel * vec4(aPos, 1.0);
            vWorldPos = wp.xyz;
            vNormal = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            vUV = aUV;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """
    private val fsrc = """
        precision mediump float;
        varying vec3 vWorldPos;
        varying vec3 vNormal;
        varying vec2 vUV;
        uniform sampler2D uDiffuseMap;
        uniform float uHasTexture;     // 1 = sample map, 0 = procedural base color
        uniform vec3  uBaseColor;
        uniform vec3  uSunPos;
        uniform vec3  uCamPos;
        uniform float uSunlight;       // inverse-square factor, CPU computed
        uniform vec3  uAtmoColor;
        uniform float uAtmoStrength;
        uniform float uEmissive;       // 1 for the Sun / stars (self lit)
        uniform float uScroll;         // gas giant cloud scroll (u offset)
        uniform float uMinAmbient;     // floor so dim outer planets stay readable
        void main() {
            vec3 N = normalize(vNormal);
            vec3 L = normalize(uSunPos - vWorldPos);
            vec3 V = normalize(uCamPos - vWorldPos);
            vec2 uv = vec2(fract(vUV.x + uScroll), vUV.y);
            vec3 albedo = mix(uBaseColor, texture2D(uDiffuseMap, uv).rgb, uHasTexture);
            // procedural banding when no texture, so fallback planets aren't flat
            if (uHasTexture < 0.5) {
                float band = 0.5 + 0.5 * sin(vUV.y * 28.0 + uScroll * 40.0);
                albedo *= 0.85 + 0.15 * band;
            }
            float ndl = max(dot(N, L), 0.0);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 24.0) * 0.25;
            float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);
            vec3 lit = albedo * (uMinAmbient + ndl * uSunlight)
                     + vec3(spec) * uSunlight * ndl
                     + uAtmoColor * fresnel * uAtmoStrength * (0.15 + 0.85 * ndl * uSunlight);
            vec3 emis = albedo * (1.2 + 0.3 * fresnel);
            gl_FragColor = vec4(mix(lit, emis, uEmissive), 1.0);
        }
    """

    private var prog = 0
    private var aPos = 0; private var aNormal = 0; private var aUV = 0
    private var uMVP = 0; private var uModel = 0; private var uDiffuse = 0; private var uHasTex = 0
    private var uBase = 0; private var uSunPos = 0; private var uCamPos = 0; private var uSunlight = 0
    private var uAtmoColor = 0; private var uAtmoStrength = 0; private var uEmissive = 0
    private var uScroll = 0; private var uMinAmbient = 0

    fun init() {
        prog = GlAssets.compileProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uDiffuse = GLES20.glGetUniformLocation(prog, "uDiffuseMap")
        uHasTex = GLES20.glGetUniformLocation(prog, "uHasTexture")
        uBase = GLES20.glGetUniformLocation(prog, "uBaseColor")
        uSunPos = GLES20.glGetUniformLocation(prog, "uSunPos")
        uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        uSunlight = GLES20.glGetUniformLocation(prog, "uSunlight")
        uAtmoColor = GLES20.glGetUniformLocation(prog, "uAtmoColor")
        uAtmoStrength = GLES20.glGetUniformLocation(prog, "uAtmoStrength")
        uEmissive = GLES20.glGetUniformLocation(prog, "uEmissive")
        uScroll = GLES20.glGetUniformLocation(prog, "uScroll")
        uMinAmbient = GLES20.glGetUniformLocation(prog, "uMinAmbient")
    }

    fun draw(
        sphere: Sphere, mvp: FloatArray, model: FloatArray,
        texture: Int, baseColor: FloatArray,
        sunPos: FloatArray, camPos: FloatArray, sunlight: Float,
        atmoColor: FloatArray, atmoStrength: Float,
        emissive: Float, scroll: Float
    ) {
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (texture != 0) texture else 0)
        GLES20.glUniform1i(uDiffuse, 0)
        GLES20.glUniform1f(uHasTex, if (texture != 0) 1f else 0f)
        GLES20.glUniform3fv(uBase, 1, baseColor, 0)
        GLES20.glUniform3fv(uSunPos, 1, sunPos, 0)
        GLES20.glUniform3fv(uCamPos, 1, camPos, 0)
        GLES20.glUniform1f(uSunlight, sunlight)
        GLES20.glUniform3fv(uAtmoColor, 1, atmoColor, 0)
        GLES20.glUniform1f(uAtmoStrength, atmoStrength)
        GLES20.glUniform1f(uEmissive, emissive)
        GLES20.glUniform1f(uScroll, scroll)
        GLES20.glUniform1f(uMinAmbient, 0.045f)   // comfort: floor scene brightness

        sphere.vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        sphere.vertices.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aNormal)
        sphere.vertices.position(6)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, sphere.stride, sphere.vertices)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indices)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aUV)
    }
}
