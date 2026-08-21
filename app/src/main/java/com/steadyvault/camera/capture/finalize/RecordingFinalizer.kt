package com.steadyvault.camera.capture.finalize

import com.steadyvault.camera.storage.vault.FileDurability
import java.io.File

object RecordingFinalizer {
    fun syncAfterMuxerStop(file: File) {
        FileDurability.syncFileAndDirectory(file)
    }
}
