package com.steadyvault.camera.capture.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGL15
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
import java.util.concurrent.LinkedBlockingQueue
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
 * hierárquica na GPU ([MotionInterpolator]), com crossfade como rede de segurança. Todas as
 * saídas passam pelo mesmo look de nitidez/cor ([VideoLook]).
 *
 * Captura e processamento são DESACOPLADOS: cada frame da câmera é copiado, assim que chega,
 * para um anel de texturas 2D já no espaço de saída (retrato), o que devolve o buffer à
 * câmera na hora. O trabalho pesado (lacunas) consome dessa fila, e a fila é reabastecida
 * entre as saídas de uma lacuna. Antes, uma lacuna de vários frames segurava a thread GL por
 * dezenas de ms, a câmera ficava sem buffer e perdia ainda mais frames. Luma/fluxo só são
 * calculados quando há lacuna; nada roda por frame além da cópia e da saída.
 *
 * O único readback restante é opcional e esparso para foco inteligente em background;
 * ele não participa da interpolação CFR.
 */
class RealTimeCfrSurfaceBridge(
    private val encoderSurface: Surface,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val physicalRotationDegrees: Int,
    private val fps: Int,
    private val superStabilizationEnabled: Boolean = false,
    private val analysisEnabled: Boolean = false,
    private val onAnalysisFrame: ((ByteArray, Int, Int) -> Unit)? = null,
    private val analysisIntervalMs: Long = 700L,
    private val lookProfile: Int = VideoLook.PROFILE_NATURAL,
    private val onError: (Throwable) -> Unit
) {
    /** Frame real já no espaço de saída, guardado numa textura 2D do anel. */
    private class Slot(val texture: Int) {
        /** Framebuffer do contexto de RECEPÇÃO (framebuffers não são compartilhados). */
        var framebuffer = 0
        @Volatile var timestampNs = 0L
        /** Ordem de chegada (para detectar frames fora de ordem entre as threads). */
        @Volatile var sequence = 0L
        /** Cerca EGL: a cópia da recepção terminou (a saída espera antes de ler). */
        @Volatile var fence: android.opengl.EGLSync? = null
    }

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
    private var fillLogCount = 0
    @Volatile private var worstFrameNs = 0L
    @Volatile private var maxBacklogSignals = 0
    private val summaryLogged = AtomicBoolean(false)
    @Volatile private var renderThreadRef: Thread? = null
    // Cada startOutput() abre uma nova "geração": as duas threads descartam o que sobrou.
    private val generation = AtomicInteger(0)
    @Volatile private var pipelineStopped = false
    private val frameQueue = LinkedBlockingQueue<Slot>()
    private val freeSlots = LinkedBlockingQueue<Slot>()

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
        generation.incrementAndGet()
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

    // Telemetria de tempo (ms) por etapa; aparece em "tempos:" no resumo.
    @Volatile private var swapMaxNs = 0L
    @Volatile private var swapTotalNs = 0L
    @Volatile private var swapCount = 0L
    @Volatile private var ingestMaxNs = 0L
    @Volatile private var ingestTotalNs = 0L
    @Volatile private var ingestCount = 0L
    @Volatile private var estimateMaxNs = 0L
    @Volatile private var warpMaxNs = 0L
    @Volatile private var ringFullWaits = 0L
    // Onde os frames se perdem: sinais que a câmera entregou à ponte x frames que a ponte consumiu.
    @Volatile private var cameraSignals = 0L
    @Volatile private var signalLongGaps = 0L
    @Volatile private var signalMaxGapNs = 0L
    @Volatile private var lastSignalNs = 0L
    @Volatile private var slowSwaps = 0L
    @Volatile private var lateIngests = 0L
    @Volatile private var ingestStallMaxNs = 0L
    @Volatile private var lastIngestStartNs = 0L
    @Volatile private var motionThrottled = 0L
    @Volatile private var orderViolations = 0L
    @Volatile private var fenceFallbacks = 0L

    private fun swapBuffers(display: android.opengl.EGLDisplay, window: android.opengl.EGLSurface, message: String) {
        val start = System.nanoTime()
        check(EGL14.eglSwapBuffers(display, window)) { message }
        val elapsed = System.nanoTime() - start
        swapTotalNs += elapsed
        swapCount++
        if (elapsed > swapMaxNs) swapMaxNs = elapsed
        if (elapsed > 20_000_000L) slowSwaps++
    }

    private fun logSummary() {
        if (!summaryLogged.compareAndSet(false, true)) return
        val stats = stats()
        Log.i(
            TAG,
            "resumo CFR: reais=${stats.realFrames} misturaDeTempo=${stats.timingBlends} " +
                "gapsPreenchidos=${stats.gapFilledFrames} descartados=${stats.droppedFrames} " +
                "maiorGap=${stats.largestFillSlots}slots piorFrame=${stats.worstFrameMs}ms " +
                "filaMax=${stats.maxBacklogSignals} fps=$fps saida=${outputWidth}x$outputHeight"
        )
        Log.i(
            TAG,
            "movimento: estado=$motionState compensados=${stats.motionFrames} " +
                "voltaramAoCrossfade=${stats.motionFallbacks} " +
                "duplicadosDaCamera=${stats.duplicatesSkipped} repeticoesDaGrade=${stats.repeatedOutputs} " +
                "limitadosPorCusto=$motionThrottled foraDeOrdem=$orderViolations semCerca=$fenceFallbacks"
        )
        fun ms(ns: Long) = ns / 1_000_000L
        Log.i(
            TAG,
            "tempos(ms): swapMax=${ms(swapMaxNs)} swapMedia=${ms(swapTotalNs / swapCount.coerceAtLeast(1L))} " +
                "ingestMax=${ms(ingestMaxNs)} ingestMedia=${ms(ingestTotalNs / ingestCount.coerceAtLeast(1L))} " +
                "fluxoMax=${ms(estimateMaxNs)} warpMax=${ms(warpMaxNs)} anelCheio=$ringFullWaits " +
                "swapsLentos(>20ms)=$slowSwaps"
        )
        // Se sinaisDaCamera ~ reais+descartados, a perda é ANTES da ponte (câmera/HAL). Se sinais >>
        // consumidos, a ponte/encoder atrasou. ingestTardios = vezes que o laço ficou >25 ms sem
        // trazer frame (a câmera fica sem buffer nesse intervalo).
        Log.i(
            TAG,
            "entrega: sinaisDaCamera=$cameraSignals sinaisComBuraco(>25ms)=$signalLongGaps " +
                "maiorBuracoDeSinal=${ms(signalMaxGapNs)}ms ingeridos=$ingestCount " +
                "ingestTardios=$lateIngests maiorParadaDoLaco=${ms(ingestStallMaxNs)}ms"
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
        LockSupport.unpark(renderThreadRef)
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

    /** Devolve um slot ao anel depois que a GPU terminou de ler dele. */
    private fun releaseSlot(slot: Slot) {
        freeSlots.add(slot)
    }

    /**
     * Thread GL de SAÍDA: consome a fila de frames já prontos, preenche lacunas e entrega ao
     * encoder. Pode bloquear em eglSwapBuffers (encoder cheio) sem afetar a câmera, porque a
     * recepção dos frames é feita por [ingestLoop] em outra thread/contexto.
     */
    private fun renderLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var window = EGL14.EGL_NO_SURFACE
        val ring = ArrayList<Slot>()
        var textureProgram = 0
        var blendProgram = 0
        var motionEstimateProgram = 0
        var motion: MotionInterpolator? = null
        var stabilizationProgram = 0
        val motionTextures = IntArray(1)
        var motionFramebuffer = 0
        var ingestThread: Thread? = null

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

            // Anel de frames já no espaço de saída (retrato, cru), compartilhado com a thread de
            // recepção. Cada frame da câmera é copiado para cá assim que chega, liberando o
            // buffer dela na hora.
            repeat(RING_SIZE) { ring += Slot(createStorageTexture(outputWidth, outputHeight)) }
            GLES20.glFinish()
            textureProgram = createProgram(VERTEX_SHADER_EXTERNAL, VideoLook.insert(FRAGMENT_SHADER_TEXTURE, lookProfile))
            blendProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_BLEND, lookProfile))
            motionEstimateProgram = createProgram(VERTEX_SHADER_BLEND, FRAGMENT_SHADER_GPU_MOTION_ESTIMATE)
            stabilizationProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_SUPER_STABILIZE, lookProfile))

            // Estimativa do Super Estável: no espaço de SAÍDA (retrato).
            val motionWidth = (outputWidth / 16).coerceIn(90, 320)
            val motionHeight = ((motionWidth.toLong() * outputHeight.toLong()) / outputWidth.coerceAtLeast(1).toLong())
                .toInt().coerceIn(90, 320)


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


            val interpolator = MotionInterpolator(outputWidth, outputHeight, lookProfile)
            motion = interpolator
            motionState = if (interpolator.initialize()) "ativo" else "indisponivel"
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glFinish()

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)

            // Thread de recepção: SurfaceTexture, cópia câmera -> anel e foco inteligente.
            ring.forEach { freeSlots.add(it) }
            val ingestReady = CountDownLatch(1)
            val shareContext = context
            val shareConfig = config
            val shareDisplay = display
            ingestThread = Thread(
                { ingestLoop(shareDisplay, shareConfig, shareContext, ring, ingestReady) },
                "SteadyVault-CfrIngest"
            ).apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            check(ingestReady.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "timeout preparando thread de recepção CFR"
            }
            initError?.let { throw it }
            ready.countDown()

            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            val resampler = CfrTimeResampler(frameIntervalNs)
            var previousSlot: Slot? = null
            var timelineStarted = false
            var motionLightUntilNs = 0L
            var outputPtsNs = 0L
            var pureBaseSensorNs = 0L
            var pureBasePtsNs = 0L
            var lastSequence = 0L
            var seenGeneration = generation.get()

            // A GPU precisa ter terminado de ler o slot antes de a thread de recepção reescrevê-lo.
            fun giveBack(slot: Slot) {
                GLES20.glFinish()
                releaseSlot(slot)
            }

            fun resetOutput() {
                while (true) {
                    val stale = frameQueue.poll() ?: break
                    val staleFence: android.opengl.EGLSync? = stale.fence
                    if (staleFence != null) {
                        try {
                            EGL15.eglDestroySync(display, staleFence)
                        } catch (_: Throwable) {
                        }
                    }
                    stale.fence = null
                    freeSlots.add(stale)
                }
                previousSlot?.let { giveBack(it) }
                previousSlot = null
                timelineStarted = false
                lastSequence = 0L
            }

            while (!released.get() && !pipelineStopped) {
                if (!outputEnabled.get()) {
                    resetOutput()
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                val generationNow = generation.get()
                if (generationNow != seenGeneration) {
                    seenGeneration = generationNow
                    resetOutput()
                }

                val current = frameQueue.poll(2, TimeUnit.MILLISECONDS) ?: continue
                val currentFence: android.opengl.EGLSync? = current.fence
                if (currentFence != null) {
                    try {
                        EGL15.eglClientWaitSync(
                            display, currentFence, EGL15.EGL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS
                        )
                    } catch (_: Throwable) {
                    }
                    try {
                        EGL15.eglDestroySync(display, currentFence)
                    } catch (_: Throwable) {
                    }
                    current.fence = null
                }
                if (current.sequence <= lastSequence) orderViolations++
                lastSequence = current.sequence
                val backlog = frameQueue.size + 1
                if (backlog > maxBacklogSignals) maxBacklogSignals = backlog
                val frameStartNs = System.nanoTime()
                val previous = previousSlot

                if (!timelineStarted || previous == null) {
                    resampler.start(current.timestampNs)
                    outputPtsNs = System.nanoTime()
                    pureBaseSensorNs = current.timestampNs
                    pureBasePtsNs = outputPtsNs
                    renderTextureToEncoder(
                        program = textureProgram,
                        texture = current.texture,
                        vertices = vertices,
                        texCoords = texCoords,
                        display = display,
                        window = window,
                        presentationTimeNs = outputPtsNs
                    )
                    outputPtsNs += frameIntervalNs
                    realFrames++
                    previous?.let { giveBack(it) }
                    previousSlot = current
                    timelineStarted = true
                    continue
                }

                // O mesmo timestamp nunca pode virar um segundo frame no arquivo.
                if (current.timestampNs <= previous.timestampNs) {
                    releaseSlot(current)
                    continue
                }

                // Reamostragem pelo tempo EXATO de captura: cada saída (1/fps) usa o frame que
                // existiria naquele instante. Frame alinhado passa direto e nítido; frame
                // perdido vira interpolação com compensação de movimento.
                recordSensorInterval(current.timestampNs - previous.timestampNs, frameIntervalNs)
                // GRAVAÇÃO PURA: um frame da câmera = um frame no arquivo, com o instante real do
                // sensor como PTS. Nada de frame criado, repetido ou descartado.
                val outputs = if (PURE_FRAMES) 1 else resampler.plan(previous.timestampNs, current.timestampNs)
                if (PURE_FRAMES) outputPtsNs = pureBasePtsNs + (current.timestampNs - pureBaseSensorNs)

                if (outputs == 0) {
                    // Câmera acima do FPS nominal: nenhum instante de saída cai neste frame.
                    droppedFrames++
                    giveBack(previous)
                    previousSlot = current
                    continue
                }

                // O Super Estável estima o deslocamento de cada frame real (leve, 1/16).
                if (superStabilizationEnabled) {
                    renderGpuMotionEstimate(
                        program = motionEstimateProgram,
                        previousTexture = previous.texture,
                        currentTexture = current.texture,
                        framebuffer = motionFramebuffer,
                        motionWidth = motionWidth,
                        motionHeight = motionHeight,
                        vertices = vertices,
                        texCoords = texCoords
                    )
                }

                // Limitador de custo: preencher lacuna com fluxo óptico em 4K custa ~15–80 ms de GPU.
                // Se o laço já está atrasado (frames esperando) ou a lacuna anterior estourou o
                // orçamento, usa a mistura barata. Sem isso, cada lacuna parava o laço, a câmera
                // ficava sem buffer e perdia outro frame (reação em cadeia).
                val nowGapNs = System.nanoTime()
                val motionAllowed = nowGapNs >= motionLightUntilNs &&
                    (pendingFrames.get() + frameQueue.size) < MOTION_MAX_BACKLOG
                var usedMotionThisFrame = false
                var flowReady = false
                var outputIndex = 0
                while (outputIndex < outputs) {
                    // Lacuna (2+ saídas para um frame real): as saídas intermediárias ficam
                    // igualmente espaçadas entre o frame anterior e o atual. Deixar a fase da
                    // grade decidir colava a intermediária no frame anterior (alfa ~0): uma
                    // repetição seguida de salto duplo, visível em panorâmica (medido no mp4).
                    val alpha = if (PURE_FRAMES) {
                        1f
                    } else if (outputs >= 2 && outputIndex < outputs - 1) {
                        (outputIndex + 1).toFloat() / outputs
                    } else {
                        resampler.alphaAt(outputIndex)
                    }
                    if (alpha <= 0.001f) repeatedOutputs++
                    if (alpha >= 1f) {
                        if (superStabilizationEnabled) {
                            renderSuperStableToEncoder(
                                program = stabilizationProgram,
                                currentTexture = current.texture,
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
                            renderTextureToEncoder(
                                program = textureProgram,
                                texture = current.texture,
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
                        if (mi != null && mi.available && outputs <= MAX_MOTION_OUTPUTS && !motionAllowed) {
                            motionThrottled++
                        }
                        if (mi != null && mi.available && outputs <= MAX_MOTION_OUTPUTS && motionAllowed) {
                            usedMotionThisFrame = true
                            done = renderMotionToEncoder(
                                mi, previous.texture, current.texture,
                                alpha, flowReady, vertices, texCoords, display, window, outputPtsNs
                            )
                            if (done) flowReady = true else motionFallbacks++
                        }
                        // Reserva (só se o movimento compensado falhar ou o anel estiver quase
                        // cheio): mistura temporal. Nunca repete frame.
                        if (!done) renderBlendToEncoder(
                            program = blendProgram,
                            previousTexture = previous.texture,
                            currentTexture = current.texture,
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
                if (outputs >= 2 && fillLogCount < FILL_LOG_LIMIT) {
                    fillLogCount++
                    Log.i(
                        TAG,
                        "lacuna: saídas=$outputs intervaloSensor=" +
                            "${(current.timestampNs - previous.timestampNs) / 1_000_000.0}ms " +
                            "movimento=$usedMotionThisFrame custo=${(System.nanoTime() - frameStartNs) / 1_000_000}ms " +
                            "fila=${frameQueue.size}"
                    )
                }


                giveBack(previous)
                previousSlot = current
                if (outputs - 1 > largestFillSlots) largestFillSlots = outputs - 1
                val frameNs = System.nanoTime() - frameStartNs
                if (frameNs > worstFrameNs) worstFrameNs = frameNs
                if (usedMotionThisFrame && frameNs > MOTION_BUDGET_NS) {
                    motionLightUntilNs = System.nanoTime() + MOTION_COOLDOWN_NS
                }
            }

        } catch (t: Throwable) {
            initError = t
            if (ready.count > 0L) ready.countDown()
            if (!released.get()) onError(t)
        } finally {
            pipelineStopped = true
            LockSupport.unpark(renderThreadRef)
            runCatching { ingestThread?.join(RELEASE_TIMEOUT_MS) }

            if (display != EGL14.EGL_NO_DISPLAY) {
                if (blendProgram != 0) runCatching { GLES20.glDeleteProgram(blendProgram) }
                if (motionEstimateProgram != 0) runCatching { GLES20.glDeleteProgram(motionEstimateProgram) }
                runCatching { motion?.release() }
                if (stabilizationProgram != 0) runCatching { GLES20.glDeleteProgram(stabilizationProgram) }
                if (motionFramebuffer != 0) runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(motionFramebuffer), 0) }
                runCatching { GLES20.glDeleteTextures(1, motionTextures, 0) }
                for (slot in ring) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0) }
                }
                if (textureProgram != 0) runCatching { GLES20.glDeleteProgram(textureProgram) }
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

    /**
     * Thread GL de RECEPÇÃO: dona da SurfaceTexture. Copia cada frame da câmera para um slot do
     * anel (com o filtro temporal) assim que chega e o enfileira para a thread de saída, de modo
     * que nada que bloqueie a saída (encoder cheio, fluxo óptico) segure os buffers da câmera.
     */
    private fun ingestLoop(
        display: android.opengl.EGLDisplay,
        sharedConfig: android.opengl.EGLConfig,
        shareContext: android.opengl.EGLContext,
        ring: List<Slot>,
        latch: CountDownLatch
    ) {
        renderThreadRef = Thread.currentThread()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        var ingestContext = EGL14.EGL_NO_CONTEXT
        var pbuffer = EGL14.EGL_NO_SURFACE
        var externalTexture = 0
        var externalProgram = 0
        var ingestProgram = 0
        var gmc: GlobalMotionEstimator? = null
        val analysisTexture = IntArray(1)
        val analysisFramebuffer = IntArray(1)
        var st: SurfaceTexture? = null
        var camera: Surface? = null

        try {
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            var current = false
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
            if (extensions.contains("EGL_KHR_surfaceless_context")) {
                ingestContext = EGL14.eglCreateContext(display, sharedConfig, shareContext, contextAttrs, 0)
                if (ingestContext != EGL14.EGL_NO_CONTEXT) {
                    current = EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ingestContext
                    )
                }
            }
            if (!current) {
                if (ingestContext != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(display, ingestContext) }
                    ingestContext = EGL14.EGL_NO_CONTEXT
                }
                val pbufferConfigs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val pbufferCount = IntArray(1)
                check(
                    EGL14.eglChooseConfig(
                        display,
                        intArrayOf(
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_ALPHA_SIZE, 8,
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                            EGL14.EGL_NONE
                        ),
                        0, pbufferConfigs, 0, 1, pbufferCount, 0
                    ) && pbufferCount[0] > 0
                ) { "configuração EGL pbuffer indisponível" }
                val pbufferConfig = requireNotNull(pbufferConfigs[0])
                ingestContext = EGL14.eglCreateContext(display, pbufferConfig, shareContext, contextAttrs, 0)
                check(ingestContext != EGL14.EGL_NO_CONTEXT) { "falha criando contexto EGL de recepção" }
                pbuffer = EGL14.eglCreatePbufferSurface(
                    display, pbufferConfig,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
                )
                check(pbuffer != EGL14.EGL_NO_SURFACE) { "falha criando pbuffer de recepção" }
                check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, ingestContext)) {
                    "falha ativando contexto EGL de recepção"
                }
            }

            externalTexture = createExternalTexture()
            st = SurfaceTexture(externalTexture).apply {
                setDefaultBufferSize(sourceWidth, sourceHeight)
                setOnFrameAvailableListener(
                    {
                        val nowSignalNs = System.nanoTime()
                        cameraSignals++
                        if (lastSignalNs != 0L) {
                            val signalGap = nowSignalNs - lastSignalNs
                            if (signalGap > signalMaxGapNs) signalMaxGapNs = signalGap
                            if (signalGap > 25_000_000L) signalLongGaps++
                        }
                        lastSignalNs = nowSignalNs
                        pendingFrames.updateAndGet { pending ->
                            if (pending >= MAX_PENDING_SIGNAL_COUNT) pending else pending + 1
                        }
                        LockSupport.unpark(renderThreadRef)
                    },
                    frameSignalHandler
                )
            }
            surfaceTexture = st
            camera = Surface(st)
            cameraSurface = camera

            // Framebuffers não são compartilhados entre contextos: esta thread cria os seus.
            for (slot in ring) slot.framebuffer = createFramebuffer(slot.texture)
            externalProgram = createProgram(VERTEX_SHADER_EXTERNAL, VideoLook.insert(FRAGMENT_SHADER_EXTERNAL, lookProfile))
            ingestProgram = createProgram(VERTEX_SHADER_EXTERNAL, FRAGMENT_SHADER_INGEST)
            // Movimento global (panorâmica) para alinhar o histórico do filtro temporal. Se a GPU
            // não suportar, o filtro continua como antes (sem alinhamento).
            gmc = GlobalMotionEstimator(outputWidth, outputHeight)
            if (!ORIGINAL_IMAGE) gmc.initialize()
            Log.i(
                "SteadyVaultCfr",
                if (ORIGINAL_IMAGE) "imagem: ORIGINAL (sem denoise)"
                else "imagem: DENOISE constante ligado; movimento global: ${if (gmc.available) "ativo" else "indisponível"}"
            )
            val gmcShift = FloatArray(2)
            var gmcSamples = 0L
            var gmcMoving = 0L
            var gmcMaxShift = 0f
            var frameCounter = 0L

            // Frame de análise do foco inteligente: orientação do SENSOR (o consumidor rotaciona).
            val analysisWidth = (sourceWidth / 8).coerceIn(240, 480)
            val analysisHeight = ((analysisWidth.toLong() * sourceHeight.toLong()) / sourceWidth.coerceAtLeast(1).toLong())
                .toInt().coerceIn(90, 270)
            val motionReadback = ByteBuffer.allocateDirect(analysisWidth * analysisHeight * 4)
                .order(ByteOrder.nativeOrder())
            val currentMotionPixels = ByteArray(analysisWidth * analysisHeight * 4)



            GLES20.glGenTextures(1, analysisTexture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                analysisWidth, analysisHeight, 0,
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



            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)
            val source = requireNotNull(st)
            // Último frame trazido para o anel (já com a redução de ruído): histórico do filtro
            // temporal. Nunca é escolhido como destino enquanto for o histórico.
            var lastIngested: Slot? = null
            var lastAnalysisSampleMs = 0L
            var ingestSequence = 0L
            var seenGeneration = generation.get()

            fun drainPending() {
                val stale = pendingFrames.getAndSet(0)
                var consumed = 0
                while (consumed < stale) {
                    runCatching { source.updateTexImage() }
                    consumed++
                }
            }

            latch.countDown()

            while (!released.get() && !pipelineStopped) {
                if (!outputEnabled.get()) {
                    drainPending()
                    lastIngested = null
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                val generationNow = generation.get()
                if (generationNow != seenGeneration) {
                    seenGeneration = generationNow
                    drainPending()
                    lastIngested = null
                }
                if (pendingFrames.get() <= 0) {
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                var destination = freeSlots.poll()
                if (destination != null && destination === lastIngested) {
                    val other = freeSlots.poll()
                    freeSlots.add(destination)
                    destination = other
                }
                if (destination == null) {
                    ringFullWaits++
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }

                val ingestStartNs = System.nanoTime()
                if (lastIngestStartNs != 0L) {
                    val stall = ingestStartNs - lastIngestStartNs
                    if (stall > ingestStallMaxNs) ingestStallMaxNs = stall
                    if (stall > 25_000_000L) lateIngests++
                }
                lastIngestStartNs = ingestStartNs
                pendingFrames.decrementAndGet()
                source.updateTexImage()
                destination.timestampNs = source.timestamp
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destination.framebuffer)
                renderIngest(
                    st = source,
                    textureMatrix = textureMatrix,
                    program = ingestProgram,
                    externalTexture = externalTexture,
                    historyTexture = lastIngested?.texture ?: 0,
                    vertices = vertices,
                    texCoords = texCoords,
                    targetFramebuffer = destination.framebuffer,
                    motion = gmc
                )
                frameCounter++
                val estimator = gmc
                if (estimator != null && estimator.available && frameCounter % 60L == 0L) {
                    // Telemetria: 1 pixel por segundo.
                    estimator.readShift(gmcShift)
                    gmcSamples++
                    val mag = kotlin.math.hypot(gmcShift[0], gmcShift[1])
                    if (mag >= 1f) gmcMoving++
                    if (mag > gmcMaxShift) gmcMaxShift = mag
                    Log.i("SteadyVaultCfr", "movimento global: dx=%.1f dy=%.1f px/frame (amostras=%d comMovimento=%d máx=%.1f)".format(gmcShift[0], gmcShift[1], gmcSamples, gmcMoving, gmcMaxShift))
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                // O slot vai ser lido pelo contexto de saída: a cópia precisa ter terminado.
                // Cerca EGL (padrão para compartilhar texturas entre contextos); se a cerca não
                // existir, glFinish.
                val fence: android.opengl.EGLSync? = try {
                    EGL15.eglCreateSync(
                        display, EGL15.EGL_SYNC_FENCE, longArrayOf(EGL14.EGL_NONE.toLong()), 0
                    )
                } catch (_: Throwable) {
                    null
                }
                if (fence != null) {
                    GLES20.glFlush()
                    destination.fence = fence
                } else {
                    fenceFallbacks++
                    GLES20.glFinish()
                    destination.fence = null
                }
                destination.sequence = ++ingestSequence
                lastIngested = destination
                frameQueue.add(destination)
                val ingestNs = System.nanoTime() - ingestStartNs
                ingestTotalNs += ingestNs
                ingestCount++
                if (ingestNs > ingestMaxNs) ingestMaxNs = ingestNs

                if (analysisEnabled && onAnalysisFrame != null) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastAnalysisSampleMs >= analysisIntervalMs) {
                        lastAnalysisSampleMs = nowMs
                        readCurrentAnalysisFrame(
                            st = source,
                            externalTexture = externalTexture,
                            framebuffer = analysisFramebuffer[0],
                            width = analysisWidth,
                            height = analysisHeight,
                            target = currentMotionPixels,
                            readback = motionReadback,
                            program = externalProgram,
                            vertices = vertices,
                            texCoords = texCoords,
                            textureMatrix = textureMatrix
                        )
                        runCatching {
                            onAnalysisFrame?.invoke(currentMotionPixels.copyOf(), analysisWidth, analysisHeight)
                        }
                    }
                }

            }
        } catch (t: Throwable) {
            initError = t
            pipelineStopped = true
            if (!released.get()) onError(t)
        } finally {
            if (latch.count > 0L) latch.countDown()
            runCatching { camera?.release() }
            cameraSurface = null
            runCatching { st?.release() }
            surfaceTexture = null
            if (externalProgram != 0) runCatching { GLES20.glDeleteProgram(externalProgram) }
            if (ingestProgram != 0) runCatching { GLES20.glDeleteProgram(ingestProgram) }
            runCatching { gmc?.release() }
            runCatching { GLES20.glDeleteTextures(1, analysisTexture, 0) }
            runCatching { GLES20.glDeleteFramebuffers(1, analysisFramebuffer, 0) }
            for (slot in ring) {
                if (slot.framebuffer != 0) runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(slot.framebuffer), 0) }
            }
            if (externalTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(externalTexture), 0) }
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (pbuffer != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, pbuffer) }
            if (ingestContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, ingestContext) }
            runCatching { EGL14.eglReleaseThread() }
        }
    }


    @Volatile private var matrixLogged = false

    /**
     * Escolhe a rotação de UV (0/90/180/270, horária) que, composta com a matriz do
     * SurfaceTexture, deixa a imagem na orientação pedida por [hintDegrees] (90 ou 270,
     * mesma convenção de MediaMuxer.setOrientationHint).
     *
     * Não assume que a matriz seja só um flip-Y: alguns drivers embutem rotação nela,
     * e aplicar a rotação do sensor por cima disso espreme a imagem paisagem
     * dentro do quadro retrato.
     */
    private fun resolveShaderRotation(m: FloatArray, hintDegrees: Int): Int {
        // Parte linear 2x2 (column-major): x' = m0*u + m4*v ; y' = m1*u + m5*v
        val a = m[0]
        val b = m[4]
        val c = m[1]
        val d = m[5]
        // Alvo em coordenadas de textura do buffer (linha 0 = topo), row-major [00,01,10,11].
        val target = if (hintDegrees == 270) {
            floatArrayOf(0f, 1f, 1f, 0f)
        } else {
            floatArrayOf(0f, -1f, -1f, 0f)
        }
        var best = hintDegrees
        var bestError = Float.MAX_VALUE
        for ((degrees, f) in ROTATION_CANDIDATES) {
            val error =
                abs(a * f[0] + b * f[2] - target[0]) +
                        abs(a * f[1] + b * f[3] - target[1]) +
                        abs(c * f[0] + d * f[2] - target[2]) +
                        abs(c * f[1] + d * f[3] - target[3])
            if (error < bestError - 1e-4f) {
                bestError = error
                best = degrees
            }
        }
        if (!matrixLogged) {
            matrixLogged = true
            Log.i(
                "SteadyVaultCfr",
                "SurfaceTexture matrix=[${a}, ${b}; ${c}, ${d}] hint=$hintDegrees " +
                        "-> shaderRotation=$best erroResidual=$bestError " +
                        "src=${sourceWidth}x$sourceHeight out=${outputWidth}x$outputHeight"
            )
            if (bestError > 0.01f) {
                Log.w("SteadyVaultCfr", "matriz do SurfaceTexture espelhada/inesperada; orientação pode sair errada")
            }
        }
        return best
    }

    /**
     * Copia o frame da câmera (OES, com matriz e rotação) para o slot, com redução de ruído
     * temporal recursiva: onde o frame quase não mudou em relação ao anterior já filtrado, os
     * dois são misturados (ruído cai ~metade em cena parada); onde mudou (movimento), passa o
     * frame novo intacto, sem rastro.
     */
    private fun renderIngest(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        historyTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        targetFramebuffer: Int,
        motion: GlobalMotionEstimator?
    ) {
        st.getTransformMatrix(textureMatrix)
        val shaderRotation = if (physicalRotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, physicalRotationDegrees)
        val motionActive = !ORIGINAL_IMAGE && motion != null && motion.available
        if (motionActive) {
            motion!!.estimate(
                externalTexture = externalTexture,
                textureMatrix = textureMatrix,
                rotationDegrees = shaderRotation,
                hasPrevious = historyTexture != 0
            ) { quadProgram -> bindQuad(quadProgram, vertices, texCoords) }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer)
        }
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        bindQuad(program, vertices, texCoords)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTextureMatrix"), 1, false, textureMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotationDegrees"), shaderRotation.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        // Sem histórico (primeiro frame): peso 0, e uma textura qualquer só para o sampler ficar válido.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (historyTexture != 0) historyTexture else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sHistory"), 1)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uTexel"),
            SPATIAL_DENOISE_RADIUS_PX / outputWidth,
            SPATIAL_DENOISE_RADIUS_PX / outputHeight
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uHistoryWeight"),
            if (historyTexture != 0) 1f else 0f
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (motionActive) motion!!.motionTexture else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sMotion"), 2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMotionOn"), if (motionActive && historyTexture != 0) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShiftScale"), GlobalMotionEstimator.DOWNSCALE.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShiftRadius"), GlobalMotionEstimator.RADIUS.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uOutTexel"), 1f / outputWidth, 1f / outputHeight)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uNoiseScale"), SensorNoiseHint.motionGateScale().coerceAtLeast(NOISE_SCALE_FLOOR))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPassthrough"), if (ORIGINAL_IMAGE) 1f else 0f)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uMaxAge"),
            (TEMPORAL_MAX_AGE + 4f * (SensorNoiseHint.motionGateScale().coerceAtLeast(NOISE_SCALE_FLOOR) - 1f)).coerceIn(TEMPORAL_MAX_AGE, TEMPORAL_MAX_AGE_HIGH_ISO)
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Frame real já no espaço de saída (textura 2D do anel) direto para o encoder, com o look. */
    private fun renderTextureToEncoder(
        program: Int,
        texture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        bindQuad(program, vertices, texCoords)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), if (APPLY_LOOK) 1f else 0f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / outputWidth,
            VideoLook.RADIUS_PX / outputHeight
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        swapBuffers(display, window, "eglSwapBuffers falhou")
    }

    private fun bindQuad(program: Int, vertices: FloatBuffer, texCoords: FloatBuffer) {
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
    }


    /** Rede de segurança (sem movimento): crossfade temporal entre dois frames do anel + look. */
    private fun renderBlendToEncoder(
        program: Int,
        previousTexture: Int,
        currentTexture: Int,
        alpha: Float,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        bindQuad(program, vertices, texCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sPrevious"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sCurrent"), 1)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / outputWidth,
            VideoLook.RADIUS_PX / outputHeight
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), if (APPLY_LOOK) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAlpha"), alpha.coerceIn(0.001f, 0.999f))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        swapBuffers(display, window, "eglSwapBuffers interpolado falhou")
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotationDegrees"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), 0f)
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
        program: Int,
        previousTexture: Int,
        currentTexture: Int,
        framebuffer: Int,
        motionWidth: Int,
        motionHeight: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, motionWidth, motionHeight)
        GLES20.glUseProgram(program)
        bindQuad(program, vertices, texCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sPrevious"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
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
     * Gera a saída [alpha] por compensação de movimento. Retorna false (sem ter trocado
     * buffers) se algo falhou; o chamador desenha o crossfade no lugar e o caminho de
     * movimento é desligado de vez para não repetir o erro a 60 vezes por segundo.
     */
    private fun renderMotionToEncoder(
        mi: MotionInterpolator,
        previousTexture: Int,
        currentTexture: Int,
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
            if (!flowReady) {
                val flowStartNs = System.nanoTime()
                mi.prepare(previousTexture, currentTexture, vertices, texCoords)
                mi.estimate(vertices, texCoords)
                if (!mi.healthy()) throw IllegalStateException("erro GL na estimativa")
                if (TIMING_DIAGNOSTICS) {
                    GLES20.glFinish() // só em lacunas: mede o tempo real de GPU do fluxo
                    val flowNs = System.nanoTime() - flowStartNs
                    if (flowNs > estimateMaxNs) estimateMaxNs = flowNs
                }
            }
            val warpStartNs = System.nanoTime()
            // Com o Super Estável, os frames reais saem recortados; o frame recriado usa o
            // mesmo recorte para não "pulsar" de zoom a cada lacuna.
            val crop = if (superStabilizationEnabled) SUPER_STABLE_CROP_SCALE else 1f
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            mi.warp(alpha, previousTexture, currentTexture, vertices, texCoords, crop)
            if (!mi.healthy()) throw IllegalStateException("erro GL no warp")
            if (TIMING_DIAGNOSTICS) {
                GLES20.glFinish()
                val warpNs = System.nanoTime() - warpStartNs
                if (warpNs > warpMaxNs) warpMaxNs = warpNs
            }
        } catch (t: Throwable) {
            Log.w(TAG, "interpolação com movimento falhou, voltando ao crossfade: ${t.message}")
            motionState = "desligadoPorErro"
            mi.release()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        swapBuffers(display, window, "eglSwapBuffers com movimento falhou")
        motionFrames++
        return true
    }

    private fun renderSuperStableToEncoder(
        program: Int,
        currentTexture: Int,
        motionTexture: Int,
        motionWidth: Int,
        motionHeight: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        bindQuad(program, vertices, texCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTexture)
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), if (APPLY_LOOK) 1f else 0f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / outputWidth,
            VideoLook.RADIUS_PX / outputHeight
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        swapBuffers(display, window, "eglSwapBuffers Super Estável falhou")
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

        /**
         * Texturas do anel (4K RGBA ~33 MB cada): frame anterior + frame em processamento +
         * fila de frames que chegam enquanto uma lacuna longa é preenchida.
         */
        private const val RING_SIZE = 8

        /** Peso máximo do frame anterior filtrado no denoise temporal (0 desliga). */
        // Raio (px de saída) das 4 amostras diagonais do filtro espacial anti-grão do ingest.
        private const val SPATIAL_DENOISE_RADIUS_PX = 1f
        // Teto da idade de confiança: peso máximo = idade/(idade+1) = 5/6 (~0,83) em área parada.
        private const val TEMPORAL_MAX_AGE = 5f
        // Força mínima constante do denoise: o grão não pode aparecer e sumir conforme o ISO/cena.
        private const val NOISE_SCALE_FLOOR = 1.5f
        private const val TEMPORAL_MAX_AGE_HIGH_ISO = 8f

        /**
         * Vídeo ORIGINAL: o filtro de granulado (espacial + temporal), o alinhamento por movimento
         * global e o "look" (nitidez/saturação/curva) ficam desligados; o que vai para o encoder é
         * o pixel da câmera. Continuam: reamostragem CFR por timestamp (frames sem repetir) e
         * foco inteligente. Troque para false para voltar ao processamento.
         */
        private const val ORIGINAL_IMAGE = true

        /** Sem reamostragem CFR: nenhum frame é criado, repetido ou descartado (PTS = tempo do sensor). */
        private const val PURE_FRAMES = true
        private const val APPLY_LOOK = VideoLook.ENABLED

        /** glFinish em lacunas para medir o tempo de GPU (log "tempos:"). Desligue depois do diagnóstico. */
        private const val TIMING_DIAGNOSTICS = false
        // Limitador do preenchimento com movimento (ver o laço de renderização).
        private const val FENCE_TIMEOUT_NS = 100_000_000L
        private const val MOTION_MAX_BACKLOG = 7
        private const val FILL_LOG_LIMIT = 40
        private const val MOTION_BUDGET_NS = 250_000_000L
        private const val MOTION_COOLDOWN_NS = 150_000_000L

        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val PREPARE_TIMEOUT_SECONDS = 5L
        private const val RELEASE_TIMEOUT_MS = 1_000L
        private const val IDLE_POLL_NS = 1_000_000L
        private const val MAX_PENDING_SIGNAL_COUNT = 8
        private const val TAG = "SteadyVaultCfr"

        /** Acima disso (lacuna longa) o movimento entre os frames reais já não é estimável: crossfade. */
        private const val MAX_MOTION_OUTPUTS = 4



        // Rotações de UV horárias como matriz row-major [00,01,10,11].
        private val ROTATION_CANDIDATES = listOf(
            0 to floatArrayOf(1f, 0f, 0f, 1f),
            90 to floatArrayOf(0f, -1f, 1f, 0f),
            180 to floatArrayOf(-1f, 0f, 0f, -1f),
            270 to floatArrayOf(0f, 1f, -1f, 0f)
        )

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

        // Os shaders de saída só repassam o UV; rotação e matriz são aplicadas no fragment
        // para amostrar também os vizinhos do filtro de nitidez (VideoLook).
        private const val VERTEX_SHADER_EXTERNAL = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aTexCoord.xy;
            }
        """

        private const val FRAGMENT_SHADER_EXTERNAL = """
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
            uniform float uLook;
            LOOK_GLSL
            vec2 rotateUv(vec2 uv) {
                // Rotação horária (mesma convenção de MediaMuxer.setOrientationHint).
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);        // 270°
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);  // 180°
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);         // 90°
                return uv;
            }
            vec3 fetchColor(vec2 uv) {
                return texture2D(sTexture, (uTextureMatrix * vec4(rotateUv(uv), 0.0, 1.0)).xy).rgb;
            }
            void main() {
                vec3 c = fetchColor(vUv);
                if (uLook > 0.5) {
                    vec2 dx = vec2(uLookTexel.x, 0.0);
                    vec2 dy = vec2(0.0, uLookTexel.y);
                    vec3 blur = 0.25 * (fetchColor(vUv + dx) + fetchColor(vUv - dx) +
                                        fetchColor(vUv + dy) + fetchColor(vUv - dy));
                    c = lookGrade(c, blur);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """

        private const val VERTEX_SHADER_BLEND = VERTEX_SHADER_EXTERNAL


        // Cópia câmera -> anel com denoise temporal recursivo, gated por movimento.
        private const val FRAGMENT_SHADER_INGEST = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform samplerExternalOES sTexture;
            uniform sampler2D sHistory;
            uniform mat4 uTextureMatrix;
            uniform float uRotationDegrees;
            uniform float uHistoryWeight;   // 0 = sem histórico (primeiro frame)
            uniform float uNoiseScale;      // escala do limiar de movimento conforme o ISO
            uniform float uMaxAge;          // teto da "idade" de confiança (frames parados)
            uniform vec2 uTexel;
            uniform sampler2D sMotion;      // 1x1: deslocamento global (dx,dy) em 16 bits
            uniform float uMotionOn;        // 1 = alinhar o histórico pelo movimento global
            uniform float uShiftScale;      // pixels de saída por pixel pequeno
            uniform float uShiftRadius;
            uniform vec2 uOutTexel;
            uniform float uPassthrough;     // 1 = vídeo original: cópia pura, sem denoise
            vec2 rotateUv(vec2 uv) {
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);
                return uv;
            }
            vec3 fetchColor(vec2 uv) {
                return texture2D(sTexture, (uTextureMatrix * vec4(rotateUv(uv), 0.0, 1.0)).xy).rgb;
            }
            float lumaOf(vec3 v) { return dot(v, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec3 c0 = fetchColor(vUv);
                if (uPassthrough > 0.5) {
                    gl_FragColor = vec4(c0, 1.0 / 16.0);
                    return;
                }
                // Filtro espacial que preserva bordas (4 diagonais): o croma (colorido do grão) é
                // suavizado com força; a luma só levemente, para não perder textura fina.
                float y0 = lumaOf(c0);
                vec3 accC = c0; float wC = 1.0;
                vec3 accL = c0; float wL = 1.0;
                float curLuma = y0;
                // Limiares espaciais crescem com o ISO (mais ruído: vizinhos ruidosos não podem ser
                // rejeitados como "borda"); nunca abaixo do valor base.
                float spScale = max(uNoiseScale, 1.0);
                // Histórico alinhado: o pixel p do frame atual estava em p - d no anterior (d inteiro,
                // em pixels de saída, para amostrar sempre no centro do texel, sem borrar).
                vec4 m = texture2D(sMotion, vec2(0.5));
                vec2 d16 = vec2(m.r * 255.0 * 256.0 + m.g * 255.0, m.b * 255.0 * 256.0 + m.a * 255.0);
                vec2 shiftPx = floor((d16 / 65535.0 * 2.0 * uShiftRadius - uShiftRadius) * uShiftScale + 0.5) * uMotionOn;
                vec2 hUv = vUv - shiftPx * uOutTexel;
                vec2 edge = uTexel * 1.5;
                float inside = step(edge.x, hUv.x) * step(hUv.x, 1.0 - edge.x) * step(edge.y, hUv.y) * step(hUv.y, 1.0 - edge.y);
                float histLuma = lumaOf(texture2D(sHistory, hUv).rgb);
                for (int i = 0; i < 4; i++) {
                    vec2 o = vec2((i < 2) ? -1.0 : 1.0, (i == 0 || i == 2) ? -1.0 : 1.0) * uTexel;
                    vec3 n = fetchColor(vUv + o);
                    float yn = lumaOf(n);
                    float dn = abs(yn - y0);
                    float a = 1.0 - smoothstep(0.03 * spScale, 0.10 * spScale, dn);
                    float b = 0.65 * (1.0 - smoothstep(0.02 * spScale, 0.06 * spScale, dn));
                    accC += n * a; wC += a;
                    accL += n * b; wL += b;
                    curLuma += yn;
                    histLuma += lumaOf(texture2D(sHistory, hUv + o).rgb);
                }
                vec3 cf = accC / wC;
                vec3 lf = accL / wL;
                vec3 c = cf + vec3(lumaOf(lf) - lumaOf(cf));

                vec4 hist = texture2D(sHistory, hUv);
                // Movimento medido na média de 5 amostras (ruído ~2,2x menor que pixel a pixel,
                // menos falso "movimento"), com limiar proporcional ao ruído do ISO.
                float d = abs(curLuma - histLuma) * 0.2;
                float still = 1.0 - smoothstep(0.025 * uNoiseScale, 0.07 * uNoiseScale, d);
                // Confiança "Kalman" por pixel: quantos frames seguidos ele ficou parado (alfa).
                float age = hist.a * 16.0;
                still *= inside;
                float w = uHistoryWeight * still * (age / (age + 1.0));
                float newAge = min(uMaxAge, (age + 1.0) * still);
                if (uHistoryWeight < 0.5) newAge = 1.0;
                gl_FragColor = vec4(mix(c, hist.rgb, w), newAge / 16.0);
            }
        """

        // Frame real já no espaço de saída (textura 2D do anel) com o look.
        private const val FRAGMENT_SHADER_TEXTURE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D sTexture;
            uniform float uLook;
            LOOK_GLSL
            void main() {
                vec3 c = texture2D(sTexture, vUv).rgb;
                if (uLook > 0.5) {
                    vec2 dx = vec2(uLookTexel.x, 0.0);
                    vec2 dy = vec2(0.0, uLookTexel.y);
                    vec3 blur = 0.25 * (texture2D(sTexture, vUv + dx).rgb + texture2D(sTexture, vUv - dx).rgb +
                                        texture2D(sTexture, vUv + dy).rgb + texture2D(sTexture, vUv - dy).rgb);
                    c = lookGrade(c, blur);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """

        // Rede de segurança (sem movimento): crossfade temporal entre dois frames do anel + look.
        private const val FRAGMENT_SHADER_BLEND = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D sPrevious;
            uniform sampler2D sCurrent;
            uniform float uAlpha;
            uniform float uLook;
            LOOK_GLSL
            vec3 composite(vec2 uv) {
                return mix(texture2D(sPrevious, uv).rgb, texture2D(sCurrent, uv).rgb, uAlpha);
            }
            void main() {
                if (uLook < 0.5) {
                    gl_FragColor = vec4(composite(vUv), 1.0);
                    return;
                }
                vec2 dx = vec2(uLookTexel.x, 0.0);
                vec2 dy = vec2(0.0, uLookTexel.y);
                vec3 blur = 0.25 * (composite(vUv + dx) + composite(vUv - dx) +
                                    composite(vUv + dy) + composite(vUv - dy));
                gl_FragColor = vec4(lookGrade(composite(vUv), blur), 1.0);
            }
        """

        private const val GPU_SEARCH_RADIUS_TEXELS = 3.0f

        private const val FRAGMENT_SHADER_GPU_MOTION_ESTIMATE = """
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D sPrevious;
            uniform sampler2D sCurrent;
            uniform vec2 uMotionTexel;
            uniform float uSearchRadius;
            vec3 fetchCurrent(vec2 uv) {
                return texture2D(sCurrent, uv).rgb;
            }
            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float patchError(vec2 prevUv, vec2 currUv, vec2 offset) {
                vec2 tx = vec2(uMotionTexel.x, 0.0);
                vec2 ty = vec2(0.0, uMotionTexel.y);
                float e = 0.0;
                e += abs(luma(texture2D(sPrevious, clamp(prevUv,0.0,1.0)).rgb) -
                         luma(fetchCurrent(clamp(currUv+offset,0.0,1.0))));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv+tx,0.0,1.0)).rgb) -
                              luma(fetchCurrent(clamp(currUv+offset+tx,0.0,1.0))));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv-tx,0.0,1.0)).rgb) -
                              luma(fetchCurrent(clamp(currUv+offset-tx,0.0,1.0))));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv+ty,0.0,1.0)).rgb) -
                              luma(fetchCurrent(clamp(currUv+offset+ty,0.0,1.0))));
                e += 0.55*abs(luma(texture2D(sPrevious, clamp(prevUv-ty,0.0,1.0)).rgb) -
                              luma(fetchCurrent(clamp(currUv+offset-ty,0.0,1.0))));
                return e / 3.2;
            }

            void consider(inout float bestError, inout vec2 bestOffset, vec2 candidate) {
                float e = patchError(vUv, vUv, candidate);
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
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D sCurrent;
            uniform sampler2D sMotion;
            uniform vec2 uMotionTexel;
            uniform float uSearchRadius;
            uniform float uStrength;
            uniform float uCropScale;
            uniform float uLook;
            vec3 fetchCurrent(vec2 uv) {
                return texture2D(sCurrent, uv).rgb;
            }
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

                vec2 cropped = vec2(0.5) + (vUv - vec2(0.5)) * uCropScale;
                vec2 stabilizedUv = clamp(cropped + m * uStrength, 0.001, 0.999);
                vec3 c = fetchCurrent(stabilizedUv);
                if (uLook > 0.5) {
                    vec2 dx = vec2(uLookTexel.x, 0.0) * uCropScale;
                    vec2 dy = vec2(0.0, uLookTexel.y) * uCropScale;
                    vec3 blur = 0.25 * (fetchCurrent(clamp(stabilizedUv + dx, 0.001, 0.999)) +
                                        fetchCurrent(clamp(stabilizedUv - dx, 0.001, 0.999)) +
                                        fetchCurrent(clamp(stabilizedUv + dy, 0.001, 0.999)) +
                                        fetchCurrent(clamp(stabilizedUv - dy, 0.001, 0.999)));
                    c = lookGrade(c, blur);
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
