package com.steadyvault.camera.storage.vault

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centraliza gravações interrompidas fora do cache. O cache é apenas área de trabalho:
 * assim que um temporário fica antigo o app o valida e move para o cofre de recuperação.
 */
object RecordingRecoveryRepository {
    data class RecoveryReport(val recoveredFiles: Int, val preservedBrokenFiles: Int)
    enum class CandidateState { RECOVERABLE, INCOMPLETE, RECOVERED }
    data class RecoveryCandidate(val file: File, val state: CandidateState, val bytes: Long, val modifiedAt: Long, val description: String)

    private val recovering = AtomicBoolean(false)

    fun directory(context: Context): File = File(context.filesDir, "vaults/recovery").apply { mkdirs() }

    fun recoverStaleRecordings(context: Context, minimumAgeMs: Long = MINIMUM_RECOVERY_AGE_MS): RecoveryReport {
        if (!recovering.compareAndSet(false, true)) return RecoveryReport(0, 0)
        return try {
            val now = System.currentTimeMillis()
            var recovered = 0
            var broken = 0
            recordingCacheRoots(context).forEach { root ->
                root.listFiles { file -> isRecoveryCandidate(file) }.orEmpty().forEach { raw ->
                    if ((now - raw.lastModified()).coerceAtLeast(0L) < minimumAgeMs) return@forEach
                    val saved = if (hasUsableVideo(raw)) recoverOne(context, raw) else preserveBroken(context, raw)
                    if (saved != null) {
                        raw.delete()
                        if (hasUsableVideo(saved)) recovered++ else broken++
                        AppLogRepository.warn(context, "recovery", "Gravação interrompida preservada fora do cache: ${saved.name}")
                    } else {
                        AppLogRepository.error(context, "recovery", "Não foi possível mover o temporário para a área de recuperação: ${raw.name}")
                    }
                }
            }
            RecoveryReport(recovered, broken)
        } finally {
            recovering.set(false)
        }
    }

    fun listCandidates(context: Context): List<RecoveryCandidate> {
        val persistent = directory(context).listFiles { file -> file.isFile && file.extension.equals("mp4", true) }.orEmpty().map { file ->
            val usable = hasUsableVideo(file)
            RecoveryCandidate(
                file = file,
                state = if (usable) CandidateState.RECOVERED else CandidateState.INCOMPLETE,
                bytes = file.length(),
                modifiedAt = file.lastModified(),
                description = if (usable) "Vídeo recuperado preservado na área de recuperação" else "Arquivo interrompido preservado fora do cache para análise"
            )
        }
        val recentCache = recordingCacheRoots(context).flatMap { root ->
            root.listFiles { file -> isRecoveryCandidate(file) }.orEmpty().map { file ->
                RecoveryCandidate(file, if (hasUsableVideo(file)) CandidateState.RECOVERABLE else CandidateState.INCOMPLETE, file.length(), file.lastModified(), "Temporário recente aguardando finalização ou preservação")
            }
        }
        return (persistent + recentCache).distinctBy { canonicalPath(it.file) }.sortedByDescending { it.modifiedAt }
    }

    fun deleteCandidate(context: Context, file: File): Boolean {
        val allowed = isInside(directory(context), file) || recordingCacheRoots(context).any { isInside(it, file) && isRecoveryCandidate(file) }
        if (!allowed || !file.isFile) return false
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (deleted) AppLogRepository.warn(context, "recovery", "Arquivo de recuperação removido pelo usuário: ${file.name}")
        return deleted
    }

    fun moveRecovered(context: Context, file: File, area: String): File {
        require(file.isFile && isInside(directory(context), file) && hasUsableVideo(file)) { "Arquivo recuperado inválido" }
        val destination = when (area) {
            AREA_PRIMARY -> VaultRepository.primaryDirectory(context)
            AREA_SECONDARY -> SecondaryVaultRepository.directory(context)
            AREA_TERTIARY -> TertiaryVaultRepository.directory(context)
            else -> throw IllegalArgumentException("Destino inválido")
        }
        val target = uniqueFile(destination, file.name)
        moveValidated(file, target, requirePlayable = true)
        VaultMediaIndex.invalidate()
        AppLogRepository.info(context, "recovery", "Recuperado movido para $area: ${target.name}")
        return target
    }

