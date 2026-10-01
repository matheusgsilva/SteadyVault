package com.steadyvault.camera.capture.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
 * Frames reais são enviados sem warp geométrico. Quando a câmera perde um slot,
 * a main3 sintetiza somente o slot ausente por blend temporal GPU entre os frames
 * reais vizinhos. Não há repetição exata do último frame nem optical-flow warp.
 */
class RealTimeCfrSurfaceBridge(
    private val encoderSurface: Surface,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val physicalRotationDegrees: Int,
    private val fps: Int,
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
    @Volatile private var largestFillSlots = 0
    @Volatile private var worstFrameNs = 0L
    @Volatile private var maxBacklogSignals = 0
    @Volatile private var renderThreadRef: Thread? = null
    private val flushRequested = AtomicBoolean(false)
    private val summaryLogged = AtomicBoolean(false)

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
        // Zerar o contador aqui deixava frames antigos presos na fila do SurfaceTexture
        // (um buffer a menos para a câmera e um frame de atraso permanentes). A thread GL
        // esvazia a fila de verdade; o próximo frame real abre a timeline útil.
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
        maxBacklogSignals = maxBacklogSignals
    )

    private fun logSummary() {
        if (!summaryLogged.compareAndSet(false, true)) return
        val stats = stats()
        Log.i(
            TAG,
            "resumo CFR: reais=${stats.realFrames} preenchidos=${stats.interpolatedFrames} " +
                    "descartados=${stats.droppedFrames} maiorGap=${stats.largestFillSlots}slots " +
                    "piorFrame=${stats.worstFrameMs}ms filaMax=${stats.maxBacklogSignals} " +
                    "fps=$fps saida=${outputWidth}x$outputHeight"
        )
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
        val maxBacklogSignals: Int = 0
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
                        pendingFrames.updateAndGet { current ->
                            if (current >= MAX_PENDING_SIGNAL_COUNT) current else current + 1
                        }
                        // Acorda a thread GL na hora em vez de esperar o próximo poll de 1 ms.
                        LockSupport.unpark(renderThreadRef)
                    },
                    frameSignalHandler
                )
            }
            surfaceTexture = st
            camera = Surface(st)
            cameraSurface = camera

            previousTexture = createStorageTexture(sourceWidth, sourceHeight)
            previousFramebuffer = createFramebuffer(previousTexture)
            externalProgram = createProgram(VERTEX_SHADER_EXTERNAL, FRAGMENT_SHADER_EXTERNAL)
            blendProgram = createProgram(VERTEX_SHADER_BLEND, FRAGMENT_SHADER_BLEND)

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)

            ready.countDown()

            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            val slotClock = CfrSlotClock(frameIntervalNs)
            var haveLatchedFrame = false
            var timelineStarted = false
            var previousSourceTimestampNs = 0L
            var copiedTimestampNs = Long.MIN_VALUE
            var outputPtsNs = 0L

            while (!released.get()) {
                if (!outputEnabled.get()) {
                    // Consome TODOS os buffers enfileirados. Antes era um por poll, e a fila
                    // do SurfaceTexture podia chegar ao início da gravação com frames velhos.
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
                    slotClock.start(previousSourceTimestampNs)
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
                    continue
                }

                if (!haveLatchedFrame) {
                    st.updateTexImage()
                    haveLatchedFrame = true
                    previousSourceTimestampNs = st.timestamp
                    continue
                }

                // Preserva o frame real anterior antes de SurfaceTexture avançar. A cópia
                // só acontece uma vez por frame latched: iteração sem frame novo não gasta
                // mais um passe de GPU em resolução cheia.
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

                st.updateTexImage()
                val currentSourceTimestampNs = st.timestamp

                // Alguns drivers podem coalescer callbacks da SurfaceTexture.
                // O mesmo timestamp nunca pode virar um segundo frame no arquivo.
                if (currentSourceTimestampNs <= previousSourceTimestampNs) {
                    continue
                }

                // Posição pelo tempo acumulado (e não pelo delta do par): sem blends falsos
                // por jitter/timestamps pareados e sem a saída correr à frente do tempo real.
                val decision = slotClock.next(currentSourceTimestampNs)
                previousSourceTimestampNs = currentSourceTimestampNs

                if (decision.drop) {
                    // Câmera acima do FPS nominal: a saída já está à frente; este frame real
                    // só serve de referência para um eventual blend seguinte.
                    droppedFrames++
                    continue
                }

                // main3 fix: preserve geometric integrity.
                // Missing CFR slots are synthesized only by temporal blending between
                // the two surrounding real frames. No optical-flow warp is applied,
                // so a repair frame cannot bend straight lines or create local flashes.
                val interpolationAlphas =
                    CfrInterpolationPlanner.interpolationAlphas(decision.missingSlots + 1)

                for (alpha in interpolationAlphas) {
                    renderBlendToEncoder(
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
                    outputPtsNs += frameIntervalNs
                    interpolatedFrames++
                }

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

                if (decision.missingSlots > largestFillSlots) largestFillSlots = decision.missingSlots
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
            texCoords = texCoords,
            viewportWidth = sourceWidth,
            viewportHeight = sourceHeight,
            rotationDegrees = 0
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
            viewportWidth = outputWidth,
            viewportHeight = outputHeight,
            rotationDegrees = physicalRotationDegrees
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
        viewportWidth: Int,
        viewportHeight: Int,
        rotationDegrees: Int
    ) {
        st.getTransformMatrix(textureMatrix)
        val shaderRotation = if (rotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, rotationDegrees)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        val rotationHandle = GLES20.glGetUniformLocation(program, "uRotationDegrees")

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniform1f(rotationHandle, shaderRotation.toFloat())
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
        val shaderRotation = if (physicalRotationDegrees == 0) 0 else resolveShaderRotation(textureMatrix, physicalRotationDegrees)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val previousHandle = GLES20.glGetUniformLocation(program, "sPrevious")
        val currentHandle = GLES20.glGetUniformLocation(program, "sCurrent")
        val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
        val rotationHandle = GLES20.glGetUniformLocation(program, "uRotationDegrees")

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniform1f(rotationHandle, shaderRotation.toFloat())

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

        private const val VERTEX_SHADER_EXTERNAL = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform float uRotationDegrees;
            varying vec2 vTexCoord;

            vec2 rotateUv(vec2 uv) {
                // Rotação horária (mesma convenção de MediaMuxer.setOrientationHint).
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);        // 270°
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);  // 180°
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);         // 90°
                return uv;
            }

            void main() {
                gl_Position = aPosition;
                vec2 rotated = rotateUv(aTexCoord.xy);
                vTexCoord = (uTextureMatrix * vec4(rotated, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val VERTEX_SHADER_BLEND = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform float uRotationDegrees;
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;

            vec2 rotateUv(vec2 uv) {
                // Rotação horária (mesma convenção de MediaMuxer.setOrientationHint).
                if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);        // 270°
                if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);  // 180°
                if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);         // 90°
                return uv;
            }

            void main() {
                gl_Position = aPosition;
                vec2 rotated = rotateUv(aTexCoord.xy);
                vPreviousCoord = rotated;
                vCurrentCoord = (uTextureMatrix * vec4(rotated, 0.0, 1.0)).xy;
            }
        """

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private const val FRAGMENT_SHADER_BLEND = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            uniform sampler2D sPrevious;
            uniform samplerExternalOES sCurrent;
            uniform float uAlpha;
            void main() {
                vec4 previousColor = texture2D(sPrevious, vPreviousCoord);
                vec4 currentColor = texture2D(sCurrent, vCurrentCoord);
                gl_FragColor = mix(previousColor, currentColor, uAlpha);
            }
        """


    }
}