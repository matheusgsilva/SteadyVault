package com.steadyvault.camera.capture.recorder

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log

/**
 * Movimento global (panorâmica) entre dois frames consecutivos, estimado 100% na GPU, sem readback
 * no caminho crítico: o resultado fica numa textura 1x1 que o filtro temporal do ingest lê para
 * alinhar o histórico antes de misturar (sem isso, durante uma panorâmica o filtro temporal
 * desliga em todos os pixels e o ruído em letras/bordas fica inteiro).
 *
 * Passos por frame (tudo em texturas pequenas, 1/[DOWNSCALE] da saída):
 *  1. luma reduzida do frame atual (16 amostras por pixel pequeno);
 *  2. SAD de cada deslocamento candidato (-RAD..RAD nos dois eixos), dividido em faixas
 *     horizontais para dar paralelismo (cada fragmento soma ~4 mil pixels);
 *  3. um fragmento escolhe o mínimo, refina com parábola (sub-pixel) e escreve dx,dy em 16 bits.
 *
 * Convenção: o deslocamento d (em pixels pequenos) significa atual(p) ~ anterior(p - d); o
 * histórico do pixel p é lido em p - d. Se a superfície de custo for plana (parede lisa) ou o
 * mínimo não for claramente melhor que "parado", o resultado é 0.
 *
 * O mesmo texto dos shaders é usado no teste headless de tools/motion_test (run_gmc.py).
 */
