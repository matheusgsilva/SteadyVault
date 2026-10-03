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
 * Ponte GPU da gravação PURA.
 *
 * Camera2 escreve em uma SurfaceTexture OES. Cada frame da câmera é copiado, assim que chega,
 * para um anel de texturas 2D já no espaço de saída (retrato), o que devolve o buffer à câmera
 * na hora, e daí segue para o encoder com o instante REAL do sensor como PTS. Um frame da
 * câmera é exatamente um frame no arquivo: nada é criado, repetido, descartado, filtrado ou
 * colorido.
 *
 * O único readback é opcional e esparso: alimenta o foco inteligente e a AE própria em
 * background; ele não altera o vídeo.
 */
class RealTimeCfrSurfaceBridge(
    private val encoderSurface: Surface,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val physicalRotationDegrees: Int,
    private val fps: Int,
    private val analysisEnabled: Boolean = false,
    private val onAnalysisFrame: ((ByteArray, Int, Int) -> Unit)? = null,
    private val analysisIntervalMs: Long = 700L,
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
    @Volatile private var maxIntervalNs = 0L
    @Volatile private var firstSensorNs = 0L
    @Volatile private var lastSensorSeenNs = 0L
    private val intervalBuckets = LongArray(5)
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
        worstFrameMs = worstFrameNs / 1_000_000L,
        maxBacklogSignals = maxBacklogSignals
    )

    // Telemetria de tempo (ms) por etapa; aparece em "tempos:" no resumo.
    @Volatile private var swapMaxNs = 0L
    @Volatile private var swapTotalNs = 0L
    @Volatile private var swapCount = 0L
    @Volatile private var ingestMaxNs = 0L
    @Volatile private var ingestTotalNs = 0L
    @Volatile private var ingestCount = 0L
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
        val spanNs = lastSensorSeenNs - firstSensorNs
        val deliveredFps = if (spanNs > 0L && stats.realFrames > 1L) {
            (stats.realFrames - 1L) * 1_000_000_000.0 / spanNs.toDouble()
        } else 0.0
        Log.i(
            TAG,
            "FPS do sensor: entregue=%.2f nominal=%d (%.1f%%) quadros=%d".format(
                deliveredFps, fps, if (fps > 0) 100.0 * deliveredFps / fps else 0.0, stats.realFrames
            )
        )
        Log.i(
            TAG,
            "resumo ponte: reais=${stats.realFrames} piorFrame=${stats.worstFrameMs}ms " +
                "filaMax=${stats.maxBacklogSignals} foraDeOrdem=$orderViolations semCerca=$fenceFallbacks " +
                "fps=$fps saida=${outputWidth}x$outputHeight"
        )
        fun ms(ns: Long) = ns / 1_000_000L
        Log.i(
            TAG,
            "tempos(ms): swapMax=${ms(swapMaxNs)} swapMedia=${ms(swapTotalNs / swapCount.coerceAtLeast(1L))} " +
                "ingestMax=${ms(ingestMaxNs)} ingestMedia=${ms(ingestTotalNs / ingestCount.coerceAtLeast(1L))} " +
                "anelCheio=$ringFullWaits swapsLentos(>20ms)=$slowSwaps"
        )
        // Se sinaisDaCamera ~ reais, a perda é ANTES da ponte (câmera/HAL). Se sinais >> consumidos,
        // a ponte/encoder atrasou. ingestTardios = vezes que o laço ficou >25 ms sem trazer frame.
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
        /** Pior tempo de processamento de um frame (cópia + encoder). */
        val worstFrameMs: Long = 0L,
        /** Maior fila de frames aguardando a thread GL. */
        val maxBacklogSignals: Int = 0
    )

    /** Devolve um slot ao anel depois que a GPU terminou de ler dele. */
    private fun releaseSlot(slot: Slot) {
        freeSlots.add(slot)
    }

    /**
     * Thread GL de SAÍDA: consome a fila de frames já prontos e entrega ao encoder com o PTS do
     * sensor. Pode bloquear em eglSwapBuffers (encoder cheio) sem afetar a câmera, porque a
     * recepção dos frames é feita por [ingestLoop] em outra thread/contexto.
     */
    private fun renderLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var window = EGL14.EGL_NO_SURFACE
        val ring = ArrayList<Slot>()
        var textureProgram = 0
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
            textureProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_TEXTURE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glFinish()

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)

            // Thread de recepção: SurfaceTexture, cópia câmera -> anel e análise em background.
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
            var lastSensorNs = 0L
            var timelineStarted = false
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

                if (!timelineStarted) {
                    // O primeiro frame ancora o PTS no relógio; os seguintes seguem o sensor.
                    pureBaseSensorNs = current.timestampNs
                    pureBasePtsNs = System.nanoTime()
                    firstSensorNs = current.timestampNs
                    renderTextureToEncoder(
                        program = textureProgram,
                        texture = current.texture,
                        vertices = vertices,
                        texCoords = texCoords,
                        display = display,
                        window = window,
                        presentationTimeNs = pureBasePtsNs
                    )
                    realFrames++
                    lastSensorNs = current.timestampNs
                    giveBack(current)
                    timelineStarted = true
                    continue
                }

                // O mesmo timestamp nunca pode virar um segundo frame no arquivo.
                if (current.timestampNs <= lastSensorNs) {
                    releaseSlot(current)
                    continue
                }

                // GRAVAÇÃO PURA: um frame da câmera = um frame no arquivo, com o instante real do
                // sensor como PTS. Nada de frame criado, repetido ou descartado.
                recordSensorInterval(current.timestampNs - lastSensorNs, frameIntervalNs)
                renderTextureToEncoder(
                    program = textureProgram,
                    texture = current.texture,
                    vertices = vertices,
                    texCoords = texCoords,
                    display = display,
                    window = window,
                    presentationTimeNs = pureBasePtsNs + (current.timestampNs - pureBaseSensorNs)
                )
                realFrames++
                lastSensorNs = current.timestampNs
                lastSensorSeenNs = current.timestampNs
                giveBack(current)
                val frameNs = System.nanoTime() - frameStartNs
                if (frameNs > worstFrameNs) worstFrameNs = frameNs
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
     * anel assim que chega e o enfileira para a thread de saída, de modo que nada que bloqueie
     * a saída (encoder cheio) segure os buffers da câmera.
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
            externalProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXTERNAL)
            Log.i(TAG, "imagem: ORIGINAL (sem filtro, sem cor); 1 frame da câmera = 1 frame no arquivo")
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
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                val generationNow = generation.get()
                if (generationNow != seenGeneration) {
                    seenGeneration = generationNow
                    drainPending()
                }
                if (pendingFrames.get() <= 0) {
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }
                val destination = freeSlots.poll()
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
                    program = externalProgram,
                    externalTexture = externalTexture,
                    vertices = vertices,
                    texCoords = texCoords
                )
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
     * Copia o frame da câmera (OES, com matriz e rotação) para o slot, sem nenhum filtro: o
     * pixel que a câmera entregou é o pixel que vai para o arquivo.
     */
    private fun renderIngest(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        externalTexture: Int,
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Frame real já no espaço de saída (textura 2D do anel) direto para o encoder. */
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
         * Texturas do anel (4K RGBA ~33 MB cada): frames que chegam da câmera enquanto a thread
         * de saída ainda entrega o anterior ao encoder.
         */
        private const val RING_SIZE = 8

        private const val FENCE_TIMEOUT_NS = 100_000_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val PREPARE_TIMEOUT_SECONDS = 5L
        private const val RELEASE_TIMEOUT_MS = 1_000L
        private const val IDLE_POLL_NS = 1_000_000L
        private const val MAX_PENDING_SIGNAL_COUNT = 8
        private const val TAG = "SteadyVaultCfr"

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

        // Os shaders só repassam o UV; rotação e matriz são aplicadas no fragment.
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aTexCoord.xy;
            }
        """

        // Câmera (OES, com matriz e rotação) -> pixel cru. Usado na cópia para o anel e na amostra
        // de análise.
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
            vec2 rotateUv(vec2 uv) {
                // Rotação horária (mesma convenção de MediaMuxer.setOrientationHint).
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);        // 270°
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);  // 180°
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);         // 90°
                return uv;
            }
            void main() {
                vec3 c = texture2D(sTexture, (uTextureMatrix * vec4(rotateUv(vUv), 0.0, 1.0)).xy).rgb;
                gl_FragColor = vec4(c, 1.0);
            }
        """

        // Frame real já no espaço de saída (textura 2D do anel), sem alteração.
        private const val FRAGMENT_SHADER_TEXTURE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = vec4(texture2D(sTexture, vUv).rgb, 1.0);
            }
        """
    }
}
