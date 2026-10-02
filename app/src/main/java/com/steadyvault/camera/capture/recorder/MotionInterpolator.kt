package com.steadyvault.camera.capture.recorder

import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * Interpolação com compensação de movimento, 100% na GPU, para preencher frames que a
 * câmera/HAL perdeu. Os shaders ficam em [MotionShaders]; este arquivo só cuida dos
 * recursos GL e da ordem dos passes.
 *
 * Tudo roda na thread GL da ponte (contexto já corrente). Se a GPU não suporta texturas
 * half-float renderizáveis, ou qualquer passo falhar, [available] vira false e a ponte
 * volta sozinha ao crossfade.
 *
 * Os dois frames reais (anterior e atual) chegam como texturas 2D já no espaço de saída
 * (a ponte copia cada frame da câmera para um anel de texturas assim que ele chega). Nada
 * aqui roda por frame: só quando há uma lacuna a preencher.
 *
 * Fluxo, uma vez por lacuna:
 *  1. [prepare]  luma 1/8, 1/4, 1/16 e 1/32 dos dois frames.
 *  2. [estimate] block matching hierárquico (1/32 -> 1/16 -> 1/8, 4 passes) e refino em 1/4
 *     (2 passes, passo de 1 e de 0,5 texel) para acompanhar o traço fino de texto.
 *  3. [warp] uma vez por saída: frame no instante alpha, direto no framebuffer atual.
 */
