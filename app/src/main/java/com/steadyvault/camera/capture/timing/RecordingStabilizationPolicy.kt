package com.steadyvault.camera.capture.timing

object RecordingStabilizationPolicy {
    enum class Mode { OFF, PREVIEW, EIS, OIS }

    fun resolve(
        requested: Mode,
        fps: Int,
        highSpeed: Boolean,
        previewSupported: Boolean,
        eisSupported: Boolean,
        oisSupported: Boolean
    ): Mode {
        if (fps <= 0) return Mode.OFF
        // Consulte os metadados apenas como confirmação/diagnóstico. O resultado
        // nunca substitui o modo escolhido; a tentativa real no CaptureRequest é
        // quem decide se a HAL aceita a configuração.
        val metadataConfirmsRequest = when (requested) {
            Mode.OFF -> true
            Mode.PREVIEW -> !highSpeed && previewSupported
            Mode.EIS -> eisSupported
            Mode.OIS -> oisSupported
        }
        return if (metadataConfirmsRequest) requested else requested
    }
}

