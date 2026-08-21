package com.steadyvault.camera.storage.vault

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

object FileDurability {

    fun syncFileAndDirectory(file: File) {
        if (!file.isFile) return

        runCatching {
            FileOutputStream(file, true).use { stream ->
                stream.flush()
                stream.fd.sync()
            }
        }

        syncDirectory(file.parentFile)
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null || !directory.isDirectory) return

        val descriptor = runCatching {
            Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        }.getOrNull() ?: return

        try {
            runCatching { Os.fsync(descriptor) }
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }
}
