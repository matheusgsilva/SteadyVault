package com.steadyvault.camera.processing.engine

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.media.MediaMetadataRetriever
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OptimizationPreset
import java.io.File
import kotlin.math.max

class ProcessingPolicy(private val context: Context) {
    data class Preflight(val requiredBytes: Long, val availableBytes: Long, val warnings: List<String>)

    fun preflight(input: File, config: OptimizationConfig): Preflight {
        val available = StatFs(input.parentFile?.absolutePath ?: context.filesDir.absolutePath).availableBytes
        val highQualityOutput = config.preset in setOf(
            OptimizationPreset.HIGH_QUALITY,
            OptimizationPreset.HQ_1080P,
            OptimizationPreset.HQ_720P,
            OptimizationPreset.CREATOR_2160P_4K,
            OptimizationPreset.CREATOR_1080P,
            OptimizationPreset.APPLE_2160P_4K_HEVC,
            OptimizationPreset.ARCHIVE_4K
        )
        val expansionFactor = when {
            highQualityOutput -> 1.55
            config.bitrateMbps > 0 -> 1.35
            else -> 1.20
        }
        val manualEstimate = estimateManualOutputBytes(input, config)
        val required = max(
            MINIMUM_FREE_BYTES,
            max((input.length() * expansionFactor).toLong(), manualEstimate)
        )
        require(available >= required) {
            "Espaço insuficiente: são necessários ${formatBytes(required)} livres e há ${formatBytes(available)}"
        }
        val warnings = buildList {
            val battery = context.getSystemService(BatteryManager::class.java)
            val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = battery?.isCharging == true
            if (level in 0..15 && !charging) add("Bateria baixa; conecte o carregador para vídeos longos")
            if (config.targetFps >= 120) add("120 FPS exige muito do decoder e do encoder")
            if (config.targetWidth >= 3840 || config.targetHeight >= 2160) add("4K pode aquecer o aparelho durante a exportação")
        }
        return Preflight(required, available, warnings)
    }

    fun awaitSafeTemperature(
        enabled: Boolean,
        cancelled: () -> Boolean,
        onWaiting: (String) -> Unit
    ) {
        if (!enabled) return
        val power = context.getSystemService(PowerManager::class.java)
        var waitedMs = 0L
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (cancelled()) throw InterruptedException("Otimização cancelada")
            onWaiting("Aparelho quente: processamento pausado; aguardando resfriamento")
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY) {
                throw IllegalStateException("Temperatura crítica. Aguarde o aparelho esfriar antes de continuar")
            }
            SystemClock.sleep(THERMAL_POLL_MS)
            waitedMs += THERMAL_POLL_MS
            if (waitedMs >= MAX_THERMAL_WAIT_MS) {
                throw IllegalStateException("O aparelho permaneceu quente por muito tempo; a otimização foi interrompida")
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    private fun estimateManualOutputBytes(input: File, config: OptimizationConfig): Long {
        if (config.bitrateMbps <= 0) return 0L
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
        if (durationMs <= 0L) return 0L
        val effectiveDurationMs = if (config.hasTrim()) {
            config.trimmedDurationUs(durationMs * 1_000L) / 1_000L
        } else {
            durationMs
        }
        val videoBytes = config.bitrateMbps.toLong() * 1_000_000L * effectiveDurationMs / 8_000L
        val audioBytes = if (config.keepAudio) AUDIO_ESTIMATE_BPS * effectiveDurationMs / 8_000L else 0L
        return ((videoBytes + audioBytes) * OUTPUT_SAFETY_PERCENT) / 100L
    }

    companion object {
        private const val MINIMUM_FREE_BYTES = 192L * 1024L * 1024L
        private const val AUDIO_ESTIMATE_BPS = 320_000L
        private const val OUTPUT_SAFETY_PERCENT = 120L
        private const val THERMAL_POLL_MS = 2_000L
        private const val MAX_THERMAL_WAIT_MS = 10L * 60L * 1_000L
    }
}
