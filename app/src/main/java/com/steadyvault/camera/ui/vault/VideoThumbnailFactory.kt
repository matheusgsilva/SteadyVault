package com.steadyvault.camera.ui.vault

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import java.io.File

/** Gera miniaturas completas, leves e com a orientação real do vídeo. */
internal object VideoThumbnailFactory {
    fun loadFilmstrip(
        file: File,
        durationMs: Int,
        frameCount: Int,
        maximumSide: Int
    ): List<Bitmap> {
        if (!file.isFile || durationMs <= 0 || frameCount <= 0) return emptyList()
        val frames = ArrayList<Bitmap>(frameCount)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: maximumSide
            val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: maximumSide
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (requestWidth, requestHeight) = fitInside(encodedWidth, encodedHeight, maximumSide)
            val lastTimeUs = (durationMs.toLong() - 1L).coerceAtLeast(0L) * 1_000L
            repeat(frameCount) { index ->
                val timeUs = if (frameCount == 1) 0L else lastTimeUs * index / (frameCount - 1L)
                val decoded = runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        requestWidth,
                        requestHeight
                    ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        requestWidth,
                        requestHeight
                    )
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: return@repeat
                val oriented = runCatching {
                    orientAndFit(decoded, encodedWidth, encodedHeight, rotation, maximumSide)
                }.getOrNull()
                if (oriented != null) frames += oriented
                else if (!decoded.isRecycled) decoded.recycle()
            }
        } finally {
            runCatching { retriever.release() }
        }
        return frames
    }

    internal fun orientAndFit(
        source: Bitmap,
        encodedWidth: Int,
        encodedHeight: Int,
        metadataRotation: Int,
        maximumSide: Int
    ): Bitmap {
        val rotation = rotationToApply(
            frameWidth = source.width,
            frameHeight = source.height,
            encodedWidth = encodedWidth,
            encodedHeight = encodedHeight,
            metadataRotation = metadataRotation
        )
        val oriented = if (rotation != 0) {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true
            ).also { if (it !== source && !source.isRecycled) source.recycle() }
        } else source
        val (targetWidth, targetHeight) = fitInside(oriented.width, oriented.height, maximumSide)
        if (oriented.width == targetWidth && oriented.height == targetHeight) return oriented
        return Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true).also {
            if (it !== oriented && !oriented.isRecycled) oriented.recycle()
        }
    }

    /**
     * Alguns aparelhos já devolvem o frame rotacionado pelo MediaMetadataRetriever.
     * Só aplicamos 90/270 graus quando o bitmap ainda conserva a orientação codificada,
     * evitando a rotação duplicada observada em vídeos verticais.
     */
    internal fun rotationToApply(
        frameWidth: Int,
        frameHeight: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        metadataRotation: Int
    ): Int {
        val normalized = ((metadataRotation % 360) + 360) % 360
        if (normalized != 90 && normalized != 270) return normalized
        val framePortrait = frameHeight > frameWidth
        val encodedPortrait = encodedHeight > encodedWidth
        return if (framePortrait == encodedPortrait) normalized else 0
    }

    internal fun fitInside(width: Int, height: Int, maximumSide: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val limit = maximumSide.coerceAtLeast(1)
        val scale = minOf(1.0, limit.toDouble() / safeWidth, limit.toDouble() / safeHeight)
        return (safeWidth * scale).toInt().coerceAtLeast(1) to
            (safeHeight * scale).toInt().coerceAtLeast(1)
    }
}
