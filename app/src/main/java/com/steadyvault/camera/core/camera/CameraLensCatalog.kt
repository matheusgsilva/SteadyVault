package com.steadyvault.camera.core.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import com.steadyvault.camera.core.settings.CaptureSettings
import kotlin.math.abs

object CameraLensCatalog {
    data class Option(
        val id: String,
        val label: String,
        val shortLabel: String,
        val facing: Int,
        val logical: Boolean,
        val equivalentFocalMm: Float?,
        val minZoom: Float,
        val maxZoom: Float
    ) {
        val isFront: Boolean
            get() = facing == CameraCharacteristics.LENS_FACING_FRONT

        val isBack: Boolean
            get() = facing == CameraCharacteristics.LENS_FACING_BACK
    }

    fun options(context: Context): List<Option> {
        val manager = context.getSystemService(CameraManager::class.java)
        val raw = runCatching {
            manager.cameraIdList.mapNotNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@mapNotNull null
                val privateSizes = runCatching { map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                if (privateSizes.isEmpty()) return@mapNotNull null

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_EXTERNAL
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: intArrayOf()
                val logical = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
                val equivalent = equivalentFocalLength(characteristics)
                val zoomRange = zoomRange(characteristics)
                RawOption(id, facing, logical, equivalent, zoomRange.first, zoomRange.second)
            }
        }.getOrDefault(emptyList())

        val cameraLabels = raw.map(::defaultLabel)
        val counts = cameraLabels.groupingBy { it }.eachCount()
        val used = mutableMapOf<String, Int>()
        return raw.mapIndexed { index, item ->
            val cameraLabel = cameraLabels[index]
            val occurrence = (used[cameraLabel] ?: 0) + 1
            used[cameraLabel] = occurrence
            val visibleLabel = if ((counts[cameraLabel] ?: 0) > 1) "$cameraLabel $occurrence" else cameraLabel
            Option(
                id = item.id,
                label = visibleLabel,
                shortLabel = shortLabel(visibleLabel),
                facing = item.facing,
                logical = item.logical,
                equivalentFocalMm = item.equivalentFocalMm,
                minZoom = item.minZoom,
                maxZoom = item.maxZoom
            )
        }.sortedWith(
            compareBy<Option> { facingRank(it.facing) }
                .thenBy { lensRank(it) }
                .thenBy { it.id }
        )
    }

