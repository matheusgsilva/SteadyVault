package com.steadyvault.camera.core.state

/**
 * Último fps efetivamente gravado no arquivo (medido pelos PTS escritos). Vive só em memória, no
 * mesmo processo do serviço e da UI, e alimenta o mostrador de teste sem I/O nem polling.
 */
object LastRecordingStats {
    @Volatile var fps: Double = 0.0
        private set
    @Volatile var nominalFps: Int = 0
        private set

    fun record(recordedFps: Double, nominal: Int) {
        if (recordedFps <= 0.0 || nominal <= 0) return
        fps = recordedFps
        nominalFps = nominal
    }
}
