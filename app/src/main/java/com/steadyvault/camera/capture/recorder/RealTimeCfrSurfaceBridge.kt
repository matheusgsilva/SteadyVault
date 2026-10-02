package com.steadyvault.camera.capture.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/**
 * Ponte GPU CFR sem repetição de frames.
 *
 * Camera2 escreve em uma SurfaceTexture OES. O frame real anterior é preservado
 * em uma textura 2D da GPU antes de avançar para o próximo frame real. Se o delta
 * entre os timestamps da câmera indicar slots ausentes, esses slots são gerados
 * por interpolação temporal entre o frame anterior e o próximo frame real.
 *
 * Main5: as saídas seguem o TEMPO EXATO de captura ([CfrTimeResampler]). Frame alinhado
 * passa direto; frame perdido pela câmera é recriado por compensação de movimento
 * hierárquica na GPU ([MotionInterpolator]), com crossfade como rede de segurança. O frame
 * anterior fica em textura 2D e o warp é desenhado direto na Surface do encoder. Um frame
 * idêntico ao anterior reentregue pela HAL é detectado e descartado. Todas as saídas passam
 * pelo mesmo look de nitidez/cor ([VideoLook]). Nada disso usa readback/OpenCV no caminho
 * crítico das lacunas (a detecção de duplicado lê poucos KB da luma 1/32).
 *
 * O único readback restante é opcional e esparso para foco inteligente em background;
 * ele não participa da interpolação CFR.
 */
