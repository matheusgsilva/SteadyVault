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
    private val onError: (Throwable) -> Unit
) {
    /** Frame real já no espaço de saída, guardado numa textura 2D do anel. */
    private class Slot(val texture: Int, val framebuffer: Int) {
        var timestampNs = 0L
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
                "limitadosPorCusto=$motionThrottled"
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
        val ring = ArrayList<Slot>()
        var externalProgram = 0
        var textureProgram = 0
        var ingestProgram = 0
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

            // Anel de frames já no espaço de saída (retrato, cru). Cada frame da câmera é copiado
            // para cá assim que chega, liberando o buffer dela na hora; o processamento pesado
            // (lacunas) consome dessa fila sem fazer a câmera esperar.
            repeat(RING_SIZE) {
                val texture = createStorageTexture(outputWidth, outputHeight)
                ring += Slot(texture, createFramebuffer(texture))
            }
            externalProgram = createProgram(VERTEX_SHADER_EXTERNAL, VideoLook.insert(FRAGMENT_SHADER_EXTERNAL))
            textureProgram = createProgram(VERTEX_SHADER_EXTERNAL, VideoLook.insert(FRAGMENT_SHADER_TEXTURE))
            ingestProgram = createProgram(VERTEX_SHADER_EXTERNAL, FRAGMENT_SHADER_INGEST)
            blendProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_BLEND))
            motionEstimateProgram = createProgram(VERTEX_SHADER_BLEND, FRAGMENT_SHADER_GPU_MOTION_ESTIMATE)
            stabilizationProgram = createProgram(VERTEX_SHADER_BLEND, VideoLook.insert(FRAGMENT_SHADER_SUPER_STABILIZE))

            // Estimativa do Super Estável: no espaço de SAÍDA (retrato).
            val motionWidth = (outputWidth / 16).coerceIn(90, 320)
            val motionHeight = ((motionWidth.toLong() * outputHeight.toLong()) / outputWidth.coerceAtLeast(1).toLong())
                .toInt().coerceIn(90, 320)
            // Frame de análise do foco inteligente: orientação do SENSOR (o consumidor rotaciona).
            val analysisWidth = (sourceWidth / 16).coerceIn(160, 320)
            val analysisHeight = ((analysisWidth.toLong() * sourceHeight.toLong()) / sourceWidth.coerceAtLeast(1).toLong())
                .toInt().coerceIn(90, 240)
            val motionReadback = ByteBuffer.allocateDirect(analysisWidth * analysisHeight * 4)
                .order(ByteOrder.nativeOrder())
            val currentMotionPixels = ByteArray(analysisWidth * analysisHeight * 4)

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

            val interpolator = MotionInterpolator(outputWidth, outputHeight)
            motion = interpolator
            motionState = if (interpolator.initialize()) "ativo" else "indisponivel"
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)

            ready.countDown()

            val source = requireNotNull(st)
            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            val resampler = CfrTimeResampler(frameIntervalNs)
            val free = ArrayDeque<Slot>()
            ring.forEach { free.addLast(it) }
            val queue = ArrayDeque<Slot>()
            var previousSlot: Slot? = null
            // Último frame trazido para o anel (já com a redução de ruído): histórico do filtro temporal.
            var lastIngested: Slot? = null
            var timelineStarted = false
            var motionLightUntilNs = 0L
            var outputPtsNs = 0L
            var lastAnalysisSampleMs = 0L

            // Traz para o anel todo frame que a câmera já entregou. Chamada com frequência
            // (inclusive entre as saídas de uma lacuna) para a câmera nunca ficar sem buffer.
            fun ingest() {
                while (pendingFrames.get() > 0 && free.isNotEmpty()) {
                    val ingestStartNs = System.nanoTime()
                    if (lastIngestStartNs != 0L) {
                        val stall = ingestStartNs - lastIngestStartNs
                        if (stall > ingestStallMaxNs) ingestStallMaxNs = stall
                        if (stall > 25_000_000L) lateIngests++
                    }
                    lastIngestStartNs = ingestStartNs
                    pendingFrames.decrementAndGet()
                    source.updateTexImage()
                    val slot = free.removeFirst()
                    slot.timestampNs = source.timestamp
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
                    renderIngest(
                        st = source,
                        textureMatrix = textureMatrix,
                        program = ingestProgram,
                        externalTexture = externalTexture,
                        historyTexture = lastIngested?.texture ?: 0,
                        vertices = vertices,
                        texCoords = texCoords
                    )
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    lastIngested = slot
                    queue.addLast(slot)
                    if (queue.size > maxBacklogSignals) maxBacklogSignals = queue.size
                    val ingestNs = System.nanoTime() - ingestStartNs
                    ingestTotalNs += ingestNs
                    ingestCount++
                    if (ingestNs > ingestMaxNs) ingestMaxNs = ingestNs

                    if (analysisEnabled && onAnalysisFrame != null) {
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastAnalysisSampleMs >= BACKGROUND_ANALYSIS_INTERVAL_MS) {
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
            }

            // Descarta tudo o que está pendente (fora da gravação ou ao iniciá-la), para a fila
            // não chegar ao início do arquivo com frames velhos.
            fun discardAll() {
                val stale = pendingFrames.getAndSet(0)
                var consumed = 0
                while (consumed < stale) {
                    runCatching { source.updateTexImage() }
                    consumed++
                }
                while (queue.isNotEmpty()) free.addLast(queue.removeFirst())
                previousSlot?.let { free.addLast(it) }
                previousSlot = null
                lastIngested = null
                timelineStarted = false
            }

            while (!released.get()) {
                if (!outputEnabled.get()) {
                    discardAll()
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                if (flushRequested.compareAndSet(true, false)) discardAll()

                if (pendingFrames.get() > 0 && free.isEmpty()) ringFullWaits++
                ingest()
                val current = queue.removeFirstOrNull()
                if (current == null) {
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                val frameStartNs = System.nanoTime()
                val previous = previousSlot

                if (!timelineStarted || previous == null) {
                    resampler.start(current.timestampNs)
                    outputPtsNs = System.nanoTime()
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
                    previous?.let { free.addLast(it) }
                    previousSlot = current
                    timelineStarted = true
                    continue
                }

                // O mesmo timestamp nunca pode virar um segundo frame no arquivo.
                if (current.timestampNs <= previous.timestampNs) {
                    // O slot volta para a lista livre: não pode ficar como histórico do filtro.
                    if (lastIngested === current) lastIngested = previous
                    free.addLast(current)
                    continue
                }

                // Reamostragem pelo tempo EXATO de captura: cada saída (1/fps) usa o frame que
                // existiria naquele instante. Frame alinhado passa direto e nítido; frame
                // perdido vira interpolação com compensação de movimento.
                recordSensorInterval(current.timestampNs - previous.timestampNs, frameIntervalNs)
                val outputs = resampler.plan(previous.timestampNs, current.timestampNs)

                if (outputs == 0) {
                    // Câmera acima do FPS nominal: nenhum instante de saída cai neste frame.
                    droppedFrames++
                    free.addLast(previous)
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
                    (pendingFrames.get() + queue.size) < MOTION_MAX_BACKLOG
                var usedMotionThisFrame = false
                var flowReady = false
                var outputIndex = 0
                while (outputIndex < outputs) {
                    val alpha = resampler.alphaAt(outputIndex)
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
                    // Durante lacunas longas a GPU fica ocupada; recolhe os frames novos da
                    // câmera já, para ela não ficar sem buffer e perder mais frames.
                    if (outputIndex < outputs) ingest()
                }

                free.addLast(previous)
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
                for (slot in ring) {
                    runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(slot.framebuffer), 0) }
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0) }
                }
                if (textureProgram != 0) runCatching { GLES20.glDeleteProgram(textureProgram) }
                if (ingestProgram != 0) runCatching { GLES20.glDeleteProgram(ingestProgram) }
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

    private fun renderExternal(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        viewportWidth: Int = outputWidth,
        viewportHeight: Int = outputHeight,
        rotationDegrees: Int = physicalRotationDegrees,
        look: Boolean = false
    ) {
        st.getTransformMatrix(textureMatrix)
        val shaderRotation = if (rotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, rotationDegrees)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotationDegrees"), shaderRotation.toFloat())

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), if (look) 1f else 0f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uLookTexel"),
            VideoLook.RADIUS_PX / viewportWidth,
            VideoLook.RADIUS_PX / viewportHeight
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
        texCoords: FloatBuffer
    ) {
        st.getTransformMatrix(textureMatrix)
        val shaderRotation = if (physicalRotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, physicalRotationDegrees)
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
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(program, "uHistoryWeight"),
            if (historyTexture != 0) TEMPORAL_DENOISE_WEIGHT else 0f
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), 1f)
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

    private fun currentRotation(textureMatrix: FloatArray): Int =
        if (physicalRotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, physicalRotationDegrees)

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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLook"), 1f)
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
        private const val BACKGROUND_ANALYSIS_INTERVAL_MS = 1_500L

        /**
         * Texturas do anel (4K RGBA ~33 MB cada): frame anterior + frame em processamento +
         * fila de frames que chegam enquanto uma lacuna longa é preenchida.
         */
        private const val RING_SIZE = 6

        /** Peso máximo do frame anterior filtrado no denoise temporal (0 desliga). */
        private const val TEMPORAL_DENOISE_WEIGHT = 0.5f

        /** glFinish em lacunas para medir o tempo de GPU (log "tempos:"). Desligue depois do diagnóstico. */
        private const val TIMING_DIAGNOSTICS = false
        // Limitador do preenchimento com movimento (ver o laço de renderização).
        private const val MOTION_MAX_BACKLOG = 2
        private const val MOTION_BUDGET_NS = 40_000_000L
        private const val MOTION_COOLDOWN_NS = 400_000_000L

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

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

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
            uniform float uHistoryWeight;
            vec2 rotateUv(vec2 uv) {
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);
                return uv;
            }
            void main() {
                vec3 c = texture2D(sTexture, (uTextureMatrix * vec4(rotateUv(vUv), 0.0, 1.0)).xy).rgb;
                vec3 h = texture2D(sHistory, vUv).rgb;
                float d = abs(dot(c - h, vec3(0.299, 0.587, 0.114)));
                float w = uHistoryWeight * (1.0 - smoothstep(0.04, 0.10, d));
                gl_FragColor = vec4(mix(c, h, w), 1.0);
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
            LOOK_GLSL
            vec3 composite(vec2 uv) {
                return mix(texture2D(sPrevious, uv).rgb, texture2D(sCurrent, uv).rgb, uAlpha);
            }
            void main() {
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
