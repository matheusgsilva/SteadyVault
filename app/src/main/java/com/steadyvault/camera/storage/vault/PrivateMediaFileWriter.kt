package com.steadyvault.camera.storage.vault

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.storage.PrivateStorageGuard
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Cópia transacional, durável e monitorada usada por todos os cofres ao importar uma mídia. */
object PrivateMediaFileWriter {
    data class CopyResult(val bytes: Long, val sha256: String)

    private data class OpenedSource(val input: InputStream, val expectedBytes: Long)

    fun copyFromUri(context: Context, uri: Uri, target: File, onProgress: ((Long, Long) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): CopyResult {
        ensureActive(shouldCancel)
        val directory = requireNotNull(target.parentFile) { "Destino privado inválido" }
        directory.mkdirs()
        PrivateStorageGuard.requireSpace(directory, 0L)

        var lastError: Throwable? = null
        for (attempt in 0 until MAX_READ_ATTEMPTS) {
            if (attempt > 0) {
                val delay = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                AppLogRepository.warn(context, "import", "Nova tentativa de leitura ${attempt + 1}/$MAX_READ_ATTEMPTS para ${target.name} após ${delay}ms")
                SystemClock.sleep(delay)
            }
            try {
                ensureActive(shouldCancel)
                return copyAttempt(context, uri, target, directory, attempt, onProgress, shouldCancel)
            } catch (throwable: Throwable) {
                lastError = throwable
                if (!isRetryableReadFailure(throwable) || attempt == MAX_READ_ATTEMPTS - 1) break
            }
        }

        val error = lastError ?: IOException("Não foi possível ler a mídia selecionada")
        if (isRetryableReadFailure(error)) {
            throw IOException("Falha de leitura da origem após $MAX_READ_ATTEMPTS tentativas: ${rootMessage(error)}", error)
        }
        throw error
    }

    private fun copyAttempt(context: Context, uri: Uri, target: File, directory: File, attempt: Int, onProgress: ((Long, Long) -> Unit)?, shouldCancel: () -> Boolean): CopyResult {
        val partial = File(directory, ".${target.name}.${System.nanoTime()}.svimport.partial")
        try {
            ensureActive(shouldCancel)
            openSource(context, uri, attempt).let { source ->
                source.input.use { input ->
                    val expected = source.expectedBytes.coerceAtLeast(0L)
                    if (expected > 0L) PrivateStorageGuard.requireSpace(directory, expected)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val lastReadElapsed = AtomicLong(SystemClock.elapsedRealtime())
                    val stalled = AtomicBoolean(false)
                    val watchdog = stallWatchdog.scheduleAtFixedRate({
                        val idleMs = SystemClock.elapsedRealtime() - lastReadElapsed.get()
                        if (idleMs >= READ_STALL_TIMEOUT_MS && stalled.compareAndSet(false, true)) runCatching { input.close() }
                    }, READ_STALL_CHECK_MS, READ_STALL_CHECK_MS, TimeUnit.MILLISECONDS)
                    var copied = 0L
                    try {
                        FileOutputStream(partial).use { stream ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            var nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES
                            var nextProgressBytes = PROGRESS_INTERVAL_BYTES
                            var lastProgressElapsed = SystemClock.elapsedRealtime()
                            onProgress?.invoke(0L, expected)
                            while (true) {
                                ensureActive(shouldCancel)
                                val read = try {
                                    input.read(buffer)
                                } catch (throwable: Throwable) {
                                    if (stalled.get()) throw IOException("Leitura sem progresso por ${READ_STALL_TIMEOUT_MS / 1000L}s", throwable)
                                    throw throwable
                                }
                                ensureActive(shouldCancel)
                                if (read < 0) break
                                if (read == 0) continue
                                lastReadElapsed.set(SystemClock.elapsedRealtime())
                                stream.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                copied += read.toLong()
                                if (copied >= nextSpaceCheck) {
                                    val remaining = if (expected > 0L) (expected - copied).coerceAtLeast(SPACE_CHECK_INTERVAL_BYTES) else SPACE_CHECK_INTERVAL_BYTES
                                    PrivateStorageGuard.requireSpace(directory, remaining)
                                    nextSpaceCheck = copied + SPACE_CHECK_INTERVAL_BYTES
                                }
                                val now = SystemClock.elapsedRealtime()
                                if (copied >= nextProgressBytes || now - lastProgressElapsed >= PROGRESS_INTERVAL_MS) {
                                    onProgress?.invoke(copied, expected)
                                    nextProgressBytes = copied + PROGRESS_INTERVAL_BYTES
                                    lastProgressElapsed = now
                                }
                            }
                            if (stalled.get()) throw IOException("Leitura sem progresso por ${READ_STALL_TIMEOUT_MS / 1000L}s")
                            ensureActive(shouldCancel)
                            onProgress?.invoke(copied, expected)
                            stream.flush()
                            stream.fd.sync()
                        }
                    } finally {
                        watchdog.cancel(false)
                    }

                    ensureActive(shouldCancel)
                    require(partial.length() > 0L) { "A mídia selecionada está vazia" }
                    if (expected > 0L) require(partial.length() == expected) { "Cópia incompleta: esperado $expected bytes e recebido ${partial.length()} bytes" }
                    publishAtomically(partial, target)
                    if (shouldCancel() || Thread.currentThread().isInterrupted) {
                        target.delete()
                        throw InterruptedIOException("Importação cancelada")
                    }
                    require(target.isFile && target.length() == copied && copied > 0L) { "Não foi possível publicar a mídia completa no cofre" }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    return CopyResult(copied, hash)
                }
            }
        } catch (throwable: Throwable) {
            if (target.exists()) target.delete()
            partial.delete()
            throw throwable
        }
    }

    private fun ensureActive(shouldCancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || shouldCancel()) throw InterruptedIOException("Importação cancelada")
    }

