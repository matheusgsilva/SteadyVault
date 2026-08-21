package com.steadyvault.camera.storage.vault

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
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

/** Cópia transacional, durável e monitorada usada por todos os cofres ao importar uma mídia. */
object PrivateMediaFileWriter {
    fun copyFromUri(context: Context, uri: Uri, target: File) {
        val expected = queryExpectedSize(context, uri)
        val directory = requireNotNull(target.parentFile) { "Destino privado inválido" }
        directory.mkdirs()
        PrivateStorageGuard.requireSpace(directory, expected)

        var lastError: Throwable? = null
        for (attempt in 0 until MAX_READ_ATTEMPTS) {
            if (attempt > 0) {
                val delay = RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                AppLogRepository.warn(context, "import", "Nova tentativa de leitura ${attempt + 1}/$MAX_READ_ATTEMPTS para ${target.name} após ${delay}ms")
                SystemClock.sleep(delay)
            }
            try {
                copyAttempt(context, uri, target, directory, expected, attempt)
                return
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

    private fun copyAttempt(context: Context, uri: Uri, target: File, directory: File, expected: Long, attempt: Int) {
        val partial = File(directory, ".${target.name}.${System.nanoTime()}.svimport.partial")
        try {
            openSourceStream(context, uri, attempt).use { input ->
                FileOutputStream(partial).use { stream ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copied = 0L
                    var nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Importação cancelada" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        stream.write(buffer, 0, read)
                        copied += read.toLong()
                        if (copied >= nextSpaceCheck) {
                            val remaining = if (expected > 0L) (expected - copied).coerceAtLeast(SPACE_CHECK_INTERVAL_BYTES) else SPACE_CHECK_INTERVAL_BYTES
                            PrivateStorageGuard.requireSpace(directory, remaining)
                            nextSpaceCheck = copied + SPACE_CHECK_INTERVAL_BYTES
                        }
                    }
                    stream.flush()
                    stream.fd.sync()
                }
            }

            require(partial.length() > 0L) { "A mídia selecionada está vazia" }
            if (expected > 0L) require(partial.length() == expected) { "Cópia incompleta: esperado $expected bytes e recebido ${partial.length()} bytes" }
            publishAtomically(partial, target)
            require(target.isFile && target.length() > 0L) { "Não foi possível publicar a mídia no cofre" }
            if (expected > 0L) require(target.length() == expected) { "A mídia publicada ficou incompleta" }
        } catch (throwable: Throwable) {
            if (target.exists()) target.delete()
            partial.delete()
            throw throwable
        }
    }

    private fun openSourceStream(context: Context, uri: Uri, attempt: Int): InputStream {
        val resolver = context.contentResolver
        return if (attempt % 2 == 0) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException("Não foi possível abrir a mídia selecionada")
        } else {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Não foi possível abrir a mídia selecionada")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
    }

    private fun queryExpectedSize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else 0L
            } ?: 0L
        }.getOrDefault(0L).coerceAtLeast(0L)
    }

    internal fun isRetryableReadFailure(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is FileNotFoundException || current is SecurityException) return false
            if (current is ErrnoException && current.errno == OsConstants.EIO) return true
            val message = current.message.orEmpty().lowercase()
            if ("eio" in message || "i/o error" in message || "input/output error" in message || "read failed" in message) return true
            if (current is IOException && ("temporarily unavailable" in message || "resource busy" in message || "connection reset" in message)) return true
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

    private val RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1_500L)
    private const val MAX_READ_ATTEMPTS = 4
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private const val SPACE_CHECK_INTERVAL_BYTES = 16L * 1024L * 1024L
}
