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
import kotlin.math.max

/**
 * Ponte GPU de baixa sobrecarga entre Camera2 e a Surface do MediaCodec.
 *
 * A câmera escreve em uma SurfaceTexture OES. Esta classe reapresenta a última
 * textura disponível em uma grade CFR rígida e envia os frames ao encoder com
 * PTS igualmente espaçados. Quando a HAL atrasa um frame, a última imagem é
 * repetida somente pelo tempo necessário; nenhum pixel passa pela CPU.
 *
 * Prioridade: nunca bloquear a Camera2 para fazer reparo visual sofisticado.
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
    @Volatile private var repeatedFrames = 0L

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
        outputEnabled.set(true)
    }

    fun stopOutput() {
        outputEnabled.set(false)
    }

    fun stats(): Stats = Stats(realFrames, repeatedFrames)

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
        val repeatedFrames: Long
    )

    private fun renderLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var window = EGL14.EGL_NO_SURFACE
        var textureId = 0
        var program = 0
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

            textureId = createExternalTexture()
            st = SurfaceTexture(textureId).apply {
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

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            val matrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
            val vertices = floatBuffer(VERTICES)
            val texCoords = floatBuffer(TEX_COORDS)
            val textureMatrix = FloatArray(16)

            ready.countDown()

            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            var hasFrame = false
            var outputPtsNs = 0L
            var nextWallNs = 0L

            while (!released.get()) {
                if (!outputEnabled.get()) {
                    if (pendingFrames.getAndSet(0) > 0) {
                        runCatching {
                            st.updateTexImage()
                            hasFrame = true
                        }
                    }
                    LockSupport.parkNanos(IDLE_POLL_NS)
                    continue
                }

                if (!hasFrame) {
                    if (pendingFrames.getAndSet(0) <= 0) {
                        LockSupport.parkNanos(IDLE_POLL_NS)
                        continue
                    }
                    st.updateTexImage()
                    hasFrame = true
                    realFrames++
                    outputPtsNs = System.nanoTime()
                    nextWallNs = outputPtsNs
                    drawFrame(
                        st, textureMatrix, program, positionHandle, texCoordHandle,
                        matrixHandle, samplerHandle, vertices, texCoords, textureId,
                        display, window, outputPtsNs
                    )
                    outputPtsNs += frameIntervalNs
                    nextWallNs += frameIntervalNs
                    continue
                }

                val now = System.nanoTime()
                if (now < nextWallNs) {
                    LockSupport.parkNanos((nextWallNs - now).coerceAtMost(MAX_SLEEP_NS))
                    continue
                }

                val due = (1L + (now - nextWallNs) / frameIntervalNs)
                    .coerceIn(1L, MAX_CATCH_UP_FRAMES.toLong())
                    .toInt()
                val newFrameAvailable = pendingFrames.get() > 0

                // Se acumulou atraso, preenche posições anteriores com o último
                // frame conhecido e reserva a última posição para o frame mais novo.
                val repeatsBeforeUpdate = if (newFrameAvailable) due - 1 else due
                repeat(repeatsBeforeUpdate) {
                    drawFrame(
                        st, textureMatrix, program, positionHandle, texCoordHandle,
                        matrixHandle, samplerHandle, vertices, texCoords, textureId,
                        display, window, outputPtsNs
                    )
                    repeatedFrames++
                    outputPtsNs += frameIntervalNs
                    nextWallNs += frameIntervalNs
                }

                if (newFrameAvailable && outputEnabled.get()) {
                    pendingFrames.set(0)
                    st.updateTexImage()
                    realFrames++
                    drawFrame(
                        st, textureMatrix, program, positionHandle, texCoordHandle,
                        matrixHandle, samplerHandle, vertices, texCoords, textureId,
                        display, window, outputPtsNs
                    )
                    outputPtsNs += frameIntervalNs
                    nextWallNs += frameIntervalNs
                }

                // Evita espiral de carga se o processo ficou suspenso por muito tempo:
                // preserva a grade de PTS, mas volta o relógio de despacho para agora.
                if (System.nanoTime() - nextWallNs > frameIntervalNs * MAX_CATCH_UP_FRAMES) {
                    nextWallNs = System.nanoTime() + frameIntervalNs
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
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
                if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
                if (textureId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
                if (window != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, window) }
                if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglReleaseThread() }
                runCatching { EGL14.eglTerminate(display) }
            }
            if (ready.count > 0L) ready.countDown()
            stopped.countDown()
        }
    }

    private fun drawFrame(
        st: SurfaceTexture,
        textureMatrix: FloatArray,
        program: Int,
        positionHandle: Int,
        texCoordHandle: Int,
        matrixHandle: Int,
        samplerHandle: Int,
        vertices: FloatBuffer,
        texCoords: FloatBuffer,
        textureId: Int,
        display: android.opengl.EGLDisplay,
        window: android.opengl.EGLSurface,
        presentationTimeNs: Long
    ) {
        st.getTransformMatrix(textureMatrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)

        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers falhou" }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        check(id != 0) { "falha criando textura OES" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
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
        private const val IDLE_POLL_NS = 500_000L
        private const val MAX_SLEEP_NS = 2_000_000L
        private const val MAX_CATCH_UP_FRAMES = 6
        private const val MAX_PENDING_SIGNAL_COUNT = 8

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

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
