package com.steadyvault.camera.capture.recorder

/**
 * Mantém o MP4 fechado até existir um IDR pertencente à época já confirmada da gravação.
 *
 * O gate não olha pixels, tamanho do sample, FPS medido ou luminosidade. Portanto uma
 * cena realmente escura segue exatamente o mesmo caminho de qualquer outra cena. Um
 * GOP que comprovadamente começou antes do commit pode ficar retido por pouco tempo
 * como fallback; se o pedido de um novo IDR não for atendido, esse GOP válido é
 * publicado em vez de cancelar ou deixar a gravação sem vídeo.
 */
internal class StartupVideoGate(
    private val maxFramesBeforeFallback: Int,
    private val maxWaitBeforeFallbackUs: Long,
    private val maxBufferedBytes: Long
) {
    init {
        require(maxFramesBeforeFallback > 0)
        require(maxWaitBeforeFallbackUs > 0L)
        require(maxBufferedBytes > 0L)
    }

    enum class Action {
        HOLD,
        START_WITH_CURRENT_KEY_FRAME,
        START_WITH_BUFFERED_GOP,
        WRITE_CURRENT
    }

    data class Decision(
        val action: Action,
        val requestSyncFrame: Boolean = false
    )

    private var open = false
    private var samplesSinceRequest = 0
    private var requestAnchorPtsUs = Long.MIN_VALUE

    fun onSample(
        isKeyFrame: Boolean,
        clearlyBeforeCommit: Boolean,
        sourcePtsUs: Long,
        bufferedBytes: Long,
        hasBufferedKeyFrame: Boolean
    ): Decision {
        if (open) return Decision(Action.WRITE_CURRENT)

        if (isKeyFrame && !clearlyBeforeCommit) {
            open = true
            return Decision(Action.START_WITH_CURRENT_KEY_FRAME)
        }

        if (requestAnchorPtsUs == Long.MIN_VALUE) requestAnchorPtsUs = sourcePtsUs
        samplesSinceRequest++
        val waitedUs = (sourcePtsUs - requestAnchorPtsUs).coerceAtLeast(0L)
        val fallbackDue = samplesSinceRequest >= maxFramesBeforeFallback ||
            waitedUs >= maxWaitBeforeFallbackUs ||
            bufferedBytes >= maxBufferedBytes

        if (!fallbackDue) return Decision(Action.HOLD)
        if (hasBufferedKeyFrame) {
            open = true
            return Decision(Action.START_WITH_BUFFERED_GOP)
        }

        // Sem um IDR não existe começo decodificável para publicar. Renova o pedido,
        // mas não encerra a gravação e não transforma um P-frame em falso keyframe.
        samplesSinceRequest = 0
        requestAnchorPtsUs = sourcePtsUs
        return Decision(Action.HOLD, requestSyncFrame = true)
    }

    fun forceFallback(hasBufferedKeyFrame: Boolean): Decision {
        if (open) return Decision(Action.WRITE_CURRENT)
        if (hasBufferedKeyFrame) {
            open = true
            return Decision(Action.START_WITH_BUFFERED_GOP)
        }
        return Decision(Action.HOLD, requestSyncFrame = true)
    }
}
