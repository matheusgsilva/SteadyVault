package com.steadyvault.camera.storage.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.util.Size
import android.os.Build
import android.media.ThumbnailUtils
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Gera e mantém miniaturas persistentes para fotos e vídeos de todos os cofres.
 * A chave usa caminho/tamanho/data para abrir rápido mesmo com milhares de mídias.
 */
object MediaThumbnailRepository {
    interface MemoryCacheHandle {
        fun memoryBytes(): Long
        fun clearMemoryCache()
        fun removeThumbnail(file: File) = clearMemoryCache()
    }

    data class CacheStats(
        val diskFiles: Int,
        val diskBytes: Long,
        val memoryBytes: Long,
        val memoryCaches: Int
    )

    data class CacheCleanupResult(
        val files: Int,
        val bytes: Long,
        val memoryBytes: Long
    )

    private const val CACHE_DIRECTORY = "SteadyVaultThumbnails"
    private const val CACHE_INDEX_PREFS = "steadyvault_thumbnail_index"
    private const val DEFAULT_MAX_SIDE = 512
    private val warmupExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-ThumbnailWarmup").apply { priority = Thread.MIN_PRIORITY }
    }
    private val warming = Collections.synchronizedSet(mutableSetOf<String>())
    private val cacheGeneration = AtomicLong(0L)
    private val memoryCaches = Collections.synchronizedMap(WeakHashMap<MemoryCacheHandle, Unit>())

    fun load(
        context: Context,
        file: File,
        video: Boolean,
        maximumSide: Int = DEFAULT_MAX_SIDE
    ): Bitmap = loadAtGeneration(context, file, video, maximumSide, cacheGeneration.get())

    private fun loadAtGeneration(
        context: Context,
        file: File,
        video: Boolean,
        maximumSide: Int,
        expectedGeneration: Long
    ): Bitmap {
        val side = maximumSide.coerceIn(192, 1024)
        if (expectedGeneration != cacheGeneration.get()) return placeholder(side, video)
        if (!file.isFile || file.length() <= 0L) return placeholder(side, video)
        val cache = cacheFile(context, file, side)
        decodeCached(cache)?.let { return it }

        val generated = runCatching {
            if (video) videoThumbnail(file, side) else imageThumbnail(file, side)
        }.getOrNull()

        if (generated != null && !generated.isRecycled) {
            if (expectedGeneration == cacheGeneration.get()) {
                persist(cache, generated)
                runCatching { rememberCacheFile(context, file, cache) }
            }
            return generated
        }
        return placeholder(side, video)
    }

    fun warmUp(context: Context, file: File, video: Boolean, maximumSide: Int = DEFAULT_MAX_SIDE) {
        val appContext = context.applicationContext
        val key = runCatching { "${fingerprint(file)}_${maximumSide.coerceIn(192, 1024)}" }.getOrNull() ?: return
        if (!warming.add(key)) return
        val generation = cacheGeneration.get()
        runCatching {
            warmupExecutor.execute {
                try {
                    val bitmap = loadAtGeneration(appContext, file, video, maximumSide, generation)
                    if (!bitmap.isRecycled) bitmap.recycle()
                } finally {
                    warming.remove(key)
                }
            }
        }.onFailure { warming.remove(key) }
    }

    fun remove(context: Context, file: File) {
        val prefix = runCatching { fingerprint(file) }.getOrNull() ?: return
        val preferences = context.getSharedPreferences(CACHE_INDEX_PREFS, Context.MODE_PRIVATE)
        val key = cacheIndexKey(prefix)
        val indexed = preferences.getStringSet(key, emptySet()).orEmpty()
        if (indexed.isNotEmpty()) {
            indexed.forEach { name -> File(cacheDirectory(context), name).delete() }
        } else {
            cacheDirectory(context).listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(prefix) }
                .forEach { it.delete() }
        }
        preferences.edit().remove(key).apply()
        memoryCacheHandles().forEach { cache -> runCatching { cache.removeThumbnail(file) } }
    }

    fun registerMemoryCache(cache: MemoryCacheHandle) {
        memoryCaches[cache] = Unit
    }

    fun unregisterMemoryCache(cache: MemoryCacheHandle) {
        memoryCaches.remove(cache)
    }

    fun cacheStats(context: Context): CacheStats {
        var files = 0
        var bytes = 0L
        cacheDirectory(context).listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                files++
                bytes += file.length().coerceAtLeast(0L)
            }
        }
        val handles = memoryCacheHandles()
        val memoryBytes = handles.sumOf { runCatching { it.memoryBytes().coerceAtLeast(0L) }.getOrDefault(0L) }
        return CacheStats(files, bytes, memoryBytes, handles.size)
    }

    fun clearAll(context: Context): CacheCleanupResult {
        cacheGeneration.incrementAndGet()
        warming.clear()
        val handles = memoryCacheHandles()
        val memoryBytes = handles.sumOf { runCatching { it.memoryBytes().coerceAtLeast(0L) }.getOrDefault(0L) }
        handles.forEach { runCatching { it.clearMemoryCache() } }
        var files = 0
        var bytes = 0L
        cacheDirectory(context).listFiles().orEmpty().forEach { file ->
            if (file.isFile) {
                val size = file.length().coerceAtLeast(0L)
                if (file.delete()) {
                    files++
                    bytes += size
                }
            }
        }
        context.getSharedPreferences(CACHE_INDEX_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return CacheCleanupResult(files, bytes, memoryBytes)
    }

    private fun memoryCacheHandles(): List<MemoryCacheHandle> =
        synchronized(memoryCaches) { memoryCaches.keys.toList() }

    private fun cacheDirectory(context: Context): File =
        File(context.filesDir, CACHE_DIRECTORY).apply { mkdirs() }

    private fun cacheFile(context: Context, file: File, maximumSide: Int): File =
        File(cacheDirectory(context), "${fingerprint(file)}_${maximumSide}.jpg")

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Mantém a chave estável sem ler pedaços do vídeo/foto. Ler 128 KB por item
        // ficava caro em cofres com milhares de mídias antes mesmo de gerar a thumb.
        digest.update(file.absolutePath.toByteArray())
        digest.update(file.length().toString().toByteArray())
        digest.update(file.lastModified().toString().toByteArray())
        digest.update(file.extension.lowercase(Locale.US).toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun decodeCached(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 && !it.isRecycled }
            ?: run { file.delete(); null }
    }

    private fun persist(target: File, bitmap: Bitmap) {
        runCatching {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target))
            }
        }
    }

    private fun rememberCacheFile(context: Context, media: File, cache: File) {
        val prefix = fingerprint(media)
        val preferences = context.getSharedPreferences(CACHE_INDEX_PREFS, Context.MODE_PRIVATE)
        val key = cacheIndexKey(prefix)
        val names = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (names.add(cache.name)) preferences.edit().putStringSet(key, names).apply()
    }

    private fun cacheIndexKey(prefix: String): String = "thumb_$prefix"

    private fun imageThumbnail(file: File, maximumSide: Int): Bitmap? {
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (width, height) = fitForCenterCrop(
                    info.size.width.coerceAtLeast(1),
                    info.size.height.coerceAtLeast(1),
                    maximumSide
                )
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
        return decoded?.let { centerCropSquare(it, maximumSide) }
    }

    private fun videoThumbnail(file: File, maximumSide: Int): Bitmap? {
        // Em Android/Samsung recentes o ThumbnailUtils usa caminho nativo/cache do sistema
        // e costuma ser mais rápido que abrir MediaMetadataRetriever para cada item.
        val platformFrame = videoThumbnailFromPlatform(file, maximumSide)
        if (platformFrame != null && !platformFrame.isRecycled) return centerCropSquare(platformFrame, maximumSide)

        val retrieverFrame = videoThumbnailFromRetriever(file, maximumSide)
        if (retrieverFrame != null && !retrieverFrame.isRecycled) return retrieverFrame

        return null
    }

    private fun videoThumbnailFromRetriever(file: File, maximumSide: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: maximumSide
            val encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()?.coerceAtLeast(1) ?: maximumSide
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val (requestWidth, requestHeight) = fitForCenterCrop(encodedWidth, encodedHeight, maximumSide)
            val candidateTimes = linkedSetOf<Long>().apply {
                if (durationMs > 0L) {
                    add((durationMs * 1_000L / 4L).coerceAtLeast(0L))
                    add((durationMs * 1_000L / 2L).coerceAtLeast(0L))
                    add((durationMs * 3_000L / 4L).coerceAtLeast(0L))
                    add(minOf(850_000L, durationMs * 1_000L))
                }
                add(0L)
                add(-1L)
            }
            val options = intArrayOf(
                MediaMetadataRetriever.OPTION_CLOSEST,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            var frame: Bitmap? = null
            for (timeUs in candidateTimes) {
                for (option in options) {
                    frame = runCatching {
                        if (timeUs >= 0L) {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                option,
                                requestWidth,
                                requestHeight
                            ) ?: retriever.getFrameAtTime(timeUs, option)
                        } else {
                            retriever.getFrameAtTime(-1L, option)
                        }
                    }.getOrNull()
                    if (frame != null && !frame!!.isRecycled) break
                }
                if (frame != null && !frame!!.isRecycled) break
            }
            frame?.let { orientAndCrop(it, encodedWidth, encodedHeight, rotation, maximumSide) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun videoThumbnailFromPlatform(file: File, maximumSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ThumbnailUtils.createVideoThumbnail(
                    file,
                    Size(maximumSide.coerceAtLeast(128), maximumSide.coerceAtLeast(128)),
                    null
                )
            }.getOrNull()
        } else {
            null
        }


    private fun orientAndCrop(
        source: Bitmap,
        encodedWidth: Int,
        encodedHeight: Int,
        metadataRotation: Int,
        maximumSide: Int
    ): Bitmap {
        val normalized = ((metadataRotation % 360) + 360) % 360
        val rotation = if (normalized == 90 || normalized == 270) {
            val framePortrait = source.height > source.width
            val encodedPortrait = encodedHeight > encodedWidth
            if (framePortrait == encodedPortrait) normalized else 0
        } else normalized
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
        return centerCropSquare(oriented, maximumSide)
    }

    private fun centerCropSquare(source: Bitmap, maximumSide: Int): Bitmap {
        val side = maximumSide.coerceIn(192, 1024)
        if (source.width <= 0 || source.height <= 0 || source.isRecycled) return placeholder(side, false)
        val scale = maxOf(side.toDouble() / source.width, side.toDouble() / source.height)
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(side)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(side)
        val scaled = if (source.width == targetWidth && source.height == targetHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true).also {
                if (it !== source && !source.isRecycled) source.recycle()
            }
        }
        if (scaled.width == side && scaled.height == side) return scaled
        val left = ((scaled.width - side) / 2).coerceAtLeast(0)
        val top = ((scaled.height - side) / 2).coerceAtLeast(0)
        val cropWidth = side.coerceAtMost(scaled.width - left).coerceAtLeast(1)
        val cropHeight = side.coerceAtMost(scaled.height - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(scaled, left, top, cropWidth, cropHeight)
        if (scaled !== source && scaled !== cropped && !scaled.isRecycled) scaled.recycle()
        return if (cropped.width == side && cropped.height == side) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, side, side, true).also {
                if (it !== cropped && !cropped.isRecycled) cropped.recycle()
            }
        }
    }

    private fun fitForCenterCrop(width: Int, height: Int, maximumSide: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val target = maximumSide.coerceIn(192, 1024)
        val maxLongSide = target * 2
        val coverScale = maxOf(target.toDouble() / safeWidth, target.toDouble() / safeHeight)
        val cappedScale = minOf(coverScale, maxLongSide.toDouble() / maxOf(safeWidth, safeHeight))
            .coerceAtMost(1.0)
        return (safeWidth * cappedScale).roundToInt().coerceAtLeast(1) to
            (safeHeight * cappedScale).roundToInt().coerceAtLeast(1)
    }

    private fun placeholder(size: Int, video: Boolean): Bitmap {
        val side = size.coerceIn(192, 1024)
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(22, 29, 39))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(141, 169, 200)
                style = Paint.Style.STROKE
                strokeWidth = side * 0.035f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val left = side * 0.27f
            val top = side * 0.30f
            val right = side * 0.73f
            val bottom = side * 0.70f
            if (video) {
                canvas.drawRoundRect(left, top, right, bottom, side * 0.05f, side * 0.05f, paint)
                paint.style = Paint.Style.FILL
                val path = Path().apply {
                    moveTo(side * 0.46f, side * 0.40f)
                    lineTo(side * 0.46f, side * 0.60f)
                    lineTo(side * 0.62f, side * 0.50f)
                    close()
                }
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRoundRect(left, top, right, bottom, side * 0.05f, side * 0.05f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(side * 0.60f, side * 0.41f, side * 0.035f, paint)
                val path = Path().apply {
                    moveTo(side * 0.32f, side * 0.64f)
                    lineTo(side * 0.45f, side * 0.49f)
                    lineTo(side * 0.54f, side * 0.58f)
                    lineTo(side * 0.62f, side * 0.51f)
                    lineTo(side * 0.69f, side * 0.64f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}
