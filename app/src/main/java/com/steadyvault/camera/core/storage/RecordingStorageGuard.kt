package com.steadyvault.camera.core.storage

import android.content.Context
import android.os.StatFs
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.vault.VaultRepository
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

object RecordingStorageGuard {
    data class Result(
        val allowed: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val message: String
    )

    fun checkProfile(context: Context, settings: CaptureSettings.Snapshot): Result =
        check(
            context = context,
            videoBitrateBps = settings.bitrateMbps.toLong() * 1_000_000L,
            audioBitrateBps = settings.audioBitrateKbps.toLong() * 1_000L
        )

    fun check(
        context: Context,
        videoBitrateBps: Long,
        audioBitrateBps: Long
    ): Result {
        val required = requiredBytes(videoBitrateBps, audioBitrateBps)
        val spaces = storageSpaces(context)
        val available = spaces.minOfOrNull { it.second } ?: 0L
        val failing = spaces.firstOrNull { it.second < required }

        return when {
            spaces.isEmpty() -> Result(
                allowed = false,
                requiredBytes = required,
                availableBytes = 0L,
                message = "Não foi possível validar o espaço livre antes de gravar."
            )
            failing != null -> Result(
                allowed = false,
                requiredBytes = required,
                availableBytes = failing.second,
                message = "Espaço insuficiente para gravar com segurança. Livre ${formatBytes(failing.second)}; necessário ${formatBytes(required)}."
            )
            else -> Result(
                allowed = true,
                requiredBytes = required,
                availableBytes = available,
                message = "Espaço OK: ${formatBytes(available)} livres."
            )
        }
    }

    /** Reserva mínima reavaliada durante a gravação para finalizar antes de o disco esgotar. */
    fun checkOngoing(
        context: Context,
        videoBitrateBps: Long,
        audioBitrateBps: Long
    ): Result {
        val bytesPerSecond = (
            videoBitrateBps.coerceAtLeast(MIN_VIDEO_BITRATE_BPS) +
                audioBitrateBps.coerceAtLeast(DEFAULT_AUDIO_BITRATE_BPS)
            ) / 8L
        val required = max(
            RUNTIME_MINIMUM_FREE_BYTES,
            bytesPerSecond * RUNTIME_SAFETY_SECONDS + RUNTIME_FILESYSTEM_RESERVE_BYTES
        )
        val spaces = storageSpaces(context)
        val available = spaces.minOfOrNull { it.second } ?: 0L
        val failing = spaces.firstOrNull { it.second < required }
        return when {
            spaces.isEmpty() -> Result(false, required, 0L, "Não foi possível acompanhar o espaço livre.")
            failing != null -> Result(
                false,
                required,
                failing.second,
                "Espaço crítico: restam ${formatBytes(failing.second)}; reservando o trecho gravado."
            )
            else -> Result(true, required, available, "Espaço de gravação monitorado.")
        }
    }

    private fun storageSpaces(context: Context): List<Pair<File, Long>> = listOf(
        context.cacheDir,
        VaultRepository.primaryDirectory(context)
    ).distinctBy { directory ->
        runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
    }.mapNotNull { directory ->
        availableBytes(directory)?.let { directory to it }
    }

    private fun requiredBytes(videoBitrateBps: Long, audioBitrateBps: Long): Long {
        val safeVideo = videoBitrateBps.coerceAtLeast(MIN_VIDEO_BITRATE_BPS)
        val safeAudio = audioBitrateBps.coerceAtLeast(DEFAULT_AUDIO_BITRATE_BPS)
        val oneMinuteRaw = (((safeVideo + safeAudio) / 8.0) * MIN_SAFE_SECONDS).roundToLong()
        val rawPlusFinalization = oneMinuteRaw * FINALIZATION_MULTIPLIER
        return max(MIN_REQUIRED_BYTES, rawPlusFinalization + FILESYSTEM_RESERVE_BYTES)
    }

    private fun availableBytes(directory: File): Long? = runCatching {
        directory.mkdirs()
        StatFs(directory.absolutePath).availableBytes
    }.getOrNull()

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val gib = safe / 1_073_741_824.0
        if (gib >= 0.95) return String.format(java.util.Locale.US, "%.1f GB", gib)
        val mib = safe / 1_048_576.0
        return String.format(java.util.Locale.US, "%.0f MB", mib)
    }

    private const val MIN_SAFE_SECONDS = 60L
    private const val FINALIZATION_MULTIPLIER = 2L
    private const val FILESYSTEM_RESERVE_BYTES = 384L * 1024L * 1024L
    private const val MIN_REQUIRED_BYTES = 768L * 1024L * 1024L
    private const val MIN_VIDEO_BITRATE_BPS = 8_000_000L
    private const val DEFAULT_AUDIO_BITRATE_BPS = 256_000L
    private const val RUNTIME_SAFETY_SECONDS = 60L
    private const val RUNTIME_FILESYSTEM_RESERVE_BYTES = 192L * 1024L * 1024L
    private const val RUNTIME_MINIMUM_FREE_BYTES = 512L * 1024L * 1024L
}
