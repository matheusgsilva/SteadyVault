package com.steadyvault.camera.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carrega capas remotas apenas em memória enquanto o seletor de mídia está aberto.
 * Nenhum arquivo de miniatura do navegador fica salvo depois que o diálogo fecha.
 */
internal class BrowserMediaThumbnailLoader(
    private val userAgent: String,
    private val cookie: String,
    private val referrer: String
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "SteadyVault-BrowserThumbnail").apply { priority = Thread.MIN_PRIORITY }
    }
    private val generation = AtomicInteger(0)
    private val cache = object : LruCache<String, Bitmap>(MAX_MEMORY_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun bind(url: String, target: ImageView) {
        if (!isHttpUrl(url)) return
        target.tag = url
        cache.get(url)?.takeIf { !it.isRecycled }?.let {
            target.setImageBitmap(it)
            return
        }
        val expectedGeneration = generation.get()
        runCatching {
            executor.execute {
                val bitmap = loadBitmap(url)
                if (bitmap != null && expectedGeneration == generation.get()) cache.put(url, bitmap)
                mainHandler.post {
                    if (expectedGeneration == generation.get() && target.tag == url && bitmap != null && !bitmap.isRecycled) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    override fun close() {
        generation.incrementAndGet()
        executor.shutdownNow()
        cache.evictAll()
    }

    private fun loadBitmap(url: String): Bitmap? {
        val connection = runCatching {
            BrowserMediaExtractor.openConnection(
                url = url,
                userAgent = userAgent,
                cookie = cookie,
                referrer = referrer,
                accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.5"
            )
        }.getOrNull() ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType.orEmpty().substringBefore(';')
            if (contentType.isNotBlank() && !contentType.startsWith("image/", true)) return null
            val bytes = connection.inputStream.buffered().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < MAX_IMAGE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_IMAGE_BYTES - total))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
            decodeSampled(bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_THUMBNAIL_SIDE * 2 || bounds.outHeight / sample > MAX_THUMBNAIL_SIDE * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    private companion object {
        const val MAX_MEMORY_KB = 8 * 1024
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        const val MAX_THUMBNAIL_SIDE = 480
    }
}