internal class GlobalMotionEstimator(
    private val outWidth: Int,
    private val outHeight: Int
) {
    var available = false
        private set

    val motionTexture: Int get() = motion?.texture ?: 0
    private val smallWidth = (outWidth / DOWNSCALE).coerceAtLeast(MIN_SMALL)
    private val smallHeight = (outHeight / DOWNSCALE).coerceAtLeast(MIN_SMALL)

    private class Target(val texture: Int, val framebuffer: Int)

    private val small = arrayOfNulls<Target>(2)
    private var sad: Target? = null
    private var motion: Target? = null
    private var current = 0
    private var downProgram = 0
    private var sadProgram = 0
    private var argminProgram = 0

    private val candidates = 2 * RADIUS + 1
    private val bandHeight = (smallHeight - 2 * RADIUS) / BANDS
    private val columns = (smallWidth - 2 * RADIUS) / 2
    private val rows = bandHeight / 2

    /** Cria os recursos. Retorna true se a GPU suporta o caminho; nunca lança. */
    fun initialize(): Boolean {
        available = try {
            if (bandHeight < 4 || columns < 8) error("saída pequena demais para movimento global")
            small[0] = target(smallWidth, smallHeight)
            small[1] = target(smallWidth, smallHeight)
            sad = target(candidates, candidates * BANDS)
            motion = target(1, 1)
            val defines = "#define SW $smallWidth\n#define SH $smallHeight\n#define RAD $RADIUS\n" +
                "#define BANDS $BANDS\n#define BAND_H $bandHeight\n#define COLS $columns\n" +
                "#define ROWS $rows\n#define CW $candidates\n#define CH ${candidates * BANDS}\n"
            downProgram = program(DOWN_FRAGMENT)
            sadProgram = program(defines + SAD_FRAGMENT)
            argminProgram = program(defines + ARGMIN_FRAGMENT)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "movimento global indisponível: ${t.message}")
            release()
            false
        }
        return available
    }

    /**
     * Estima o movimento do frame atual (textura OES) contra o anterior. [hasPrevious] falso
     * (primeiro frame / depois de reinício) apenas guarda a luma e deixa o resultado em zero.
     * Muda framebuffer, viewport e programa ativos: quem chama precisa religá-los depois.
     */
    fun estimate(
        externalTexture: Int,
        textureMatrix: FloatArray,
        rotationDegrees: Int,
        hasPrevious: Boolean,
        bindQuad: (Int) -> Unit
    ) {
        if (!available) return
        val cur = requireNotNull(small[current])
        val prev = requireNotNull(small[1 - current])

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cur.framebuffer)
        GLES20.glViewport(0, 0, smallWidth, smallHeight)
        GLES20.glUseProgram(downProgram)
        bindQuad(downProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(downProgram, "uTextureMatrix"), 1, false, textureMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(downProgram, "uRotationDegrees"), rotationDegrees.toFloat())
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(downProgram, "uStep"),
            (DOWNSCALE / 4f) / outWidth,
            (DOWNSCALE / 4f) / outHeight
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(downProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val motionTarget = requireNotNull(motion)
        if (hasPrevious) {
            val sadTarget = requireNotNull(sad)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sadTarget.framebuffer)
            GLES20.glViewport(0, 0, candidates, candidates * BANDS)
            GLES20.glUseProgram(sadProgram)
            bindQuad(sadProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cur.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(sadProgram, "sCur"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(sadProgram, "sPrev"), 1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionTarget.framebuffer)
            GLES20.glViewport(0, 0, 1, 1)
            GLES20.glUseProgram(argminProgram)
            bindQuad(argminProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sadTarget.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(argminProgram, "sSad"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionTarget.framebuffer)
            GLES20.glViewport(0, 0, 1, 1)
            // 32767/65535 nos dois eixos = deslocamento 0 (ver a codificação no ARGMIN).
            GLES20.glClearColor(127f / 255f, 255f / 255f, 127f / 255f, 255f / 255f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        current = 1 - current
    }

    /** Leitura de 1 pixel para telemetria (para a GPU; chamar só ~1x por segundo). Em pixels de saída. */
    fun readShift(out: FloatArray) {
        val target = motion ?: return
        val buffer = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val r = buffer.get(0).toInt() and 0xFF
        val g = buffer.get(1).toInt() and 0xFF
        val b = buffer.get(2).toInt() and 0xFF
        val a = buffer.get(3).toInt() and 0xFF
        out[0] = ((r * 256 + g) / 65535f * 2f * RADIUS - RADIUS) * DOWNSCALE
        out[1] = ((b * 256 + a) / 65535f * 2f * RADIUS - RADIUS) * DOWNSCALE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun release() {
        available = false
        for (t in listOf(small[0], small[1], sad, motion)) {
            if (t == null) continue
            runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(t.framebuffer), 0) }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(t.texture), 0) }
        }
        small[0] = null; small[1] = null; sad = null; motion = null
        for (p in intArrayOf(downProgram, sadProgram, argminProgram)) {
            if (p != 0) runCatching { GLES20.glDeleteProgram(p) }
        }
        downProgram = 0; sadProgram = 0; argminProgram = 0
    }

    private fun target(w: Int, h: Int): Target {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // NEAREST: as amostras caem sempre no centro do texel; nada de interpolação.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer ${w}x$h incompleto" }
        return Target(texture, framebuffer)
    }

    private fun program(fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(linked[0] == GLES20.GL_TRUE) { "link movimento global: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compilação movimento global: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val TAG = "SteadyVaultCfr"

        /** Fator de redução da luma usada na estimativa (saída 2160x3840 -> 270x480). */
        const val DOWNSCALE = 8
        private const val MIN_SMALL = 32

        /** Deslocamento máximo por frame, em pixels pequenos (8 x 8 = 64 px de saída). */
        const val RADIUS = 8
        private const val BANDS = 8

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aTexCoord.xy;
            }
        """

        const val DOWN_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTextureMatrix;
            uniform float uRotationDegrees;
            uniform vec2 uStep;
            vec2 rotateUv(vec2 uv) {
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);
                return uv;
            }
            float lumaAt(vec2 uv) {
                vec3 c = texture2D(sTexture, (uTextureMatrix * vec4(rotateUv(uv), 0.0, 1.0)).xy).rgb;
                return dot(c, vec3(0.299, 0.587, 0.114));
            }
            void main() {
                float s = 0.0;
                for (int j = 0; j < 4; j++) {
                    for (int i = 0; i < 4; i++) {
                        s += lumaAt(vUv + (vec2(float(i), float(j)) - 1.5) * uStep);
                    }
                }
                gl_FragColor = vec4(vec3(s / 16.0), 1.0);
            }
        """

        const val SAD_FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sCur;
            uniform sampler2D sPrev;
            void main() {
                float ix = floor(gl_FragCoord.x);
                float iy = floor(gl_FragCoord.y);
                float dx = ix - float(RAD);
                float candY = floor(iy / float(BANDS));
                float band = iy - candY * float(BANDS);
                float dy = candY - float(RAD);
                float y0 = float(RAD) + band * float(BAND_H);
                vec2 size = vec2(float(SW), float(SH));
                float acc = 0.0;
                for (int r = 0; r < ROWS; r++) {
                    float y = y0 + float(r) * 2.0;
                    for (int c = 0; c < COLS; c++) {
                        float x = float(RAD) + float(c) * 2.0;
                        float a = texture2D(sCur, vec2(x + 0.5, y + 0.5) / size).r;
                        float b = texture2D(sPrev, vec2(x - dx + 0.5, y - dy + 0.5) / size).r;
                        acc += abs(a - b);
                    }
                }
                float mean = acc / float(ROWS * COLS);
                float v = floor(clamp(mean * 2.0, 0.0, 1.0) * 65535.0 + 0.5);
                float hi = floor(v / 256.0);
                float lo = v - hi * 256.0;
                gl_FragColor = vec4(hi / 255.0, lo / 255.0, 0.0, 1.0);
            }
        """

        const val ARGMIN_FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D sSad;
            float costAt(int i, int j) {
                float s = 0.0;
                for (int b = 0; b < BANDS; b++) {
                    vec4 t = texture2D(sSad, vec2((float(i) + 0.5) / float(CW), (float(j * BANDS + b) + 0.5) / float(CH)));
                    s += (t.r * 255.0 * 256.0 + t.g * 255.0) / 65535.0;
                }
                return s / float(BANDS) * 0.5;
            }
            vec2 encode(float d) {
                float v = floor(clamp((d + float(RAD)) / (2.0 * float(RAD)), 0.0, 1.0) * 65535.0 + 0.5);
                float hi = floor(v / 256.0);
                return vec2(hi / 255.0, (v - hi * 256.0) / 255.0);
            }
            void main() {
                float best = 1.0e9;
                int bi = RAD;
                int bj = RAD;
                float sum = 0.0;
                for (int j = 0; j < CW; j++) {
                    for (int i = 0; i < CW; i++) {
                        float c = costAt(i, j);
                        sum += c;
                        if (c < best) { best = c; bi = i; bj = j; }
                    }
                }
                float mean = sum / float(CW * CW);
                float zero = costAt(RAD, RAD);
                float dx = 0.0;
                float dy = 0.0;
                // Só confia se o mínimo é claramente melhor que "parado" e que a média (superfície
                // plana = parede lisa, deslocamento ambíguo).
                if (best < zero * 0.97 && best < mean * 0.85) {
                    dx = float(bi - RAD);
                    dy = float(bj - RAD);
                    if (bi > 0 && bi < CW - 1) {
                        float l = costAt(bi - 1, bj);
                        float r = costAt(bi + 1, bj);
                        float den = l - 2.0 * best + r;
                        if (den > 1.0e-6) dx += clamp(0.5 * (l - r) / den, -0.5, 0.5);
                    }
                    if (bj > 0 && bj < CW - 1) {
                        float u = costAt(bi, bj - 1);
                        float d = costAt(bi, bj + 1);
                        float den = u - 2.0 * best + d;
                        if (den > 1.0e-6) dy += clamp(0.5 * (u - d) / den, -0.5, 0.5);
                    }
                }
                vec2 ex = encode(dx);
                vec2 ey = encode(dy);
                gl_FragColor = vec4(ex, ey);
            }
        """
    }
}