class RealTimeCfrSurfaceBridge(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val superStabilizationEnabled: Boolean = false,
    private val analysisEnabled: Boolean = false,
    private val onAnalysisFrame: ((ByteArray, Int, Int) -> Unit)? = null,
    private val onError: (Throwable) -> Unit
) {
    private val released = AtomicBoolean(false)
    private val outputEnabled = AtomicBoolean(false)
    private val pendingFrames = AtomicInteger(0)
    private val ready = CountDownLatch(1)
    private val stopped = CountDownLatch(1)

    private val frameSignalThread = HandlerThread(
        "SteadyVault-CfrFrameSignal",
        Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val frameSignalHandler = Handler(frameSignalThread.looper)

    @Volatile private var initError: Throwable? = null
    @Volatile private var cameraSurface: Surface? = null
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var realFrames = 0L
    @Volatile private var interpolatedFrames = 0L
    @Volatile private var droppedFrames = 0L
    @Volatile private var timingBlends = 0L
    @Volatile private var gapFilledFrames = 0L
    @Volatile private var motionFrames = 0L
    @Volatile private var motionFallbacks = 0L
    @Volatile private var duplicatesSkipped = 0L
    @Volatile private var repeatedOutputs = 0L
    @Volatile private var motionState = "desligado"
    @Volatile private var maxIntervalNs = 0L
    private val intervalBuckets = LongArray(5)
    @Volatile private var largestFillSlots = 0
    @Volatile private var worstFrameNs = 0L
    @Volatile private var maxBacklogSignals = 0
    private val summaryLogged = AtomicBoolean(false)
    @Volatile private var renderThreadRef: Thread? = null
    private val flushRequested = AtomicBoolean(false)

    private val renderThread = Thread(
        { renderLoop() },
        "SteadyVault-CfrGpu"
    ).apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    fun prepare(): Surface {
        check(ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "timeout preparando ponte CFR da câmera"
        }
        initError?.let { throw IllegalStateException("falha preparando ponte CFR", it) }
        return requireNotNull(cameraSurface) { "Surface CFR não criada" }
    }

    fun startOutput() {
        check(!released.get()) { "ponte CFR já liberada" }
        flushRequested.set(true)
        outputEnabled.set(true)
    }

    fun stopOutput() {
        outputEnabled.set(false)
    }

    fun stats(): Stats = Stats(
        realFrames = realFrames,
        interpolatedFrames = interpolatedFrames,
        droppedFrames = droppedFrames,
        largestFillSlots = largestFillSlots,
        worstFrameMs = worstFrameNs / 1_000_000L,
        maxBacklogSignals = maxBacklogSignals,
        timingBlends = timingBlends,
        gapFilledFrames = gapFilledFrames,
        motionFrames = motionFrames,
        motionFallbacks = motionFallbacks,
        duplicatesSkipped = duplicatesSkipped,
        repeatedOutputs = repeatedOutputs
    )

    private fun logSummary() {
        if (!summaryLogged.compareAndSet(false, true)) return
        val stats = stats()
        Log.i(
            TAG,
            "resumo CFR: reais=${stats.realFrames} misturaDeTempo=${stats.timingBlends} " +
                "gapsPreenchidos=${stats.gapFilledFrames} descartados=${stats.droppedFrames} " +
                "maiorGap=${stats.largestFillSlots}slots piorFrame=${stats.worstFrameMs}ms " +
                "filaMax=${stats.maxBacklogSignals} fps=$fps saida=${width}x$height"
        )
        Log.i(
            TAG,
            "movimento: estado=$motionState compensados=${stats.motionFrames} " +
                "voltaramAoCrossfade=${stats.motionFallbacks} " +
                "duplicadosDaCamera=${stats.duplicatesSkipped} repeticoesDaGrade=${stats.repeatedOutputs}"
        )
        val total = intervalBuckets.sum().coerceAtLeast(1L)
        fun pct(index: Int) = 100L * intervalBuckets[index] / total
        Log.i(
            TAG,
            "sensor: intervalos ±5%=${pct(0)}% ±15%=${pct(1)}% ±30%=${pct(2)}% " +
                "±50%=${pct(3)}% >50%=${pct(4)}% maiorIntervalo=${maxIntervalNs / 1_000_000L}ms"
        )
    }

    /** Histograma do intervalo REAL entre frames do sensor, em desvio relativo ao nominal. */
    private fun recordSensorInterval(deltaNs: Long, nominalNs: Long) {
        if (deltaNs > maxIntervalNs) maxIntervalNs = deltaNs
        val deviation = abs(deltaNs - nominalNs).toDouble() / nominalNs.toDouble()
        val bucket = when {
            deviation < 0.05 -> 0
            deviation < 0.15 -> 1
            deviation < 0.30 -> 2
            deviation < 0.50 -> 3
            else -> 4
        }
        intervalBuckets[bucket]++
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        outputEnabled.set(false)
        logSummary()
        renderThread.interrupt()
        runCatching { stopped.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        runCatching { frameSignalThread.quitSafely() }
        runCatching { frameSignalThread.join(RELEASE_TIMEOUT_MS) }
    }

    data class Stats(
        val realFrames: Long,
        val interpolatedFrames: Long,
        /** Frames reais descartados porque a câmera entregou acima do FPS nominal. */
        val droppedFrames: Long = 0L,
        /** Maior sequência de slots ausentes preenchida de uma vez. */
        val largestFillSlots: Int = 0,
        /** Pior tempo de processamento de um frame (cópia + blends + encoder). */
        val worstFrameMs: Long = 0L,
        /** Maior fila de frames aguardando a thread GL. */
        val maxBacklogSignals: Int = 0,
        /** Saídas misturadas só para corrigir o TEMPO de um frame (jitter), sem gap real. */
        val timingBlends: Long = 0L,
        /** Saídas sintetizadas para preencher frames que a câmera realmente perdeu. */
        val gapFilledFrames: Long = 0L,
        /** Saídas sintetizadas com compensação de movimento (sem ghosting). */
        val motionFrames: Long = 0L,
        /** Saídas que voltaram ao crossfade por erro/indisponibilidade do caminho de movimento. */
        val motionFallbacks: Long = 0L,
        /** Frames idênticos ao anterior reentregues pela câmera/HAL e descartados (viravam trava). */
        val duplicatesSkipped: Long = 0L,
        /** Saídas que repetiram o frame anterior por causa da grade de tempo (alpha ~ 0). */
        val repeatedOutputs: Long = 0L
    )

    private fun renderLoop() {
        renderThreadRef = Thread.currentThread()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var window = EGL14.EGL_NO_SURFACE
        var externalTexture = 0
        var previousTexture = 0
        var previousFramebuffer = 0
        var externalProgram = 0
        var blendProgram = 0
        var motionEstimateProgram = 0
        var motion: MotionInterpolator? = null
        var stabilizationProgram = 0
        val motionTextures = IntArray(1)
        var motionFramebuffer = 0
        val analysisTexture = IntArray(1)
        val analysisFramebuffer = IntArray(1)
        var st: SurfaceTexture? = null
        var camera: Surface? = null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display indisponível" }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                "falha inicializando EGL"
            }

            val configAttrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    configAttrs,
                    0,
                    configs,
                    0,
                    configs.size,
                    count,
                    0
                ) && count[0] > 0
            ) { "configuração EGL recordable indisponível" }
            val config = requireNotNull(configs[0])

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "falha criando contexto EGL" }

            window = EGL14.eglCreateWindowSurface(
                display,
                config,
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(window != EGL14.EGL_NO_SURFACE) { "falha criando EGLSurface do encoder" }
            check(EGL14.eglMakeCurrent(display, window, window, context)) {
                "falha ativando contexto EGL"
            }

            externalTexture = createExternalTexture()
            st = SurfaceTexture(externalTexture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(
                    {
                        pendingFrames.updateAndGet { current ->
                            if (current >= MAX_PENDING_SIGNAL_COUNT) current else current + 1
                        }
                        LockSupport.unpark(renderThreadRef)
                    },
                    frameSignalHandler
                )
            }
            surfaceTexture = st
            camera = Surface(st)
            cameraSurface = camera

            previousTexture = createStorageTexture(width, height)
            previousFramebuffer = createFramebuffer(previousTexture)
            externalProgram = createProgram(VERTEX_SHADER_EXTERNAL, VideoLook.insert(FRAGMENT_SHADER_EXTERNAL))
            blendProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_BLEND))
            motionEstimateProgram = createProgram(VERTEX_SHADER_BLEND, FRAGMENT_SHADER_GPU_MOTION_ESTIMATE)
            stabilizationProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_SUPER_STABILIZE))

            val motionWidth = (width / 16).coerceIn(160, 320)
            val motionHeight = ((motionWidth.toLong() * height.toLong()) / width.coerceAtLeast(1).toLong())
                .toInt().coerceIn(90, 240)
            val motionReadback = ByteBuffer.allocateDirect(motionWidth * motionHeight * 4)
                .order(ByteOrder.nativeOrder())
            val currentMotionPixels = ByteArray(motionWidth * motionHeight * 4)

            GLES20.glGenTextures(1, motionTextures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTextures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                motionWidth, motionHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            motionFramebuffer = createFramebuffer(motionTextures[0])
            GLES20.glGenTextures(1, analysisTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                motionWidth, motionHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glGenFramebuffers(1, analysisFramebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFramebuffer[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, analysisTexture[0], 0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "framebuffer de análise CFR incompleto"
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val interpolator = MotionInterpolator(width, height)
            motion = interpolator
            motionState = if (interpolator.initialize()) "ativo" else "indisponivel"
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)

            ready.countDown()

            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            val resampler = CfrTimeResampler(frameIntervalNs)
            var haveLatchedFrame = false
            var timelineStarted = false
            var previousSourceTimestampNs = 0L
            var copiedTimestampNs = Long.MIN_VALUE
            var outputPtsNs = 0L
            var lastAnalysisSampleMs = 0L
            var flowReady = false
            var lumaValid = false
            var consecutiveSkips = 0

            while (!released.get()) {
                if (!outputEnabled.get()) {
                    // Consome TODOS os buffers enfileirados, para a fila do SurfaceTexture não
                    // chegar ao início da gravação com frames velhos.
                    val queued = pendingFrames.getAndSet(0)
                    var consumed = 0
                    while (consumed < queued) {
                        runCatching {
                            st.updateTexImage()
                            haveLatchedFrame = true
                        }
                        consumed++
                    }
                    timelineStarted = false
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }

                if (flushRequested.compareAndSet(true, false)) {
                    val stale = pendingFrames.getAndSet(0)
                    var flushed = 0
                    while (flushed < stale) {
                        runCatching {
                            st.updateTexImage()
                            haveLatchedFrame = true
                        }
                        flushed++
                    }
                    timelineStarted = false
                }

                val backlog = pendingFrames.get()
                if (backlog <= 0) {
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                if (backlog > maxBacklogSignals) maxBacklogSignals = backlog

                pendingFrames.decrementAndGet()
                val frameStartNs = System.nanoTime()

                if (!timelineStarted) {
                    st.updateTexImage()
                    haveLatchedFrame = true
                    previousSourceTimestampNs = st.timestamp
                    resampler.start(previousSourceTimestampNs)
                    copiedTimestampNs = Long.MIN_VALUE
                    outputPtsNs = System.nanoTime()
                    renderExternalToEncoder(
                        st = st,
                        textureMatrix = textureMatrix,
                        program = externalProgram,
                        externalTexture = externalTexture,
                        vertices = vertices,
                        texCoords = texCoords,
                        display = display,
                        window = window,
                        presentationTimeNs = outputPtsNs
                    )
                    outputPtsNs += frameIntervalNs
                    realFrames++
                    timelineStarted = true
                    lumaValid = false
                    consecutiveSkips = 0
                    motion?.takeIf { it.available }?.let { mi ->
                        if (analyzeFrame(mi, st, textureMatrix, externalTexture, vertices, texCoords, false) != ANALYSIS_FAILED) {
                            mi.commit()
                            lumaValid = true
                        }
                    }
                    continue
                }

                if (!haveLatchedFrame) {
                    st.updateTexImage()
                    haveLatchedFrame = true
                    previousSourceTimestampNs = st.timestamp
                    lumaValid = false
                    continue
                }

                // Preserva o frame real anterior antes de SurfaceTexture avançar. A cópia só
                // acontece uma vez por frame latched.
                if (copiedTimestampNs != previousSourceTimestampNs) {
                    copyExternalToPreviousTexture(
                        st = st,
                        textureMatrix = textureMatrix,
                        program = externalProgram,
                        externalTexture = externalTexture,
                        framebuffer = previousFramebuffer,
                        vertices = vertices,
                        texCoords = texCoords
                    )
                    copiedTimestampNs = previousSourceTimestampNs
                }
                flowReady = false

                st.updateTexImage()
                val currentSourceTimestampNs = st.timestamp

                if (analysisEnabled && onAnalysisFrame != null) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastAnalysisSampleMs >= BACKGROUND_ANALYSIS_INTERVAL_MS) {
                        readCurrentAnalysisFrame(
                            st = st,
                            externalTexture = externalTexture,
                            framebuffer = analysisFramebuffer[0],
                            width = motionWidth,
                            height = motionHeight,
                            target = currentMotionPixels,
                            readback = motionReadback,
                            program = externalProgram,
                            vertices = vertices,
                            texCoords = texCoords,
                            textureMatrix = textureMatrix
                        )
                        lastAnalysisSampleMs = nowMs
                        runCatching {
                            onAnalysisFrame?.invoke(currentMotionPixels.copyOf(), motionWidth, motionHeight)
                        }
                    }
                }

                // Alguns drivers podem coalescer callbacks da SurfaceTexture.
                // O mesmo timestamp nunca pode virar um segundo frame no arquivo.
                if (currentSourceTimestampNs <= previousSourceTimestampNs) {
                    continue
                }

                // Luma do frame atual (para o fluxo) e detecção de frame duplicado: a HAL às
                // vezes reentrega o mesmo frame com outro timestamp, e isso aparece no vídeo
                // como uma trava de 1 frame. Um duplicado isolado é descartado; o próximo
                // frame real passa a cobrir os dois instantes (um deles interpolado).
                var analyzed = false
                val analysisMotion = motion
                if (analysisMotion != null && analysisMotion.available) {
                    val changed = analyzeFrame(
                        analysisMotion, st, textureMatrix, externalTexture, vertices, texCoords, lumaValid
                    )
                    analyzed = changed != ANALYSIS_FAILED
                    if (analyzed && lumaValid && changed in 0f..DUPLICATE_CHANGED_FRACTION &&
                        consecutiveSkips < MAX_CONSECUTIVE_SKIPS &&
                        currentSourceTimestampNs - previousSourceTimestampNs <= frameIntervalNs * 3 / 2
                    ) {
                        duplicatesSkipped++
                        consecutiveSkips++
                        continue
                    }
                    consecutiveSkips = 0
                }

                // Reamostragem pelo tempo EXATO de captura: cada saída (1/fps) usa o frame que
                // existiria naquele instante. Frame alinhado passa direto e nítido; frame
                // perdido vira interpolação com compensação de movimento.
                val sensorIntervalNs = currentSourceTimestampNs - previousSourceTimestampNs
                recordSensorInterval(sensorIntervalNs, frameIntervalNs)
                val outputs = resampler.plan(previousSourceTimestampNs, currentSourceTimestampNs)
                previousSourceTimestampNs = currentSourceTimestampNs

                if (outputs == 0) {
                    // Câmera acima do FPS nominal: nenhum instante de saída cai neste frame.
                    droppedFrames++
                    if (analyzed) {
                        motion?.commit()
                        lumaValid = true
                    }
                    continue
                }

                // O Super Estável estima o deslocamento de cada frame real (leve, 1/16).
                if (superStabilizationEnabled) {
                    renderGpuMotionEstimate(
                        st = st,
                        textureMatrix = textureMatrix,
                        program = motionEstimateProgram,
                        previousTexture = previousTexture,
                        externalTexture = externalTexture,
                        framebuffer = motionFramebuffer,
                        motionWidth = motionWidth,
                        motionHeight = motionHeight,
                        vertices = vertices,
                        texCoords = texCoords
                    )
                }

                var outputIndex = 0
                while (outputIndex < outputs) {
                    val alpha = resampler.alphaAt(outputIndex)
                    if (alpha <= 0.001f) repeatedOutputs++
                    if (alpha >= 1f) {
                        if (superStabilizationEnabled) {
                            renderSuperStableToEncoder(
                                st = st,
                                textureMatrix = textureMatrix,
                                program = stabilizationProgram,
                                externalTexture = externalTexture,
                                motionTexture = motionTextures[0],
                                motionWidth = motionWidth,
                                motionHeight = motionHeight,
                                vertices = vertices,
                                texCoords = texCoords,
                                display = display,
                                window = window,
                                presentationTimeNs = outputPtsNs
                            )
                        } else {
                            renderExternalToEncoder(
                                st = st,
                                textureMatrix = textureMatrix,
                                program = externalProgram,
                                externalTexture = externalTexture,
                                vertices = vertices,
                                texCoords = texCoords,
                                display = display,
                                window = window,
                                presentationTimeNs = outputPtsNs
                            )
                        }
                        realFrames++
                    } else {
                        var done = false
                        val mi = motion
                        if (mi != null && mi.available && outputs <= MAX_MOTION_OUTPUTS) {
                            done = lumaValid && renderMotionToEncoder(
                                mi, st, textureMatrix, previousTexture, externalTexture,
                                alpha, flowReady, vertices, texCoords, display, window, outputPtsNs
                            )
                            if (done) flowReady = true else motionFallbacks++
                        }
                        if (!done) renderBlendToEncoder(
                            st = st,
                            textureMatrix = textureMatrix,
                            program = blendProgram,
                            previousTexture = previousTexture,
                            externalTexture = externalTexture,
                            alpha = alpha,
                            vertices = vertices,
                            texCoords = texCoords,
                            display = display,
                            window = window,
                            presentationTimeNs = outputPtsNs
                        )
                        interpolatedFrames++
                        if (outputs == 1) timingBlends++ else gapFilledFrames++
                    }
                    outputPtsNs += frameIntervalNs
                    outputIndex++
                }

                if (analyzed) {
                    motion?.commit()
                    lumaValid = true
                } else {
                    lumaValid = false
                }
                if (outputs - 1 > largestFillSlots) largestFillSlots = outputs - 1
                val frameNs = System.nanoTime() - frameStartNs
                if (frameNs > worstFrameNs) worstFrameNs = frameNs
            }
        } catch (t: Throwable) {
            initError = t
            if (ready.count > 0L) ready.countDown()
            if (!released.get()) onError(t)
        } finally {
            runCatching { camera?.release() }
            cameraSurface = null
            runCatching { st?.release() }
            surfaceTexture = null

            if (display != EGL14.EGL_NO_DISPLAY) {
                if (externalProgram != 0) runCatching { GLES20.glDeleteProgram(externalProgram) }
                if (blendProgram != 0) runCatching { GLES20.glDeleteProgram(blendProgram) }
                if (motionEstimateProgram != 0) runCatching { GLES20.glDeleteProgram(motionEstimateProgram) }
                runCatching { motion?.release() }
                if (stabilizationProgram != 0) runCatching { GLES20.glDeleteProgram(stabilizationProgram) }
                if (motionFramebuffer != 0) runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(motionFramebuffer), 0) }
                runCatching { GLES20.glDeleteTextures(1, motionTextures, 0) }
                runCatching { GLES20.glDeleteTextures(1, analysisTexture, 0) }
                runCatching { GLES20.glDeleteFramebuffers(1, analysisFramebuffer, 0) }
                if (previousFramebuffer != 0) {
                    runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(previousFramebuffer), 0) }
                }
                if (previousTexture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(previousTexture), 0) }
                }
                if (externalTexture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(externalTexture), 0) }
                }
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
                if (window != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, window) }
                if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglReleaseThread() }
                runCatching { EGL14.eglTerminate(display) }
            }

            if (ready.count > 0L) ready.countDown()
            stopped.countDown()
        }
    }

    private fun copyExternalToPreviousTexture(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        framebuffer: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        renderExternal(
            st = st,
            textureMatrix = textureMatrix,
            program = program,
            externalTexture = externalTexture,
            vertices = vertices,
            texCoords = texCoords
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun renderExternalToEncoder(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        renderExternal(
            st = st,
            textureMatrix = textureMatrix,
            program = program,
            externalTexture = externalTexture,
            vertices = vertices,
            texCoords = texCoords,
            look = true
        )
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers falhou" }
    }

    private fun renderExternal(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        look: Boolean = false
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), if (look) 1f else 0f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / width,
            VideoLook.RADIUS_PX / height
        )

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun renderBlendToEncoder(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        previousTexture: Int,
        externalTexture: Int,
        alpha: Float,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val previousHandle = GLES20.glGetUniformLocation(program, "sPrevious")
        val currentHandle = GLES20.glGetUniformLocation(program, "sCurrent")
        val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / width,
            VideoLook.RADIUS_PX / height
        )

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture)
        GLES20.glUniform1i(previousHandle, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(currentHandle, 1)

        GLES20.glUniform1f(alphaHandle, alpha.coerceIn(0.001f, 0.999f))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers interpolado falhou" }
    }

    private fun readCurrentAnalysisFrame(
        st: SurfaceTexture,
        externalTexture: Int,
        framebuffer: Int,
        width: Int,
        height: Int,
        target: ByteArray,
        readback: ByteBuffer,
        program: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        textureMatrix: FloatArray
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        st.getTransformMatrix(textureMatrix)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        readback.clear()
        GLES20.glReadPixels(
            0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback
        )
        readback.position(0)
        readback.get(target, 0, target.size)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun renderGpuMotionEstimate(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        previousTexture: Int,
        externalTexture: Int,
        framebuffer: Int,
        motionWidth: Int,
        motionHeight: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, motionWidth, motionHeight)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sPrevious"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sCurrent"), 1)

        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uMotionTexel"),
            1f / motionWidth.coerceAtLeast(1).toFloat(),
            1f / motionHeight.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uSearchRadius"),
            GPU_SEARCH_RADIUS_TEXELS
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Analisa o frame atual na GPU (luma + comparação com o anterior). Devolve a fração de
     * texels que mudaram (0..1), -1 se não comparou ou [ANALYSIS_FAILED] se algo falhou, e
     * nesse caso o caminho de movimento é desligado de vez.
     */
    private fun analyzeFrame(
        mi: MotionInterpolator,
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        externalTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        compare: Boolean
    ): Float {
        return try {
            GLES20.glGetError()
            st.getTransformMatrix(textureMatrix)
            val changed = mi.analyze(externalTexture, textureMatrix, 0, vertices, texCoords, compare)
            if (!mi.healthy()) throw IllegalStateException("erro GL na análise do frame")
            changed
        } catch (t: Throwable) {
            Log.w(TAG, "análise de frame falhou, desligando movimento: ${t.message}")
            motionState = "desligadoPorErro"
            mi.release()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            ANALYSIS_FAILED
        }
    }

    /**
     * Gera a saída [alpha] por compensação de movimento. Retorna false (sem ter trocado
     * buffers) se algo falhou; o chamador desenha o crossfade no lugar e o caminho de
     * movimento é desligado de vez para não repetir o erro a 60 vezes por segundo.
     */
    private fun renderMotionToEncoder(
        mi: MotionInterpolator,
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        previousTexture: Int,
        externalTexture: Int,
        alpha: Float,
        flowReady: Boolean,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ): Boolean {
        try {
            GLES20.glGetError() // limpa erro pendente de outro passo
            st.getTransformMatrix(textureMatrix)
            if (!flowReady) {
                mi.estimate(vertices, texCoords)
                if (!mi.healthy()) throw IllegalStateException("erro GL na estimativa")
            }
            // Com o Super Estável, os frames reais saem recortados; o frame recriado usa o
            // mesmo recorte para não "pulsar" de zoom a cada lacuna.
            val crop = if (superStabilizationEnabled) SUPER_STABLE_CROP_SCALE else 1f
            mi.warp(alpha, previousTexture, externalTexture, textureMatrix, 0, vertices, texCoords, crop)
            if (!mi.healthy()) throw IllegalStateException("erro GL no warp")
        } catch (t: Throwable) {
            Log.w(TAG, "interpolação com movimento falhou, voltando ao crossfade: ${t.message}")
            motionState = "desligadoPorErro"
            mi.release()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers com movimento falhou" }
        motionFrames++
        return true
    }

    private fun renderSuperStableToEncoder(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        motionTexture: Int,
        motionWidth: Int,
        motionHeight: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sCurrent"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sMotion"), 1)

        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uMotionTexel"),
            1f / motionWidth.coerceAtLeast(1).toFloat(),
            1f / motionHeight.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSearchRadius"), GPU_SEARCH_RADIUS_TEXELS)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), SUPER_STABLE_STRENGTH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCropScale"), SUPER_STABLE_CROP_SCALE)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), 1f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / width,
            VideoLook.RADIUS_PX / height
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers Super Estável falhou" }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        check(id != 0) { "falha criando textura OES" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createStorageTexture(width: Int, height: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        check(id != 0) { "falha criando textura de histórico CFR" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        return id
    }

    private fun createFramebuffer(texture: Int): Int {
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        val id = framebuffers[0]
        check(id != 0) { "falha criando framebuffer CFR" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "framebuffer CFR incompleto"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return id
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(linked[0] == GLES20.GL_TRUE) {
                "falha linkando shader CFR: ${GLES20.glGetProgramInfoLog(program)}"
            }
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) {
                "falha compilando shader CFR: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val BACKGROUND_ANALYSIS_INTERVAL_MS = 1_500L

        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val PREPARE_TIMEOUT_SECONDS = 5L
        private const val RELEASE_TIMEOUT_MS = 1_000L
        private const val IDLE_POLL_NS = 1_000_000L
        private const val MAX_PENDING_SIGNAL_COUNT = 8
        private const val TAG = "SteadyVaultCfr"

        /** Acima disso (lacuna longa) o movimento entre os frames reais já não é estimável: crossfade. */
        private const val MAX_MOTION_OUTPUTS = 4

        private const val ANALYSIS_FAILED = -2f

        /** Abaixo disso (fração de texels 1/8 que mudaram) o frame é idêntico ao anterior. */
        private const val DUPLICATE_CHANGED_FRACTION = 0.002f
        private const val MAX_CONSECUTIVE_SKIPS = 2

        private val VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER_EXTERNAL = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTextureMatrix;
            uniform float uLook;
            LOOK_GLSL
            void main() {
                vec3 c = texture2D(sTexture, vTexCoord).rgb;
                if (uLook > 0.5) {
                    // Offsets em coordenadas de textura: parte linear da matriz (w = 0).
                    vec2 dx = (uTextureMatrix * vec4(uLookTexel.x, 0.0, 0.0, 0.0)).xy;
                    vec2 dy = (uTextureMatrix * vec4(0.0, uLookTexel.y, 0.0, 0.0)).xy;
                    vec3 blur = 0.25 * (texture2D(sTexture, vTexCoord + dx).rgb +
                                        texture2D(sTexture, vTexCoord - dx).rgb +
                                        texture2D(sTexture, vTexCoord + dy).rgb +
                                        texture2D(sTexture, vTexCoord - dy).rgb);
                    c = lookGrade(c, blur);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """

        private const val VERTEX_SHADER_BLEND = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            void main() {
                gl_Position = aPosition;
                vPreviousCoord = aTexCoord.xy;
                vCurrentCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Rede de segurança (sem movimento): crossfade temporal + look.
        private const val FRAGMENT_SHADER_BLEND = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            uniform sampler2D sPrevious;
            uniform samplerExternalOES sCurrent;
            uniform mat4 uTextureMatrix;
            uniform float uAlpha;
            LOOK_GLSL
            vec3 composite(vec2 previousUv, vec2 currentUv) {
                return mix(texture2D(sPrevious, previousUv).rgb, texture2D(sCurrent, currentUv).rgb, uAlpha);
            }
            void main() {
                vec2 dx = vec2(uLookTexel.x, 0.0);
                vec2 dy = vec2(0.0, uLookTexel.y);
                vec2 tx = (uTextureMatrix * vec4(uLookTexel.x, 0.0, 0.0, 0.0)).xy;
                vec2 ty = (uTextureMatrix * vec4(0.0, uLookTexel.y, 0.0, 0.0)).xy;
                vec3 c = composite(vPreviousCoord, vCurrentCoord);
                vec3 blur = 0.25 * (composite(vPreviousCoord + dx, vCurrentCoord + tx) +
                                    composite(vPreviousCoord - dx, vCurrentCoord - tx) +
                                    composite(vPreviousCoord + dy, vCurrentCoord + ty) +
                                    composite(vPreviousCoord - dy, vCurrentCoord - ty));
                gl_FragColor = vec4(lookGrade(c, blur), 1.0);
            }
        """

        private const val GPU_SEARCH_RADIUS_TEXELS = 3.0f

        private const val FRAGMENT_SHADER_GPU_MOTION_ESTIMATE = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            uniform sampler2D sPrevious;
            uniform samplerExternalOES sCurrent;
            uniform vec2 uMotionTexel;
            uniform float uSearchRadius;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float patchError(vec2 prevUv, vec2 currUv, vec2 offset) {
                vec2 tx = vec2(uMotionTexel.x, 0.0);
                vec2 ty = vec2(0.0, uMotionTexel.y);
                float e = 0.0;
                e += abs(luma(texture2D(sPrevious, clamp(prevUv,0.0,1.0)).rgb) -
                         luma(texture2D(sCurrent, clamp(currUv+offset,0.0,1.0)).rgb));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv+tx,0.0,1.0)).rgb) -
                              luma(texture2D(sCurrent, clamp(currUv+offset+tx,0.0,1.0)).rgb));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv-tx,0.0,1.0)).rgb) -
                              luma(texture2D(sCurrent, clamp(currUv+offset-tx,0.0,1.0)).rgb));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv+ty,0.0,1.0)).rgb) -
                              luma(texture2D(sCurrent, clamp(currUv+offset+ty,0.0,1.0)).rgb));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv-ty,0.0,1.0)).rgb) -
                              luma(texture2D(sCurrent, clamp(currUv+offset-ty,0.0,1.0)).rgb));
                return e / 3.2;
            }

            void consider(inout float bestError, inout vec2 bestOffset, vec2 candidate) {
                float e = patchError(vPreviousCoord, vCurrentCoord, candidate);
                if (e < bestError) {
                    bestError = e;
                    bestOffset = candidate;
                }
            }

            void main() {
                vec2 r = uMotionTexel * uSearchRadius;
                float bestError = 999.0;
                vec2 bestOffset = vec2(0.0);

                consider(bestError,bestOffset,vec2(0.0));
                consider(bestError,bestOffset,vec2( r.x, 0.0));
                consider(bestError,bestOffset,vec2(-r.x, 0.0));
                consider(bestError,bestOffset,vec2(0.0, r.y));
                consider(bestError,bestOffset,vec2(0.0,-r.y));
                consider(bestError,bestOffset,vec2( r.x, r.y));
                consider(bestError,bestOffset,vec2(-r.x, r.y));
                consider(bestError,bestOffset,vec2( r.x,-r.y));
                consider(bestError,bestOffset,vec2(-r.x,-r.y));

                float confidence = 1.0 - smoothstep(0.035, 0.22, bestError);
                vec2 encoded = bestOffset / max(r, vec2(0.000001));
                encoded = encoded * 0.5 + 0.5;
                gl_FragColor = vec4(encoded, confidence, 1.0);
            }
        """

        private const val SUPER_STABLE_STRENGTH = 0.68f
        private const val SUPER_STABLE_CROP_SCALE = 0.90f

        private const val FRAGMENT_SHADER_SUPER_STABILIZE = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            uniform samplerExternalOES sCurrent;
            uniform sampler2D sMotion;
            uniform vec2 uMotionTexel;
            uniform float uSearchRadius;
            uniform float uStrength;
            uniform float uCropScale;
            uniform mat4 uTextureMatrix;
            uniform float uLook;
            LOOK_GLSL

            vec2 decodeMotion(vec2 uv) {
                vec4 m = texture2D(sMotion, uv);
                return (m.rg * 2.0 - 1.0) * (uMotionTexel * uSearchRadius) * m.b;
            }

            void main() {
                vec2 m = vec2(0.0);
                m += decodeMotion(vec2(0.25,0.25));
                m += decodeMotion(vec2(0.50,0.25));
                m += decodeMotion(vec2(0.75,0.25));
                m += decodeMotion(vec2(0.25,0.50));
                m += decodeMotion(vec2(0.50,0.50)) * 2.0;
                m += decodeMotion(vec2(0.75,0.50));
                m += decodeMotion(vec2(0.25,0.75));
                m += decodeMotion(vec2(0.50,0.75));
                m += decodeMotion(vec2(0.75,0.75));
                m /= 10.0;

                vec2 cropped = vec2(0.5) + (vCurrentCoord - vec2(0.5)) * uCropScale;
                vec2 stabilizedUv = clamp(cropped + m * uStrength, 0.001, 0.999);
                vec3 c = texture2D(sCurrent, stabilizedUv).rgb;
                if (uLook > 0.5) {
                    vec2 dx = (uTextureMatrix * vec4(uLookTexel.x, 0.0, 0.0, 0.0)).xy * uCropScale;
                    vec2 dy = (uTextureMatrix * vec4(0.0, uLookTexel.y, 0.0, 0.0)).xy * uCropScale;
                    vec3 blur = 0.25 * (texture2D(sCurrent, clamp(stabilizedUv + dx, 0.001, 0.999)).rgb +
                                        texture2D(sCurrent, clamp(stabilizedUv - dx, 0.001, 0.999)).rgb +
                                        texture2D(sCurrent, clamp(stabilizedUv + dy, 0.001, 0.999)).rgb +
                                        texture2D(sCurrent, clamp(stabilizedUv - dy, 0.001, 0.999)).rgb);
                    c = lookGrade(c, blur);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
