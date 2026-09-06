from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'bloco nao encontrado em {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# Dependencia oficial OpenCV Android via Maven Central.
build = 'app/build.gradle.kts'
replace_once(
    build,
    '    implementation("org.videolan.android:libvlc-all:3.7.4")\n',
    '    implementation("org.videolan.android:libvlc-all:3.7.4")\n    implementation("org.opencv:opencv:4.10.0")\n'
)

# Estimador de fluxo optico denso. Executa somente no pos-processamento.
motion_file = Path('app/src/main/java/com/steadyvault/camera/processing/motion/OpenCvMotionEstimator.kt')
motion_file.parent.mkdir(parents=True, exist_ok=True)
motion_file.write_text(r'''package com.steadyvault.camera.processing.motion

import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Fluxo óptico local entre dois quadros vizinhos.
 *
 * A entrada vem de um downsample feito pela GPU. Farneback calcula movimento nos
 * dois sentidos e a confiança usa consistência forward/backward + erro fotométrico.
 * O resultado volta como textura RGBA compacta para o shader fazer o warp em 4K.
 */
object OpenCvMotionEstimator {
    data class Field(
        val rgba: ByteArray,
        val flowScaleX: Float,
        val flowScaleY: Float,
        val meanConfidence: Float
    )

    @Volatile
    private var loadState: Boolean? = null

    @Synchronized
    private fun ensureLoaded() {
        loadState?.let { loaded ->
            check(loaded) { "OpenCV não pôde ser carregado no aparelho" }
            return
        }
        val loaded = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        loadState = loaded
        check(loaded) { "OpenCV não pôde ser carregado no aparelho" }
    }

    fun estimate(
        previousRgba: ByteArray,
        currentRgba: ByteArray,
        width: Int,
        height: Int,
        highQuality: Boolean
    ): Field {
        require(width > 8 && height > 8) { "Resolução insuficiente para fluxo óptico" }
        val pixels = width * height
        require(previousRgba.size == pixels * 4 && currentRgba.size == pixels * 4) {
            "Quadros de análise inválidos"
        }
        ensureLoaded()

        val previous = Mat(height, width, CvType.CV_8UC4)
        val current = Mat(height, width, CvType.CV_8UC4)
        val previousGray = Mat()
        val currentGray = Mat()
        val forward = Mat()
        val backward = Mat()
        try {
            previous.put(0, 0, previousRgba)
            current.put(0, 0, currentRgba)
            Imgproc.cvtColor(previous, previousGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(current, currentGray, Imgproc.COLOR_RGBA2GRAY)

            val levels = if (highQuality) 5 else 4
            val window = if (highQuality) 25 else 21
            val iterations = if (highQuality) 5 else 3
            val polyN = if (highQuality) 7 else 5
            val polySigma = if (highQuality) 1.5 else 1.2
            Video.calcOpticalFlowFarneback(
                previousGray, currentGray, forward,
                0.5, levels, window, iterations, polyN, polySigma, 0
            )
            Video.calcOpticalFlowFarneback(
                currentGray, previousGray, backward,
                0.5, levels, window, iterations, polyN, polySigma, 0
            )

            val forwardData = FloatArray(pixels * 2)
            val backwardData = FloatArray(pixels * 2)
            val previousLuma = ByteArray(pixels)
            val currentLuma = ByteArray(pixels)
            forward.get(0, 0, forwardData)
            backward.get(0, 0, backwardData)
            previousGray.get(0, 0, previousLuma)
            currentGray.get(0, 0, currentLuma)

            val maxFlow = if (highQuality) 14f else 10f
            val encoded = ByteArray(pixels * 4)
            var confidenceSum = 0.0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val rawDx = forwardData[index * 2]
                    val rawDy = forwardData[index * 2 + 1]
                    val dx = rawDx.coerceIn(-maxFlow, maxFlow)
                    val dy = rawDy.coerceIn(-maxFlow, maxFlow)
                    val targetX = x + dx
                    val targetY = y + dy
                    val backDx = sampleFlow(backwardData, width, height, targetX, targetY, 0)
                    val backDy = sampleFlow(backwardData, width, height, targetX, targetY, 1)
                    val consistencyError = hypot((dx + backDx).toDouble(), (dy + backDy).toDouble()).toFloat()
                    val previousValue = previousLuma[index].toInt() and 0xff
                    val currentValue = sampleGray(currentLuma, width, height, targetX, targetY)
                    val photoError = abs(previousValue.toFloat() - currentValue) / 255f

                    val consistencyConfidence = exp((-consistencyError / if (highQuality) 1.7f else 2.1f).toDouble()).toFloat()
                    val photoConfidence = (1f - photoError * 2.4f).coerceIn(0f, 1f)
                    val clipped = abs(rawDx) > maxFlow || abs(rawDy) > maxFlow
                    val boundaryPenalty = if (clipped) 0.45f else 1f
                    val confidence = ((consistencyConfidence * 0.72f + photoConfidence * 0.28f) * boundaryPenalty)
                        .coerceIn(0f, 1f)
                    confidenceSum += confidence.toDouble()

                    val out = index * 4
                    encoded[out] = encodeFlow(dx, maxFlow)
                    encoded[out + 1] = encodeFlow(dy, maxFlow)
                    encoded[out + 2] = (confidence * 255f).roundToInt().coerceIn(0, 255).toByte()
                    encoded[out + 3] = 0xff.toByte()
                }
            }
            return Field(
                rgba = encoded,
                flowScaleX = maxFlow / width.toFloat(),
                flowScaleY = maxFlow / height.toFloat(),
                meanConfidence = (confidenceSum / pixels.coerceAtLeast(1).toDouble()).toFloat()
            )
        } finally {
            previous.release()
            current.release()
            previousGray.release()
            currentGray.release()
            forward.release()
            backward.release()
        }
    }

    private fun encodeFlow(value: Float, maximum: Float): Byte {
        val encoded = (((value / maximum).coerceIn(-1f, 1f) * 0.5f + 0.5f) * 255f)
            .roundToInt().coerceIn(0, 255)
        return encoded.toByte()
    }

    private fun sampleFlow(
        data: FloatArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        channel: Int
    ): Float {
        val safeX = x.coerceIn(0f, (width - 1).toFloat())
        val safeY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(safeX.toDouble()).toInt()
        val y0 = floor(safeY.toDouble()).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        fun at(px: Int, py: Int): Float = data[(py * width + px) * 2 + channel]
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return top + (bottom - top) * fy
    }

    private fun sampleGray(data: ByteArray, width: Int, height: Int, x: Float, y: Float): Float {
        val safeX = x.coerceIn(0f, (width - 1).toFloat())
        val safeY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(safeX.toDouble()).toInt()
        val y0 = floor(safeY.toDouble()).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = safeX - x0
        val fy = safeY - y0
        fun at(px: Int, py: Int): Float = (data[py * width + px].toInt() and 0xff).toFloat()
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return top + (bottom - top) * fy
    }
}
''', encoding='utf-8')

