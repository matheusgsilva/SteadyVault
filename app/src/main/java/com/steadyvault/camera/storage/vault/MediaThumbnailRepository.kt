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
    private const val SHARPEST_OF_FRAMES = 3
    private val warmupExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-ThumbnailWarmup").apply { priority = Thread.MIN_PRIORITY }
    }
    private val warming = Collections.synchronizedSet(mutableSetOf<String>())
    private val cacheGeneration = AtomicLong(0L)
    private val memoryCaches = Collections.synchronizedMap(WeakHashMap<MemoryCacheHandle, Unit>())
    // Bitmaps de "carregando/falhou": quem chama não pode guardá-los no cache de memória, senão a
    // miniatura ficava presa no ícone até fechar a tela.
    private val placeholders = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Bitmap, Boolean>()))
    private const val CACHE_VERSION = 2
    private const val KEY_CACHE_VERSION = "thumbnail_cache_version"
    @Volatile private var versionChecked = false

    /** true se [bitmap] é só o ícone provisório (miniatura real ainda não disponível). */
    fun isPlaceholder(bitmap: Bitmap): Boolean = placeholders.contains(bitmap)

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
        // Durante a gravação, nem a decodificação de thumbnails já cacheadas deve
        // disputar CPU/memória com Camera2/MediaCodec. A grade recebe placeholder e volta
        // a carregar normalmente assim que a captura termina.
        if (VaultStartupCoordinator.isCapturePriorityActive(context)) return placeholder(side, video)
        purgeOldCacheOnce(context)
        val cache = cacheFile(context, file, side)
        decodeCached(cache, video)?.let { return it }

        var generated = runCatching {
            if (video) videoThumbnail(file, side) else imageThumbnail(file, side)
        }.getOrNull()
        if (generated == null || generated.isRecycled) {
            // Falha passageira (decodificador ocupado, arquivo ainda sendo fechado): tenta de novo.
            runCatching { Thread.sleep(350L) }
            if (expectedGeneration == cacheGeneration.get() && !VaultStartupCoordinator.isCapturePriorityActive(context)) {
                generated = runCatching {
                    if (video) videoThumbnail(file, side) else imageThumbnail(file, side)
                }.getOrNull()
            }
        }

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
        if (VaultStartupCoordinator.isCapturePriorityActive(appContext)) return
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

    /**
     * Libera pressão de memória antes de uma gravação e invalida warm-ups que começaram
     * antes de a câmera assumir prioridade. O cache em disco é preservado.
     */
    fun prepareForCapture() {
        cacheGeneration.incrementAndGet()
        warming.clear()
        memoryCacheHandles().forEach { cache -> runCatching { cache.clearMemoryCache() } }
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

    /** Miniaturas antigas (versões borradas) são descartadas uma vez; ficam só as novas. */
    private fun purgeOldCacheOnce(context: Context) {
        if (versionChecked) return
        versionChecked = true
        val preferences = context.getSharedPreferences(CACHE_INDEX_PREFS, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_CACHE_VERSION, 0) >= CACHE_VERSION) return
        cacheDirectory(context).listFiles().orEmpty().forEach { runCatching { it.delete() } }
        preferences.edit().clear().putInt(KEY_CACHE_VERSION, CACHE_VERSION).apply()
    }

    private fun memoryCacheHandles(): List<MemoryCacheHandle> =
        synchronized(memoryCaches) { memoryCaches.keys.toList() }

    private fun cacheDirectory(context: Context): File =
        File(context.filesDir, CACHE_DIRECTORY).apply { mkdirs() }

    private fun cacheFile(context: Context, file: File, maximumSide: Int): File =
        File(cacheDirectory(context), "${fingerprint(file)}_${maximumSide}_v$CACHE_VERSION.jpg")

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

    private fun decodeCached(file: File, video: Boolean): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 && !it.isRecycled }
        if (bitmap == null) {
            file.delete()
            return null
        }
        if (video && isLikelyBlackFrame(bitmap)) {
            runCatching { bitmap.recycle() }
            file.delete()
            return null
        }
        return bitmap
    }

    private fun persist(target: File, bitmap: Bitmap) {
        runCatching {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
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
        // O ThumbnailUtils devolve o frame ENCAIXADO em side x side (ex.: 288x512 num vídeo
        // vertical) e o recorte quadrado ampliava isso ~1,8x: miniatura borrada. O retriever abaixo
        // pede o tamanho de COBERTURA e escolhe o quadro mais nítido entre vários instantes.
        val retrieverFrame = videoThumbnailFromRetriever(file, maximumSide)
        if (retrieverFrame != null && !retrieverFrame.isRecycled) {
            if (!isLikelyBlackFrame(retrieverFrame)) return retrieverFrame
            runCatching { retrieverFrame.recycle() }
        }

        val platformFrame = videoThumbnailFromPlatform(file, maximumSide)
        if (platformFrame != null && !platformFrame.isRecycled) {
            if (!isLikelyBlackFrame(platformFrame)) return centerCropSquare(platformFrame, maximumSide)
            runCatching { platformFrame.recycle() }
        }

        return null
    }

    /** Energia de gradiente da luma no centro do quadro: maior = mais nítido (menos borrão). */
    private fun sharpnessScore(bitmap: Bitmap): Double {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return 0.0
        val w = minOf(bitmap.width, 256)
        val h = minOf(bitmap.height, 256)
        val left = (bitmap.width - w) / 2
        val top = (bitmap.height - h) / 2
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        fun luma(p: Int) = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
        var sum = 0.0
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val c = luma(pixels[y * w + x])
                sum += kotlin.math.abs(c - luma(pixels[y * w + x + 1])) + kotlin.math.abs(c - luma(pixels[(y + 1) * w + x]))
            }
        }
        return sum
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
                    add(minOf(1_500_000L, durationMs * 1_000L))
                }
                add(0L)
                add(-1L)
            }
            val options = intArrayOf(
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            var frame: Bitmap? = null
            var bestScore = -1.0
            var tried = 0
            for (timeUs in candidateTimes) {
                // Até 3 quadros válidos; fica o mais nítido (evita pegar um quadro em movimento).
                if (tried >= SHARPEST_OF_FRAMES && frame != null) break
                var candidate: Bitmap? = null
                for (option in options) {
                    candidate = runCatching {
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
                    if (candidate != null && !candidate!!.isRecycled) break
                }
                val usable = candidate?.takeIf { !it.isRecycled && !isLikelyBlackFrame(it) }
                if (usable == null) {
                    candidate?.takeIf { !it.isRecycled }?.recycle()
                    continue
                }
                tried++
                val score = sharpnessScore(usable)
                if (score > bestScore) {
                    frame?.takeIf { !it.isRecycled }?.recycle()
                    frame = usable
                    bestScore = score
                } else {
                    usable.recycle()
                }
            }
            frame?.let { orientAndCrop(it, encodedWidth, encodedHeight, rotation, maximumSide) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isLikelyBlackFrame(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return true
        val xs = intArrayOf(bitmap.width / 6, bitmap.width / 3, bitmap.width / 2, (bitmap.width * 2) / 3, (bitmap.width * 5) / 6)
        val ys = intArrayOf(bitmap.height / 6, bitmap.height / 3, bitmap.height / 2, (bitmap.height * 2) / 3, (bitmap.height * 5) / 6)
        var lumaTotal = 0.0
        var lumaMax = 0.0
        var samples = 0
        ys.forEach { yRaw ->
            val y = yRaw.coerceIn(0, bitmap.height - 1)
            xs.forEach { xRaw ->
                val x = xRaw.coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel) / 255.0
                val luma = (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) * alpha
                lumaTotal += luma
                if (luma > lumaMax) lumaMax = luma
                samples++
            }
        }
        if (samples <= 0) return true
        val averageLuma = lumaTotal / samples.toDouble()
        return averageLuma < 14.0 && lumaMax < 32.0
    }

    private fun videoThumbnailFromPlatform(file: File, maximumSide: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                ThumbnailUtils.createVideoThumbnail(
                    file,
                    Size(maximumSide.coerceAtLeast(128) * 2, maximumSide.coerceAtLeast(128) * 2),
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
            placeholders.add(bitmap)
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