internal class MotionInterpolator(
    private val width: Int,
    private val height: Int,
    private val lookProfile: Int = VideoLook.PROFILE_NATURAL
) {
    var available = false
        private set

    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    private val sizes = Array(LEVELS) { level ->
        val divisor = 8 shl level
        intArrayOf((width / divisor).coerceAtLeast(2), (height / divisor).coerceAtLeast(2))
    }
    // Nível de refino em 1/4: o box 8x8 do 1/8 apaga o traço fino de texto, e o fluxo de bloco
    // grosso faz letras saírem deslocadas ou com sombra. Fica fora do vetor [sizes].
    private val quarterSize = intArrayOf((width / 4).coerceAtLeast(2), (height / 4).coerceAtLeast(2))

    // Lumas (1/8, 1/16, 1/32, depois 1/4) do frame anterior e do atual.
    private val lumaPrevious = ArrayList<Target>()
    private val lumaCurrent = ArrayList<Target>()
    private val flowCoarse = ArrayList<Target>() // 1/32, 1/16
    private var flowFine: Target? = null          // 1/8 (resultado de L0)
    private var flowRefined: Target? = null       // 1/8 (resultado do bloco grosso)
    private var flowQuarterA: Target? = null      // 1/4 (passo de 1 texel)
    private var flowQuarterB: Target? = null      // 1/4 (resultado final, meio texel)
    private var downEighth = 0
    private var downQuarter = 0
    private var pyramid = 0
    private val estimate = IntArray(6)
    private var warp = 0

    /** Cria os recursos. Retorna true se a GPU suporta o caminho; nunca lança. */
    fun initialize(): Boolean {
        available = try {
            setUp()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "interpolação com movimento indisponível: ${t.message}")
            release()
            false
        }
        return available
    }

    private fun setUp() {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        check(extensions.contains("GL_OES_texture_half_float")) { "sem OES_texture_half_float" }
        check(
            extensions.contains("GL_EXT_color_buffer_half_float") ||
                extensions.contains("GL_EXT_color_buffer_float")
        ) { "sem render em half-float" }
        val flowFilter = if (extensions.contains("GL_OES_texture_half_float_linear")) {
            GLES20.GL_LINEAR
        } else {
            GLES20.GL_NEAREST
        }

        for (set in arrayOf(lumaPrevious, lumaCurrent)) {
            for (level in 0 until LEVELS) {
                set += createTarget(sizes[level][0], sizes[level][1], GLES20.GL_UNSIGNED_BYTE, GLES20.GL_LINEAR)
            }
            set += createTarget(quarterSize[0], quarterSize[1], GLES20.GL_UNSIGNED_BYTE, GLES20.GL_LINEAR)
        }
        flowCoarse += createTarget(sizes[2][0], sizes[2][1], HALF_FLOAT_OES, flowFilter)
        flowCoarse += createTarget(sizes[1][0], sizes[1][1], HALF_FLOAT_OES, flowFilter)
        flowFine = createTarget(sizes[0][0], sizes[0][1], HALF_FLOAT_OES, flowFilter)
        flowRefined = createTarget(sizes[0][0], sizes[0][1], HALF_FLOAT_OES, flowFilter)
        flowQuarterA = createTarget(quarterSize[0], quarterSize[1], HALF_FLOAT_OES, flowFilter)
        flowQuarterB = createTarget(quarterSize[0], quarterSize[1], HALF_FLOAT_OES, flowFilter)

        downEighth = program(MotionShaders.DOWN_FRAGMENT, "")
        downQuarter = program(MotionShaders.DOWN_FRAGMENT, "#define DOWN_QUARTER\n")
        pyramid = program(MotionShaders.PYRAMID_FRAGMENT, "")
        estimate[0] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(3, 1.0f, 2, 0.02f))
        estimate[1] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(1, 1.0f, 2, 0.02f))
        estimate[2] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(1, 1.0f, 2, 0.02f))
        estimate[3] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(1, 0.5f, 2, 0.001f))
        estimate[4] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(1, 1.0f, 2, 0.001f))
        estimate[5] = program(MotionShaders.ESTIMATE_FRAGMENT, searchDefines(1, 0.5f, 2, 0.001f))
        warp = program(MotionShaders.WARP_FRAGMENT, "")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "erro GL na criação" }
    }

    /**
     * Gera a pirâmide de luma dos dois frames reais (texturas 2D no espaço de saída). Chame
     * uma vez por lacuna, antes de [estimate].
     */
    fun prepare(
        previousTexture: Int,
        currentTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer
    ) {
        GLES20.glDisable(GLES20.GL_BLEND)
        lumaOf(previousTexture, lumaPrevious, vertices, texCoords)
        lumaOf(currentTexture, lumaCurrent, vertices, texCoords)
    }

    private fun lumaOf(texture: Int, set: ArrayList<Target>, vertices: FloatBuffer, texCoords: FloatBuffer) {
        draw(downEighth, set[0], vertices, texCoords) { p ->
            GLES20.glUniform2f(loc(p, "uTexel"), 1f / width, 1f / height)
            bind2d(0, texture, loc(p, "sSrc"))
        }
        draw(downQuarter, set[QUARTER], vertices, texCoords) { p ->
            GLES20.glUniform2f(loc(p, "uTexel"), 1f / width, 1f / height)
            bind2d(0, texture, loc(p, "sSrc"))
        }
        for (level in 1 until LEVELS) {
            val source = set[level - 1]
            draw(pyramid, set[level], vertices, texCoords) { p ->
                GLES20.glUniform2f(loc(p, "uSrcTexel"), 1f / source.width, 1f / source.height)
                bind2d(0, source.texture, loc(p, "sSrc"))
            }
        }
    }

    /** Fluxo entre os dois frames de [prepare]. Chame uma vez por lacuna, antes dos [warp]. */
    fun estimate(vertices: FloatBuffer, texCoords: FloatBuffer) {
        GLES20.glDisable(GLES20.GL_BLEND)
        // 3) block matching grosso -> fino.
        search(estimate[0], 2, flowCoarse[0], null, vertices, texCoords)
        search(estimate[1], 1, flowCoarse[1], flowCoarse[0], vertices, texCoords)
        search(estimate[2], 0, requireNotNull(flowFine), flowCoarse[1], vertices, texCoords)
        search(estimate[3], 0, requireNotNull(flowRefined), flowFine, vertices, texCoords)
        // 4) refino em 1/4 (luma com o traço fino): passo de 1 texel e depois de meio texel.
        search(estimate[4], QUARTER, requireNotNull(flowQuarterA), flowRefined, vertices, texCoords)
        search(estimate[5], QUARTER, requireNotNull(flowQuarterB), flowQuarterA, vertices, texCoords)
    }

    private fun search(
        program: Int,
        level: Int,
        target: Target,
        prior: Target?,
        vertices: FloatBuffer,
        texCoords: FloatBuffer
    ) {
        draw(program, target, vertices, texCoords) { p ->
            GLES20.glUniform2f(loc(p, "uTexel"), 1f / lumaPrevious[level].width, 1f / lumaPrevious[level].height)
            GLES20.glUniform1f(loc(p, "uHasPrior"), if (prior != null) 1f else 0f)
            bind2d(0, lumaPrevious[level].texture, loc(p, "sP"))
            bind2d(1, lumaCurrent[level].texture, loc(p, "sC"))
            // Sem fluxo anterior, liga a própria luma só para o sampler ficar válido.
            bind2d(2, prior?.texture ?: lumaPrevious[level].texture, loc(p, "sFlow"))
        }
    }

    /** Desenha no framebuffer 0 (encoder) o frame no instante [alpha] (0 = anterior, 1 = atual). */
    fun warp(
        alpha: Float,
        previousTexture: Int,
        currentTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        cropScale: Float = 1f
    ) {
        draw(warp, null, vertices, texCoords) { p ->
            GLES20.glUniform1f(loc(p, "uCropScale"), cropScale)
            bind2d(0, previousTexture, loc(p, "sP"))
            bind2d(1, requireNotNull(flowQuarterB).texture, loc(p, "sFlow"))
            bind2d(2, currentTexture, loc(p, "sC"))
            GLES20.glUniform1f(loc(p, "uAlpha"), alpha.coerceIn(0.001f, 0.999f))
            GLES20.glUniform1f(loc(p, "uCostLow"), COST_LOW)
            GLES20.glUniform1f(loc(p, "uCostHigh"), COST_HIGH)
            GLES20.glUniform1f(loc(p, "uDiffLow"), DIFF_LOW)
            GLES20.glUniform1f(loc(p, "uDiffHigh"), DIFF_HIGH)
            GLES20.glUniform1f(loc(p, "uSpreadLow"), SPREAD_LOW)
            GLES20.glUniform1f(loc(p, "uSpreadHigh"), SPREAD_HIGH)
            GLES20.glUniform2f(loc(p, "uFlowTexel"), 1f / quarterSize[0], 1f / quarterSize[1])
            GLES20.glUniform1f(loc(p, "uLook"), if (VideoLook.ENABLED) 1f else 0f)
            GLES20.glUniform2f(loc(p, "uLookTexel"), VideoLook.RADIUS_PX / width, VideoLook.RADIUS_PX / height)
        }
    }

    /** true se nenhum erro GL ficou pendente; quem chama cai para o crossfade quando é false. */
    fun healthy(): Boolean {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "erro GL 0x${Integer.toHexString(error)} na interpolação com movimento")
            return false
        }
        return true
    }

    fun release() {
        available = false
        val targets = lumaPrevious + lumaCurrent + flowCoarse +
            listOfNotNull(flowFine, flowRefined, flowQuarterA, flowQuarterB)
        for (target in targets) {
            runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0) }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0) }
        }
        lumaPrevious.clear()
        lumaCurrent.clear()
        flowCoarse.clear()
        flowFine = null
        flowRefined = null
        flowQuarterA = null
        flowQuarterB = null
        for (p in intArrayOf(downEighth, downQuarter, pyramid, warp) + estimate) {
            if (p != 0) runCatching { GLES20.glDeleteProgram(p) }
        }
        downEighth = 0; downQuarter = 0; pyramid = 0; warp = 0
        estimate.fill(0)
    }

    private inline fun draw(
        program: Int,
        target: Target?,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        setup: (Int) -> Unit
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target?.framebuffer ?: 0)
        GLES20.glViewport(0, 0, target?.width ?: width, target?.height ?: height)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        setup(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loc(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)

    private fun bind2d(unit: Int, texture: Int, uniform: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uniform, unit)
    }

    private fun searchDefines(radius: Int, step: Float, window: Int, lambda: Float): String {
        val norm = ((2 * window + 1) * (2 * window + 1)).toFloat()
        return "#define R $radius\n#define STEP ${fmt(step)}\n#define WIN $window\n" +
            "#define NORM ${fmt(norm)}\n#define LAMBDA ${fmt(lambda)}\n"
    }

    // Locale-independente (ponto decimal), sem depender de String.format.
    private fun fmt(value: Float): String = java.lang.Float.toString(value)

    private fun createTarget(w: Int, h: Int, type: Int, filter: Int): Target {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texture = ids[0]
        check(texture != 0) { "textura de movimento não criada" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, type, null)
        GLES20.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            error("framebuffer ${w}x$h tipo 0x${Integer.toHexString(type)} incompleto")
        }
        return Target(texture, framebuffer, w, h)
    }

    private fun program(fragment: String, defines: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, MotionShaders.VERTEX)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, defines + VideoLook.insert(fragment, lookProfile))
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(linked[0] == GLES20.GL_TRUE) { "link movimento: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compilação movimento: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val TAG = "SteadyVaultCfr"
        const val LEVELS = 3

        /** Índice do luma 1/4 dentro de cada conjunto de lumas (depois dos [LEVELS] níveis). */
        const val QUARTER = LEVELS
        const val HALF_FLOAT_OES = 0x8D61

        // Mesmos valores validados no teste headless (tools/motion_test).
        // Mais rígidos que antes: texto fino com fluxo duvidoso vira o frame mais próximo,
        // nítido, em vez de mistura com sombra.
        const val COST_LOW = 0.025f
        const val COST_HIGH = 0.08f
        const val DIFF_LOW = 0.04f
        const val DIFF_HIGH = 0.12f

        // Discordância entre vetores vizinhos (em texels 1/4) a partir da qual o fluxo não é confiável.
        const val SPREAD_LOW = 2.5f
        const val SPREAD_HIGH = 6.0f
    }
}
