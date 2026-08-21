package com.steadyvault.camera.core.validation

import kotlin.math.abs

object UiBehaviorRules {
    private val recordingBusyPrefixes = listOf(
        "Preparando gravação",
        "Preparando 4K",
        "Preparando 1080",
        "Preparando 720",
        "Procurando",
        "Validando",
        "Abrindo câmera",
        "Configurando",
        "Estabilizando",
        "Iniciando gravação",
        "Gravando",
        "Trecho salvo no cofre",
        "Recuperando a gravação",
        "Continuando a gravação",
        "Retomando a gravação",
        "Finalizando",
        "Salvando original no cofre",
        "Removendo intervalos",
        "Criando vídeo",
        "ABRINDO",
        "VÍDEO ORIGINAL",
        "ESPAÇO",
        "FFMPEG",
        "QUALIDADE",
        "ENCODER",
        "VALIDAÇÃO",
        "ÁUDIO",
        "CORREÇÃO",
        "PUBLICANDO"
    )

    fun isRecordingBusy(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isEmpty()) return false
        if (isPhotoProgress(normalized) || isPhotoResult(normalized)) return false
        return recordingBusyPrefixes.any { normalized.startsWith(it, ignoreCase = true) }
    }

    fun isRecordingFinalizing(message: String): Boolean {
        val normalized = message.trim()
        return listOf(
            "Finalizando",
            "Salvando original no cofre",
            "Validando",
            "Removendo intervalos",
            "Criando vídeo",
            "ABRINDO",
            "VÍDEO ORIGINAL",
            "ESPAÇO",
            "FFMPEG",
            "QUALIDADE",
            "ENCODER",
            "VALIDAÇÃO",
            "ÁUDIO",
            "CORREÇÃO",
            "PUBLICANDO"
        ).any { normalized.startsWith(it, ignoreCase = true) }
    }

    fun isPhotoProgress(message: String): Boolean {
        val normalized = message.trim()
        return normalized.startsWith("Preparando foto", ignoreCase = true) ||
            normalized.startsWith("Preparando sequência", ignoreCase = true) ||
            normalized.startsWith("Capturando foto", ignoreCase = true) ||
            normalized.startsWith("Capturando sequência", ignoreCase = true) ||
            normalized.startsWith("Iniciando sequência", ignoreCase = true)
    }

    fun isPhotoResult(message: String): Boolean {
        val normalized = message.trim()
        return normalized.startsWith("Foto salva", ignoreCase = true) ||
            normalized.startsWith("Fotos salvas", ignoreCase = true) ||
            normalized.startsWith("Foto não capturada", ignoreCase = true) ||
            normalized.startsWith("Sequência concluída", ignoreCase = true)
    }

    fun nextDoubleTapScale(currentScale: Float, maxScale: Float): Float {
        val safeMax = maxScale.coerceAtLeast(1f)
        val current = currentScale.coerceIn(1f, safeMax)
        val first = minOf(2.5f, safeMax)
        val second = minOf(4.5f, safeMax)
        return when {
            current < first - 0.15f -> first
            current < second - 0.15f && second > first + 0.15f -> second
            else -> 1f
        }
    }

    fun zoomStep(currentScale: Float, delta: Float, maxScale: Float): Float {
        val safeMax = maxScale.coerceAtLeast(1f)
        val requested = currentScale + delta
        return requested.coerceIn(1f, safeMax).let { if (abs(it - 1f) < 0.02f) 1f else it }
    }

    fun sanitizedPlaybackSpeed(value: Float): Float {
        val allowed = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        return allowed.minByOrNull { abs(it - value) } ?: 1f
    }
}
