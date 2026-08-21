package com.steadyvault.camera.processing.validation

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.steadyvault.camera.processing.analysis.VideoAnalysis
import java.io.File
import kotlin.math.abs

object VideoValidator {
    data class Result(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val videoMime: String,
        val hasAudio: Boolean,
        val rotation: Int
    )

    fun validate(output: File, source: VideoAnalysis, expectedDurationUs: Long? = null): Result {
        require(output.isFile && output.length() >= MINIMUM_FILE_BYTES) { "O arquivo otimizado está vazio ou incompleto" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            var videoTrack = -1
            var width = 0
            var height = 0
            var durationUs = 0L
            var videoMime = ""
            var hasAudio = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    videoTrack < 0 && mime.startsWith("video/") -> {
                        videoTrack = index
                        videoMime = mime
                        width = integer(format, MediaFormat.KEY_WIDTH)
                        height = integer(format, MediaFormat.KEY_HEIGHT)
                        durationUs = long(format, MediaFormat.KEY_DURATION)
                    }
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            require(videoTrack >= 0) { "A saída não contém uma faixa de vídeo" }
            require(width > 0 && height > 0) { "A saída não possui dimensões válidas" }
            extractor.selectTrack(videoTrack)
            require(extractor.sampleTrackIndex == videoTrack && extractor.sampleTime >= 0L) { "A saída não contém quadros legíveis" }

            if (durationUs <= 0L) durationUs = metadataDurationUs(output)
            require(durationUs > 0L) { "A duração da saída não pôde ser validada" }
            val rotation = metadataRotation(output)
            require(normalizeRotation(rotation) == normalizeRotation(source.rotation)) {
                "A orientação da saída não corresponde ao vídeo original"
            }
            val durationReference = expectedDurationUs?.takeIf { it >= MINIMUM_DURATION_FOR_TOLERANCE_US } ?: source.durationUs
            if (durationReference >= MINIMUM_DURATION_FOR_TOLERANCE_US) {
                val difference = abs(durationUs - durationReference).toDouble() / durationReference.toDouble()
                require(difference <= MAXIMUM_DURATION_DIFFERENCE) {
                    val label = if (expectedDurationUs != null) "do corte configurado" else "do original"
                    "A duração da saída divergiu $label (${(difference * 100).toInt()}%)"
                }
            }
            return Result(width, height, durationUs, videoMime, hasAudio, rotation)
        } finally {
            extractor.release()
        }
    }

    private fun metadataDurationUs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000L
        } finally {
            retriever.release()
        }
    }

    private fun metadataRotation(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    private fun normalizeRotation(value: Int): Int = ((value % 360) + 360) % 360

    private fun integer(format: MediaFormat, key: String): Int = runCatching {
        if (format.containsKey(key)) format.getInteger(key) else 0
    }.getOrDefault(0)

    private fun long(format: MediaFormat, key: String): Long = runCatching {
        if (format.containsKey(key)) format.getLong(key) else 0L
    }.getOrDefault(0L)

    private const val MINIMUM_FILE_BYTES = 1_024L
    private const val MINIMUM_DURATION_FOR_TOLERANCE_US = 2_000_000L
    private const val MAXIMUM_DURATION_DIFFERENCE = 0.18
}