    fun shouldProtectFromCacheCleanup(file: File): Boolean = if (file.isFile) isRecoveryCandidate(file) else file.walkTopDown().any { it.isFile && isRecoveryCandidate(it) }

    fun preserveInterrupted(context: Context, raw: File, label: String = "GravacaoInterrompida"): File? {
        if (!raw.isFile || raw.length() <= 0L) return null
        val usable = hasUsableVideo(raw)
        val prefix = if (usable) "Recuperado" else "Erro"
        val target = uniqueFile(directory(context), "${prefix}_${label}_${System.currentTimeMillis()}.mp4")
        return runCatching {
            moveValidated(raw, target, requirePlayable = usable)
            AppLogRepository.warn(context, "recovery", "Gravação interrompida preservada em Vídeos com erro / recuperados: ${target.name}")
            target
        }.onFailure { error ->
            target.delete()
            AppLogRepository.error(context, "recovery", "Falha ao preservar ${raw.name}", error)
        }.getOrNull()
    }

    fun recoverOne(context: Context, raw: File, label: String = "Recuperado_GravacaoInterrompida"): File? {
        if (!hasUsableVideo(raw)) return null
        val target = uniqueFile(directory(context), "${label}_${System.currentTimeMillis()}.mp4")
        return runCatching {
            moveValidated(raw, target, requirePlayable = true, keepSource = true)
            target
        }.onFailure { error ->
            target.delete()
            AppLogRepository.error(context, "recovery", "Falha ao preservar ${raw.name}", error)
        }.getOrNull()
    }

    fun hasUsableVideo(file: File): Boolean {
        if (!file.isFile || file.length() < MINIMUM_VALID_VIDEO_BYTES) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
                extractor.selectTrack(index)
                val hasSample = extractor.sampleTime >= 0L
                extractor.unselectTrack(index)
                if (hasSample) return true
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    fun recoveryBytes(context: Context): Long = directory(context).walkTopDown().filter(File::isFile).sumOf { it.length().coerceAtLeast(0L) }

    private fun preserveBroken(context: Context, raw: File): File? {
        val target = uniqueFile(directory(context), "Erro_GravacaoInterrompida_${System.currentTimeMillis()}.mp4")
        return runCatching {
            moveValidated(raw, target, requirePlayable = false, keepSource = true)
            target
        }.onFailure { target.delete() }.getOrNull()
    }

    private fun moveValidated(source: File, target: File, requirePlayable: Boolean, keepSource: Boolean = false) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, ".${target.name}.moving")
        try {
            source.inputStream().buffered().use { input -> staging.outputStream().buffered().use(input::copyTo) }
            require(staging.length() == source.length() && staging.length() > 0L) { "A cópia ficou incompleta" }
            if (requirePlayable) require(hasUsableVideo(staging)) { "O vídeo copiado não é reproduzível" }
            require(staging.renameTo(target)) { "Não foi possível concluir a movimentação" }
            if (!keepSource) require(source.delete()) { "A cópia foi concluída, mas o original não pôde ser removido" }
        } catch (error: Throwable) {
            staging.delete()
            target.delete()
            throw error
        }
    }

    private fun recordingCacheRoots(context: Context): List<File> = listOfNotNull(context.cacheDir, context.externalCacheDir).distinctBy(::canonicalPath)
    private fun isRecoveryCandidate(file: File): Boolean = file.isFile && file.extension.equals("mp4", true) && file.name.startsWith(CAMERA_PREFIX)
    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    private fun isInside(root: File, file: File): Boolean = runCatching { file.canonicalFile.path.startsWith(root.canonicalFile.path + File.separator) }.getOrDefault(false)

    private fun uniqueFile(directory: File, name: String): File {
        directory.mkdirs()
        val dot = name.lastIndexOf('.')
        val fileStem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var target = File(directory, name)
        var index = 1
        while (target.exists()) target = File(directory, "${fileStem}_${index++}$ext")
        return target
    }

    const val AREA_PRIMARY = VaultAreaId.PRIMARY
    const val AREA_SECONDARY = VaultAreaId.SECONDARY
    const val AREA_TERTIARY = VaultAreaId.TERTIARY
    private const val CAMERA_PREFIX = "steadyvault_raw_"
    private const val MINIMUM_VALID_VIDEO_BYTES = 32_768L
    private const val MINIMUM_RECOVERY_AGE_MS = 15_000L
}