# SMART/analise local passam a conhecer o modo novo.
advisor = 'app/src/main/java/com/steadyvault/camera/processing/analysis/OptimizationAdvisor.kt'
replace_once(
    advisor,
    '''                FrameRepairMode.ADAPTIVE_BLEND -> add("Somente lacunas muito curtas e com pouco movimento receberão mistura temporal")\n            }''',
    '''                FrameRepairMode.ADAPTIVE_BLEND -> add("Lacunas curtas receberão mistura temporal simples")\n                FrameRepairMode.MOTION_COMPENSATED -> add("Lacunas receberão interpolação compensada por movimento")\n            }'''
)
replace_once(
    advisor,
    '''                maxInterpolatedFramesPerGap = if (effectiveRepair == FrameRepairMode.ADAPTIVE_BLEND) 2 else 1,''',
    '''                maxInterpolatedFramesPerGap = if (\n                    effectiveRepair == FrameRepairMode.ADAPTIVE_BLEND ||\n                    effectiveRepair == FrameRepairMode.MOTION_COMPENSATED\n                ) 2 else 1,'''
)

ai = 'app/src/main/java/com/steadyvault/camera/processing/ai/AiVideoEnhancer.kt'
replace_once(
    ai,
    '''        val preferredRepair = when {\n            !analysis.hasCadenceProblems -> FrameRepairMode.NONE\n            analysis.estimatedMissingFrames <= 0 -> FrameRepairMode.SMOOTH_TIMELINE\n            safeForBlend -> FrameRepairMode.ADAPTIVE_BLEND\n            else -> FrameRepairMode.FILL_MISSING_FRAMES\n        }''',
    '''        val preferredRepair = when {\n            !analysis.hasCadenceProblems -> FrameRepairMode.NONE\n            analysis.estimatedMissingFrames <= 0 -> FrameRepairMode.SMOOTH_TIMELINE\n            else -> FrameRepairMode.MOTION_COMPENSATED\n        }'''
)
replace_once(
    ai,
    '''            if (analysis.hasCadenceProblems && !safeForBlend) {\n                add("Mistura temporal foi evitada para reduzir rastros e artefatos")\n            }''',
    '''            if (analysis.hasCadenceProblems && !safeForBlend) {\n                add("Movimento relevante detectado; o reparo usará fluxo óptico em vez de mistura simples")\n            }'''
)

