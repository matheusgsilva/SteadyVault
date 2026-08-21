package com.steadyvault.camera.processing.validation

import com.steadyvault.camera.processing.model.FrameRepairMode
import com.steadyvault.camera.processing.model.OptimizationConfig
import com.steadyvault.camera.processing.model.OutputCodec

data class OptimizationValidation(
    val valid: Boolean,
    val error: String? = null,
    val warnings: List<String> = emptyList()
)

object OptimizationConfigValidator {
    fun validate(
        config: OptimizationConfig,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFps: Int,
        sourceHdrHlg10: Boolean
    ): OptimizationValidation {
        val normalized = config.normalized()
        val warnings = mutableListOf<String>()

        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceFps <= 0) {
            return OptimizationValidation(false, "Não foi possível confirmar resolução e FPS do vídeo original.")
        }

        if (sourceHdrHlg10) {
            val changesPixels = normalized.filters.enabled ||
                normalized.frameRepair == FrameRepairMode.ADAPTIVE_BLEND ||
                normalized.targetWidth > 0 || normalized.targetHeight > 0 ||
                normalized.targetFps > 0 || normalized.codec != OutputCodec.SOURCE || normalized.hasTrim()
            if (changesPixels) {
                return OptimizationValidation(
                    false,
                    "Vídeo HLG10: use 'Somente reparar', mantenha resolução/FPS/codec, sem corte e com filtros desligados para preservar cores e contraste."
                )
            }
        }

        if ((normalized.targetWidth == 0) != (normalized.targetHeight == 0)) {
            return OptimizationValidation(false, "A resolução de saída precisa ter largura e altura válidas.")
        }

        if (normalized.targetWidth > 0 && normalized.targetHeight > 0) {
            val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
            val targetPixels = normalized.targetWidth.toLong() * normalized.targetHeight.toLong()
            if (targetPixels > sourcePixels) {
                warnings += "Aumentar a resolução não cria detalhes novos e deixa o processamento mais pesado."
            }
        }

        if (normalized.targetFps > sourceFps && normalized.frameRepair == FrameRepairMode.NONE) {
            warnings += "O FPS escolhido é maior que o original, mas o preenchimento de lacunas está desligado."
        }

        if (normalized.frameRepair == FrameRepairMode.ADAPTIVE_BLEND && normalized.maxInterpolatedFramesPerGap > 12) {
            warnings += "Misturar muitos quadros por falha pode criar rastros em movimentos rápidos."
        }

        if (normalized.bitrateMbps in 1..3) {
            return OptimizationValidation(false, "O bitrate informado é baixo demais para uma saída estável. Use 0 para automático ou pelo menos 4 Mbps.")
        }

        if (normalized.targetFps >= 120 && normalized.targetWidth >= 3840) {
            warnings += "4K a 120 FPS exige muito do encoder e pode ser reduzido automaticamente pelo aparelho."
        }

        return OptimizationValidation(true, warnings = warnings)
    }
}
