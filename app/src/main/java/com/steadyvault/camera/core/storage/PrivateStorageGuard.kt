package com.steadyvault.camera.core.storage

import android.os.StatFs
import java.io.File

/** Verificação compartilhada para cópias privadas, imports e downloads. */
object PrivateStorageGuard {
    fun requireSpace(directory: File, expectedBytes: Long, reserveBytes: Long = DEFAULT_RESERVE_BYTES) {
        directory.mkdirs()
        val available = runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
        val required = expectedBytes.coerceAtLeast(0L) + reserveBytes.coerceAtLeast(0L)
        require(available >= required) {
            "Espaço insuficiente: libere pelo menos ${formatBytes((required - available).coerceAtLeast(1L))}"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mib = (bytes + MIB - 1L) / MIB
        return if (mib >= 1024L) "${(mib + 1023L) / 1024L} GB" else "$mib MB"
    }

    private const val MIB = 1024L * 1024L
    private const val DEFAULT_RESERVE_BYTES = 192L * MIB
}