# Hardware transcoder: remove block matching em shader; OpenCV calcula o fluxo em
# baixa resolução e a GPU faz somente o warp bidirecional em resolução final.
trans = 'app/src/main/java/com/steadyvault/camera/processing/transcode/HardwareVideoTranscoder.kt'
replace_once(
    trans,
    '''import com.steadyvault.camera.processing.model.VideoFilterConfig\n''',
    '''import com.steadyvault.camera.processing.model.VideoFilterConfig\nimport com.steadyvault.camera.processing.motion.OpenCvMotionEstimator\n'''
)
replace_once(
    trans,
    '''        highQualityMotion: Boolean\n    ) {''',
    '''        private val highQualityMotion: Boolean\n    ) {'''
)
replace_once(
    trans,
    '''        private var blendProgram = 0\n        private var motionFieldProgram = 0\n        private var motionInterpolateProgram = 0''',
    '''        private var blendProgram = 0\n        private var motionInterpolateProgram = 0'''
)
replace_once(
    trans,
    '''        private val motionTexture = IntArray(1)\n        private val motionFramebuffer = IntArray(1)\n        private val motionWidth = if (highQualityMotion) (width / 12).coerceIn(128, 320) else (width / 16).coerceIn(96, 240)\n        private val motionHeight = ((motionWidth.toLong() * height.toLong()) / width.coerceAtLeast(1).toLong()).toInt().coerceIn(54, 180)\n        private var motionFieldDirty = true''',
    '''        private val motionTexture = IntArray(1)\n        private val motionFramebuffer = IntArray(1)\n        private val analysisTexture = IntArray(1)\n        private val analysisFramebuffer = IntArray(1)\n        private val motionWidth = if (highQualityMotion) (width / 12).coerceIn(160, 320) else (width / 16).coerceIn(120, 240)\n        private val motionHeight = ((motionWidth.toLong() * height.toLong()) / width.coerceAtLeast(1).toLong()).toInt().coerceIn(68, 180)\n        private val motionReadback = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4).order(ByteOrder.nativeOrder())\n        private val previousMotionPixels = ByteArray(motionWidth * motionHeight * 4)\n        private val currentMotionPixels = ByteArray(motionWidth * motionHeight * 4)\n        private val motionUpload = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4).order(ByteOrder.nativeOrder())\n        private var motionFlowScaleX = 0f\n        private var motionFlowScaleY = 0f\n        private var motionFieldDirty = true'''
)
replace_once(
    trans,
    '''            blendProgram = createProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)\n            motionFieldProgram = createProgram(VERTEX_SHADER, MOTION_FIELD_FRAGMENT_SHADER)\n            motionInterpolateProgram = createProgram(VERTEX_SHADER, MOTION_INTERPOLATE_FRAGMENT_SHADER)''',
    '''            blendProgram = createProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)\n            motionInterpolateProgram = createProgram(VERTEX_SHADER, MOTION_INTERPOLATE_FRAGMENT_SHADER)'''
)
# Acrescenta framebuffer de downsample para readback.
replace_once(
    trans,
    '''            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {\n                "Framebuffer do campo de movimento incompleto"\n            }\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)''',
    '''            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {\n                "Framebuffer do campo de movimento incompleto"\n            }\n\n            GLES20.glGenTextures(1, analysisTexture, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture[0])\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)\n            GLES20.glTexImage2D(\n                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionWidth, motionHeight, 0,\n                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null\n            )\n            GLES20.glGenFramebuffers(1, analysisFramebuffer, 0)\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFramebuffer[0])\n            GLES20.glFramebufferTexture2D(\n                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, analysisTexture[0], 0\n            )\n            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {\n                "Framebuffer de análise de movimento incompleto"\n            }\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)'''
)
replace_once(
    trans,
    '''            GLES20.glUniform2f(\n                GLES20.glGetUniformLocation(motionInterpolateProgram, "uSearchStep"),\n                MOTION_SEARCH_STEP_PIXELS / width.toFloat(),\n                MOTION_SEARCH_STEP_PIXELS / height.toFloat()\n            )''',
    '''            GLES20.glUniform2f(\n                GLES20.glGetUniformLocation(motionInterpolateProgram, "uFlowScale"),\n                motionFlowScaleX,\n                motionFlowScaleY\n            )'''
)
# Troca ensureMotionField inteiro.
start = '''        private fun ensureMotionField() {\n            if (!motionFieldDirty) return\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFramebuffer[0])'''
end = '''            checkGl("calcular campo de movimento")\n        }\n\n        private fun drawTextures'''
text = Path(trans).read_text(encoding='utf-8')
si = text.find(start)
ei = text.find(end, si)
if si < 0 or ei < 0:
    raise SystemExit('ensureMotionField antigo nao encontrado')
