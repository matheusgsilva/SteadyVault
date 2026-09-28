package com.steadyvault.camera.capture.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

/**
 * Ponte GPU CFR sem repetição de frames.
 *
 * Camera2 escreve em uma SurfaceTexture OES. O frame real anterior é preservado
 * em uma textura 2D da GPU antes de avançar para o próximo frame real. Se o delta
 * entre os timestamps da câmera indicar slots ausentes, esses slots são gerados
 * por interpolação temporal entre o frame anterior e o próximo frame real.
 *
 * Não há fallback de duplicação: nenhum slot CFR é preenchido reapresentando
 * exatamente o mesmo frame anterior. O custo fica na GPU; pixels não voltam à CPU.
 */
class RealTimeCfrSurfaceBridge(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
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
        // Descarta apenas sinais antigos; o próximo frame real abre a timeline útil.
        pendingFrames.set(0)
        outputEnabled.set(true)
    }

    fun stopOutput() {
        outputEnabled.set(false)
    }

    fun stats(): Stats = Stats(realFrames, interpolatedFrames)

    fun release() {
        if (!released.compareAndSet(false, true)) return
        outputEnabled.set(false)
        renderThread.interrupt()
        runCatching { stopped.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        runCatching { frameSignalThread.quitSafely() }
        runCatching { frameSignalThread.join(RELEASE_TIMEOUT_MS) }
    }

    data class Stats(
        val realFrames: Long,
        val interpolatedFrames: Long
    )

    private fun renderLoop() {
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
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(
                    {
                        pendingFrames.updateAndGet { current ->
                            if (current >= MAX_PENDING_SIGNAL_COUNT) current else current + 1
                        }
                    },
                    frameSignalHandler
                )
            }
            surfaceTexture = st
            camera = Surface(st)
            cameraSurface = camera

            previousTexture = createStorageTexture(width, height)
            previousFramebuffer = createFramebuffer(previousTexture)
            externalProgram = createProgram(VERTEX_SHADER_EXTERNAL, FRAGMENT_SHADER_EXTERNAL)
            blendProgram = createProgram(VERTEX_SHADER_BLEND, FRAGMENT_SHADER_BLEND)

            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)

            ready.countDown()

            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            var haveLatchedFrame = false
            var timelineStarted = false
            var previousSourceTimestampNs = 0L
            var outputPtsNs = 0L

            while (!released.get()) {
                if (!outputEnabled.get()) {
                    if (pendingFrames.getAndSet(0) > 0) {
                        runCatching {
                            st.updateTexImage()
                            haveLatchedFrame = true
                        }
                    }
                    timelineStarted = false
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }

                if (pendingFrames.get() <= 0) {
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }

                pendingFrames.decrementAndGet()

                if (!timelineStarted) {
                    st.updateTexImage()
                    haveLatchedFrame = true
                    previousSourceTimestampNs = st.timestamp
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

                // Preserva o frame real anterior antes de SurfaceTexture avançar.
                copyExternalToPreviousTexture(
                    st = st,
                    textureMatrix = textureMatrix,
                    program = externalProgram,
                    externalTexture = externalTexture,
                    framebuffer = previousFramebuffer,
                    vertices = vertices,
                    texCoords = texCoords
                )

                st.updateTexImage()
                val currentSourceTimestampNs = st.timestamp
                val deltaNs = (currentSourceTimestampNs - previousSourceTimestampNs)
                    .coerceAtLeast(frameIntervalNs)

                val sourceSteps = (deltaNs.toDouble() / frameIntervalNs.toDouble())
                    .roundToInt()
                    .coerceAtLeast(1)
                    .coerceAtMost(MAX_INTERPOLATED_STEPS_PER_SOURCE_GAP)

                val missingFrames = (sourceSteps - 1).coerceAtLeast(0)

                // Cada slot ausente recebe uma mistura diferente entre os dois
                // frames reais. alpha nunca é 0 ou 1, portanto não é cópia exata.
                for (index in 1..missingFrames) {
                    val alpha = index.toFloat() / sourceSteps.toFloat()
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
                previousSourceTimestampNs = currentSourceTimestampNs
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
            texCoords = texCoords
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
        texCoords: FloatBuffer
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")

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

        // Sem duplicação: gaps maiores também recebem alphas distintos.
        // O teto evita uma explosão patológica de trabalho após suspensão longa.
        private const val MAX_INTERPOLATED_STEPS_PER_SOURCE_GAP = 120

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
            varying vec2 vPreviousCoord;
            varying vec2 vCurrentCoord;
            void main() {
                gl_Position = aPosition;
                vPreviousCoord = aTexCoord.xy;
                vCurrentCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """

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