    private fun openSource(context: Context, uri: Uri, attempt: Int): OpenedSource {
        val resolver = context.contentResolver
        return if (attempt % 2 == 0) {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Não foi possível abrir a mídia selecionada")
            OpenedSource(ParcelFileDescriptor.AutoCloseInputStream(descriptor), descriptor.statSize.coerceAtLeast(0L))
        } else {
            OpenedSource(resolver.openInputStream(uri) ?: throw FileNotFoundException("Não foi possível abrir a mídia selecionada"), 0L)
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(COPY_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun isRetryableReadFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is FileNotFoundException || current is SecurityException) return false
            if (current is ErrnoException && current.errno == OsConstants.EIO) return true
            val message = current.message.orEmpty().lowercase()
            if ("eio" in message || "i/o error" in message || "input/output error" in message || "read failed" in message) return true
            if (current is IOException && ("temporarily unavailable" in message || "resource busy" in message || "connection reset" in message || "sem progresso" in message || "stream closed" in message)) return true
            current = current.cause
        }
        return false
    }

    private fun rootMessage(throwable: Throwable): String {
        var current: Throwable = throwable
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message?.take(180).orEmpty().ifBlank { current.javaClass.simpleName }
    }

    private fun publishAtomically(partial: File, target: File) {
        require(!target.exists()) { "O destino da importação já existe" }
        if (partial.renameTo(target)) {
            syncDirectory(target.parentFile)
            return
        }
        FileInputStream(partial).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.flush()
                output.fd.sync()
            }
        }
        require(target.length() == partial.length()) { "Falha ao confirmar a cópia transacional" }
        require(partial.delete()) { "A cópia foi concluída, mas o temporário não pôde ser removido" }
        syncDirectory(target.parentFile)
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0).let { fd ->
                try {
                    android.system.Os.fsync(fd)
                } finally {
                    android.system.Os.close(fd)
                }
            }
        }
    }

    private val stallWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SteadyVault-ImportReadWatchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1_500L)
    private const val MAX_READ_ATTEMPTS = 4
    private const val COPY_BUFFER_SIZE = 512 * 1024
    private const val SPACE_CHECK_INTERVAL_BYTES = 16L * 1024L * 1024L
    private const val PROGRESS_INTERVAL_BYTES = 2L * 1024L * 1024L
    private const val PROGRESS_INTERVAL_MS = 300L
    private const val READ_STALL_CHECK_MS = 3_000L
    private const val READ_STALL_TIMEOUT_MS = 20_000L
}
