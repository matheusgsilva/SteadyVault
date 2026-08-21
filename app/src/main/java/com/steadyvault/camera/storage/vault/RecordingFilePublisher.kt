package com.steadyvault.camera.storage.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publica uma gravação já finalizada sem recodificar nem alterar seus bytes.
 *
 * O caminho rápido apenas move o MP4 dentro do armazenamento privado. Quando o
 * sistema de arquivos não aceita a movimentação atômica, uma cópia sincronizada
 * é validada antes que o temporário original seja removido.
 */
object RecordingFilePublisher {
    data class Result(
        val file: File,
        val moved: Boolean,
        val bytes: Long
    )

    fun publish(
        raw: File,
        destination: File,
        validator: (File) -> Boolean = RecordingRecoveryRepository::hasUsableVideo
    ): Result {
        require(raw.isFile && raw.length() > 0L) { "Gravação temporária vazia" }
        require(!destination.exists()) { "O destino da gravação já existe" }
        val expectedBytes = raw.length()
        require(validator(raw)) { "Gravação temporária sem vídeo válido" }
        destination.parentFile?.mkdirs()

        if (moveWithoutCopy(raw, destination)) {
            check(destination.isFile && destination.length() == expectedBytes) {
                "Movimentação da gravação ficou incompleta"
            }
            FileDurability.syncFileAndDirectory(destination)
            VaultMediaIndex.invalidate()
            return Result(destination, moved = true, bytes = expectedBytes)
        }

        val pending = File(
            destination.parentFile,
            ".${destination.name}.${System.nanoTime()}.publish_pending"
        )
        try {
            raw.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                FileOutputStream(pending).buffered(COPY_BUFFER_SIZE).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                    output.flush()
                }
            }
            FileOutputStream(pending, true).use { it.fd.sync() }
            check(pending.length() == expectedBytes) { "Cópia da gravação ficou incompleta" }
            check(validator(pending)) { "Cópia da gravação não passou na validação" }
            check(moveWithoutCopy(pending, destination)) { "Não foi possível publicar a cópia validada" }
            check(destination.length() == expectedBytes) { "Arquivo publicado ficou incompleto" }
            raw.delete()
            FileDurability.syncFileAndDirectory(destination)
            VaultMediaIndex.invalidate()
            return Result(destination, moved = false, bytes = expectedBytes)
        } catch (throwable: Throwable) {
            pending.delete()
            destination.takeIf { it.length() != expectedBytes }?.delete()
            throw throwable
        }
    }

    private fun moveWithoutCopy(source: File, destination: File): Boolean {
        val moved = runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        }.recoverCatching { throwable ->
            if (throwable !is AtomicMoveNotSupportedException) throw throwable
            Files.move(source.toPath(), destination.toPath())
            true
        }.getOrDefault(false)
        return moved || source.renameTo(destination)
    }

    private const val COPY_BUFFER_SIZE = 1024 * 1024
}