ei += len('            checkGl("calcular campo de movimento")\n        }\n')
new_block = r'''        private fun ensureMotionField() {
            if (!motionFieldDirty) return
            readMotionFrame(previousIndex, previousMotionPixels)
            readMotionFrame(currentIndex, currentMotionPixels)
            val field = OpenCvMotionEstimator.estimate(
                previousRgba = previousMotionPixels,
                currentRgba = currentMotionPixels,
                width = motionWidth,
                height = motionHeight,
                highQuality = highQualityMotion
            )
            motionFlowScaleX = field.flowScaleX
            motionFlowScaleY = field.flowScaleY
            motionUpload.clear()
            motionUpload.put(field.rgba)
            motionUpload.flip()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexture[0])
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                motionWidth,
                motionHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                motionUpload
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            motionFieldDirty = false
            checkGl("enviar campo de movimento")
        }

        private fun readMotionFrame(textureIndex: Int, target: ByteArray) {
            check(textureIndex >= 0) { "Quadro de movimento indisponível" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFramebuffer[0])
            GLES20.glViewport(0, 0, motionWidth, motionHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(blendProgram)
            bindGeometry(blendProgram, identity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[textureIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrevious"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[textureIndex])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uCurrent"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uAlpha"), 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            motionReadback.clear()
            GLES20.glReadPixels(
                0, 0, motionWidth, motionHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, motionReadback
            )
            motionReadback.position(0)
            motionReadback.get(target, 0, target.size)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            checkGl("ler quadro para fluxo óptico")
        }
'''
text = text[:si] + new_block + text[ei:]
Path(trans).write_text(text, encoding='utf-8')

replace_once(
    trans,
    '''            GLES20.glDeleteTextures(1, motionTexture, 0)\n            GLES20.glDeleteFramebuffers(1, motionFramebuffer, 0)\n            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)\n            if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)\n            if (motionFieldProgram != 0) GLES20.glDeleteProgram(motionFieldProgram)\n            if (motionInterpolateProgram != 0) GLES20.glDeleteProgram(motionInterpolateProgram)''',
    '''            GLES20.glDeleteTextures(1, motionTexture, 0)\n            GLES20.glDeleteFramebuffers(1, motionFramebuffer, 0)\n            GLES20.glDeleteTextures(1, analysisTexture, 0)\n            GLES20.glDeleteFramebuffers(1, analysisFramebuffer, 0)\n            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)\n            if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)\n            if (motionInterpolateProgram != 0) GLES20.glDeleteProgram(motionInterpolateProgram)'''
)
replace_once(
    trans,
    '''            blendProgram = 0\n            motionFieldProgram = 0\n            motionInterpolateProgram = 0''',
    '''            blendProgram = 0\n            motionInterpolateProgram = 0'''
)
# Remove shader de block matching e usa flowScale vindo do OpenCV.
text = Path(trans).read_text(encoding='utf-8')
marker = '            private const val MOTION_FIELD_FRAGMENT_SHADER = """'
mi = text.find(marker)
if mi < 0:
    raise SystemExit('shader motion field nao encontrado')
next_marker = '            private const val MOTION_INTERPOLATE_FRAGMENT_SHADER = """'
ni = text.find(next_marker, mi)
if ni < 0:
    raise SystemExit('shader interpolate nao encontrado')
text = text[:mi] + text[ni:]
Path(trans).write_text(text, encoding='utf-8')
replace_once(
    trans,
    '''uniform sampler2D uMotion;\nuniform vec2 uSearchStep;\nuniform float uAlpha;''',
    '''uniform sampler2D uMotion;\nuniform vec2 uFlowScale;\nuniform float uAlpha;'''
)
replace_once(
    trans,
    '''    vec2 cells=(flow.rg-0.5)*10.0;\n    vec2 delta=cells*uSearchStep;''',
    '''    vec2 delta=(flow.rg*2.0-1.0)*uFlowScale;'''
)
replace_once(
    trans,
    '''        private const val MAX_GPU_FRAME_BYTES = 160L * 1024L * 1024L\n        private const val MOTION_SEARCH_STEP_PIXELS = 8f\n        private const val MOTION_PATCH_STEP_PIXELS = 4f''',
    '''        private const val MAX_GPU_FRAME_BYTES = 160L * 1024L * 1024L'''
)