    fun resolveCameraId(context: Context, requestedId: String?, resolution: String): String? {
        val available = options(context)
        requestedId?.takeIf { requested -> available.any { it.id == requested } }?.let { return it }
        val manager = context.getSystemService(CameraManager::class.java)
        val requestedPixels = when (resolution) {
            CaptureSettings.RESOLUTION_8K -> 7680L * 4320L
            CaptureSettings.RESOLUTION_4K -> 3840L * 2160L
            CaptureSettings.RESOLUTION_1080P -> 1920L * 1080L
            CaptureSettings.RESOLUTION_720P -> 1280L * 720L
            else -> 1920L * 1080L
        }
        return available
            .asSequence()
            .filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .maxByOrNull { option ->
                val characteristics = runCatching { manager.getCameraCharacteristics(option.id) }.getOrNull()
                    ?: return@maxByOrNull Long.MIN_VALUE
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = runCatching { map?.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                val supportsRequested = sizes.any { it.width.toLong() * it.height == requestedPixels }
                val mainLensDistance = abs((option.equivalentFocalMm ?: 24f) - 24f)
                (if (option.logical) 8_000_000L else 0L) +
                    (if (supportsRequested) 2_000_000L else 0L) +
                    (1_500_000L - (mainLensDistance * 45_000f).toLong()).coerceAtLeast(0L) +
                    (sizes.maxOfOrNull { it.width.toLong() * it.height } ?: 0L) / 1_000L
            }
            ?.id
            ?: available.firstOrNull()?.id
    }


    fun resolveRecordingCameraId(context: Context, requestedId: String?, resolution: String): String? {
        val available = options(context)
        val requested = available.firstOrNull { it.id == requestedId }
        if (requested?.isFront == true) return requested.id

        val requestedSize = when (resolution) {
            CaptureSettings.RESOLUTION_8K -> Size(7680, 4320)
            CaptureSettings.RESOLUTION_4K -> Size(3840, 2160)
            CaptureSettings.RESOLUTION_1080P -> Size(1920, 1080)
            CaptureSettings.RESOLUTION_720P -> Size(1280, 720)
            else -> Size(1920, 1080)
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val logicalBack = available
            .asSequence()
            .filter { it.isBack && it.logical }
            .sortedWith(
                compareByDescending<Option> { option ->
                    val characteristics = runCatching { manager.getCameraCharacteristics(option.id) }.getOrNull()
                    val sizes = runCatching {
                        characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(MediaRecorder::class.java)
                            ?.toList().orEmpty()
                    }.getOrDefault(emptyList())
                    if (requestedSize in sizes) 1 else 0
                }.thenBy { abs((it.equivalentFocalMm ?: 24f) - 24f) }
            )
            .firstOrNull()
        return logicalBack?.id ?: resolveCameraId(context, requestedId, resolution)
    }
    fun labelFor(context: Context, cameraId: String?): String {
        if (cameraId.isNullOrBlank()) return "Automática"
        return options(context).firstOrNull { it.id == cameraId }?.label ?: "Câmera"
    }

    fun isFront(context: Context, cameraId: String?): Boolean =
        options(context).firstOrNull { it.id == cameraId }?.isFront == true

    /**
     * Atalhos ópticos usados no preview. Quando o fabricante expõe as lentes
     * físicas, o atalho seleciona a câmera correspondente; em uma câmera lógica,
     * o HAL continua livre para fazer a transição óptica pelo zoom.
     */
    fun shortcutRatio(option: Option): Float = when {
        option.isFront -> 1f
        option.logical -> 1f
        (option.equivalentFocalMm ?: 24f) < 19f -> 0.6f
        (option.equivalentFocalMm ?: 24f) < 38f -> 1f
        (option.equivalentFocalMm ?: 24f) < 90f -> 3f
        else -> 5f
    }

    fun optionForShortcut(available: List<Option>, shortcut: Float): Option? {
        val back = available.filter { it.isBack }
        if (back.isEmpty()) return null
        back.firstOrNull { option ->
            option.logical && shortcut >= option.minZoom - 0.02f && shortcut <= option.maxZoom + 0.02f
        }?.let { return it }

        return when {
            shortcut < 0.8f -> back.filterNot { it.logical }.filter { (it.equivalentFocalMm ?: 24f) < 19f }
                .minByOrNull { it.equivalentFocalMm ?: Float.MAX_VALUE }
            shortcut < 2f -> back.filterNot { it.logical }.minByOrNull { abs((it.equivalentFocalMm ?: 24f) - 24f) }
            shortcut < 4f -> back.filterNot { it.logical }
                .filter { (it.equivalentFocalMm ?: 24f) >= 38f && (it.equivalentFocalMm ?: 24f) < 90f }
                .minByOrNull { abs((it.equivalentFocalMm ?: 70f) - 70f) }
            else -> back.filterNot { it.logical }.filter { (it.equivalentFocalMm ?: 24f) >= 90f }
                .maxByOrNull { it.equivalentFocalMm ?: 0f }
        }
    }

    fun previewSize(
        context: Context,
        cameraId: String?,
        resolution: String,
        photoMode: Boolean,
        highSpeed: Boolean
    ): Size? {
        val resolvedId = resolveCameraId(context, cameraId, resolution) ?: return null
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = runCatching { manager.getCameraCharacteristics(resolvedId) }.getOrNull()
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val candidates = if (highSpeed) {
            runCatching { map.highSpeedVideoSizes?.toList().orEmpty() }.getOrDefault(emptyList())
        } else {
            runCatching { map.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() }
                .getOrDefault(emptyList())
        }
        if (candidates.isEmpty()) return null
        // O SurfaceView mantém a mesma proporção em FOTO e VÍDEO. A foto final
        // continua sendo capturada pelo ImageReader na resolução/proporção própria,
        // mas o fluxo visual fica em 16:9 para não comprimir a imagem lateralmente
        // durante a troca de função.
        val target = when {
            highSpeed -> Size(1280, 720)      // 16:9 para modo de alta cadência legado
            photoMode -> Size(1920, 1080)     // 16:9 estável no modo FOTO
            else -> Size(1920, 1080)          // 16:9 para vídeo normal
        }
        val targetAspect = target.width.toDouble() / target.height
        val targetArea = target.width.toLong() * target.height
        val valid = candidates.filter { it.width > 0 && it.height > 0 }
        val aspectMatched = valid.filter { size ->
            val aspect = size.width.toDouble() / size.height
            abs(aspect - targetAspect) <= 0.03
        }.ifEmpty { valid }
        return aspectMatched.minByOrNull { size ->
            val aspect = size.width.toDouble() / size.height
            val aspectPenalty = (abs(aspect - targetAspect) * 100_000_000.0).toLong()
            val areaPenalty = abs(size.width.toLong() * size.height - targetArea)
            aspectPenalty + areaPenalty
        }
    }

    private data class RawOption(
        val id: String,
        val facing: Int,
        val logical: Boolean,
        val equivalentFocalMm: Float?,
        val minZoom: Float,
        val maxZoom: Float
    )

    private fun equivalentFocalLength(characteristics: CameraCharacteristics): Float? {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (sensor.width <= 0f) return null
        val focals = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it > 0f }
            .orEmpty()
        if (focals.isEmpty()) return null
        val representative = if (focals.size == 1) focals.first() else focals.minByOrNull { focal ->
            abs(focal * 36f / sensor.width - 24f)
        } ?: focals.first()
        return representative * 36f / sensor.width
    }

    private fun zoomRange(characteristics: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                return range.lower.coerceAtLeast(0.1f) to range.upper.coerceAtLeast(range.lower)
            }
        }
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.takeIf { it.isFinite() && it >= 1f } ?: 1f
        return 1f to max
    }

    private fun defaultLabel(option: RawOption): String = when (option.facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "Selfie"
        CameraCharacteristics.LENS_FACING_BACK -> when {
            option.logical -> "Traseira"
            (option.equivalentFocalMm ?: 24f) < 19f -> "Ultra-wide"
            (option.equivalentFocalMm ?: 24f) < 38f -> "Principal"
            (option.equivalentFocalMm ?: 24f) < 90f -> "Tele"
            else -> "Super tele"
        }
        else -> "Externa"
    }

    private fun shortLabel(label: String): String = when {
        label.startsWith("Ultra-wide") -> label.replace("Ultra-wide", "Ultra")
        label.startsWith("Super tele") -> label.replace("Super tele", "S. tele")
        else -> label
    }

    private fun facingRank(facing: Int): Int = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> 0
        CameraCharacteristics.LENS_FACING_FRONT -> 1
        else -> 2
    }

    private fun lensRank(option: Option): Int = when {
        option.logical -> 10
        (option.equivalentFocalMm ?: 24f) < 19f -> 0
        (option.equivalentFocalMm ?: 24f) < 38f -> 20
        (option.equivalentFocalMm ?: 24f) < 90f -> 30
        else -> 40
    }
}
