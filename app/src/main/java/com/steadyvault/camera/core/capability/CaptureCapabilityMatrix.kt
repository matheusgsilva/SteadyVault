package com.steadyvault.camera.core.capability

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.core.content.pm.PackageInfoCompat
import com.steadyvault.camera.capture.timing.StrictCaptureModePolicy
import com.steadyvault.camera.core.camera.OisSupportPolicy
import com.steadyvault.camera.core.camera.OpticalStabilizationCapability
import com.steadyvault.camera.core.capability.HardwareSupportPolicy.Support
import com.steadyvault.camera.core.settings.CaptureModeStore
import com.steadyvault.camera.core.settings.CaptureSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retrato único das capacidades reais que dirigem a interface e a captura.
 *
 * A análise combina câmera lógica, lentes físicas, faixas de FPS e encoders de
 * hardware. O resultado é persistido por build do aparelho. A análise completa
 * só roda quando o usuário pede para reanalisar o hardware nos Ajustes.
 */
object CaptureCapabilityMatrix {
    data class Mode(
        val cameraId: String,
        val resolution: String,
        val size: Size,
        val fps: Int,
        val highSpeed: Boolean,
        val encoderMime: String
    )

    data class CameraFeatures(
        val cameraId: String,
        val stabilization: Map<String, Support>,
        val focus: Map<String, Support>,
        val noiseReduction: Map<String, Support>,
        val edge: Map<String, Support>,
        val antibanding: Map<String, Support>,
        val whiteBalance: Map<String, Support>,
        val hdrHlg10: Support,
        val awbLock: Support,
        val manualPostProcessing: Support,
        val minimumExposureCompensation: Int,
        val maximumExposureCompensation: Int
    ) {
        fun stabilizationSupport(value: String): Support =
            stabilization[value] ?: Support.UNVERIFIED

        fun focusSupport(value: String): Support = focus[value] ?: Support.UNVERIFIED

        fun noiseReductionSupport(value: String): Support =
            noiseReduction[value] ?: Support.UNVERIFIED

        fun edgeSupport(value: String): Support = edge[value] ?: Support.UNVERIFIED

        fun antibandingSupport(value: String): Support =
            antibanding[value] ?: Support.UNVERIFIED

        fun whiteBalanceSupport(value: String): Support =
            whiteBalance[value] ?: Support.UNVERIFIED

        val exposureCompensationSupported: Boolean
            get() = minimumExposureCompensation < 0 || maximumExposureCompensation > 0
    }

    data class Matrix(
        val modes: List<Mode>,
        val diagnostics: List<String>,
        val cameras: List<CameraFeatures> = emptyList(),
        val scannedAtMillis: Long = 0L
    ) {
        fun isSupported(resolution: String, fps: Int): Boolean =
            modes.any { it.resolution == resolution && it.fps == fps }

        fun maximumMode(fps: Int): Mode? {
            val candidates = modes.filter { it.fps == fps }
            return bestCandidate(candidates, fps)
        }

        fun bestMode(resolution: String, fps: Int): Mode? {
            val candidates = modes.filter { it.fps == fps && it.resolution == resolution }
            return bestCandidate(candidates, fps)
        }

        private fun bestCandidate(candidates: List<Mode>, fps: Int): Mode? =
            candidates.maxWithOrNull(
                compareBy<Mode> { resolutionScore(it.resolution) }
                    .thenBy { if (it.highSpeed) 0 else 1 }
                    .thenBy { if (it.encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) 1 else 0 }
            )

        fun featuresFor(cameraId: String?): CameraFeatures? {
            if (cameraId != null) return cameras.firstOrNull { it.cameraId == cameraId }
            return cameras.singleOrNull() ?: cameras.firstOrNull()
        }

        fun forCamera(cameraId: String?): Matrix {
            if (cameraId.isNullOrBlank()) return this
            return copy(
                modes = modes.filter { it.cameraId == cameraId },
                cameras = cameras.filter { it.cameraId == cameraId }
            )
        }

        fun encoderSupport(cameraId: String?, resolution: String, fps: Int, mime: String): Support {
            val cameraModes = modes.filter { cameraId == null || it.cameraId == cameraId }
            val matchingFps = cameraModes.filter { it.fps == fps }
            if (matchingFps.isEmpty()) return Support.UNVERIFIED
            val matchingResolution = matchingFps.filter { it.resolution == resolution }
            if (matchingResolution.isEmpty()) return Support.UNSUPPORTED
            return if (matchingResolution.any { it.encoderMime.equals(mime, ignoreCase = true) }) {
                Support.SUPPORTED
            } else {
                Support.UNSUPPORTED
            }
        }

        companion object {
            private fun resolutionScore(value: String): Int = when (value) {
                CaptureSettings.RESOLUTION_8K -> 5
                CaptureSettings.RESOLUTION_4K -> 4
                CaptureSettings.RESOLUTION_2K -> 3
                CaptureSettings.RESOLUTION_1080P -> 2
                CaptureSettings.RESOLUTION_720P -> 1
                else -> 0
            }
        }
    }

    private data class CollectedModes(
        val values: Set<Int>,
        val metadataComplete: Boolean
    )

    @Volatile
    private var cached: Matrix? = null

    fun cached(context: Context? = null): Matrix? {
        cached?.let { return it }
        if (context == null) return null
        return synchronized(this) {
            cached ?: loadPersisted(context.applicationContext)?.also { cached = it }
        }
    }

    @Synchronized
    fun scan(context: Context, force: Boolean = false): Matrix {
        if (!force) cached(context)?.let { return it }

        val manager = context.getSystemService(CameraManager::class.java)
        val diagnostics = mutableListOf<String>()
        val modes = mutableListOf<Mode>()
        val cameraFeatures = mutableListOf<CameraFeatures>()
        val encoderCache = mutableMapOf<Triple<Size, Int, Boolean>, List<String>>()
        val hdrEncoderSupport = hardwareHlg10EncoderSupport()

        for (cameraId in manager.cameraIdList) {
            val characteristics = try {
                manager.getCameraCharacteristics(cameraId)
            } catch (throwable: Throwable) {
                diagnostics += "Câmera $cameraId: ${throwable.message ?: "falha ao consultar"}"
                continue
            }
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val advertisedCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logicalCamera = advertisedCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && !logicalCamera) continue

            val physicalCharacteristics = runCatching { characteristics.physicalCameraIds.toList() }
                .getOrDefault(emptyList())
                .mapNotNull { physicalId ->
                    runCatching { manager.getCameraCharacteristics(physicalId) }
                        .onFailure {
                            diagnostics += "Lente física $physicalId: metadados incompletos"
                        }
                        .getOrNull()
                }

            cameraFeatures += inspectFeatures(
                manager = manager,
                cameraId = cameraId,
                characteristics = characteristics,
                physicalCharacteristics = physicalCharacteristics,
                hdrEncoderSupport = hdrEncoderSupport
            )

            val streamMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            if (streamMap == null) {
                diagnostics += "Câmera $cameraId: mapa de streams ausente"
                continue
            }

            val regularSizes = linkedSetOf<Size>().apply {
                try {
                    streamMap.getOutputSizes(ImageFormat.PRIVATE)?.let(::addAll)
                } catch (_: Throwable) {
                }
                try {
                    streamMap.getOutputSizes(MediaCodec::class.java)?.let(::addAll)
                } catch (_: Throwable) {
                }
                try {
                    streamMap.getOutputSizes(MediaRecorder::class.java)?.let(::addAll)
                } catch (_: Throwable) {
                }
            }

            for ((resolution, size) in knownSizes()) {
                if (size !in regularSizes) continue
                for (fps in CaptureSettings.supportedFpsValues) {
                    // A sessão é testada com a faixa fixa exata; faixa variável nunca confirma um modo.
                    val encoders = encoderCache.getOrPut(Triple(size, fps, false)) {
                        findHardwareEncoders(size, fps, allowRateMetadataFallback = false)
                    }
                    if (encoders.isEmpty()) continue

                    val minimumFrameDuration = minimumFrameDurationNs(streamMap, size)
                    val publicTimingAccepts = minimumFrameDuration <= 0L ||
                        minimumFrameDuration <= frameDurationNs(fps) + frameToleranceNs(fps)
                    val runtimeSessionSupport = queryRegularSessionSupport(
                        manager = manager,
                        cameraId = cameraId,
                        size = size,
                        fps = fps
                    )
                    if (runtimeSessionSupport == false) {
                        diagnostics += "Câmera $cameraId: ${size.width}×${size.height} $fps FPS rejeitado pela consulta de sessão em runtime"
                        continue
                    }
                    // 120+ FPS: a consulta de sessão aceita a faixa mesmo quando a HAL entrega menos
                    // (4K "120" chegou a 60 FPS reais); aqui vale só o tempo mínimo de quadro.
                    if (!publicTimingAccepts && (fps >= 120 || runtimeSessionSupport != true)) {
                        continue
                    }
                    if (!publicTimingAccepts && runtimeSessionSupport == true) {
                        diagnostics += "Câmera $cameraId: ${size.width}×${size.height} $fps FPS aceito pela sessão em runtime apesar do tempo mínimo conservador"
                    }
                    encoders.forEach { mime ->
                        modes += Mode(cameraId, resolution, size, fps, highSpeed = false, encoderMime = mime)
                    }
                }
            }

            // Modos constrained high-speed (120/240 FPS da Samsung): só entram quando a HAL anuncia
            // o tamanho com faixa de FPS fixa e o encoder de hardware aceita a taxa.
            val highSpeedSizes = runCatching { streamMap.highSpeedVideoSizes?.toSet().orEmpty() }
                .getOrDefault(emptySet())
            diagnostics += "Câmera $cameraId: high-speed anunciado = " + highSpeedSizes
                .sortedByDescending { it.width.toLong() * it.height }
                .take(6)
                .joinToString(" • ") { size ->
                    val rs = runCatching { streamMap.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty() }
                        .getOrDefault(emptyList())
                    "${size.width}×${size.height} " + rs.joinToString("/") { "${it.lower}-${it.upper}" }
                }.ifBlank { "nenhum" }
            for ((resolution, size) in knownSizes()) {
                if (size !in highSpeedSizes) continue
                val ranges = runCatching { streamMap.getHighSpeedVideoFpsRangesFor(size)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                for (fps in CaptureSettings.supportedFpsValues.filter { it >= 120 }) {
                    if (!ranges.any { it.upper == fps }) continue
                    val encoders = encoderCache.getOrPut(Triple(size, fps, true)) {
                        findHardwareEncoders(size, fps, allowRateMetadataFallback = false)
                    }
                    if (encoders.isEmpty()) {
                        diagnostics += "Câmera $cameraId: ${size.width}×${size.height} $fps FPS high-speed sem encoder de hardware"
                        continue
                    }
                    encoders.forEach { mime ->
                        modes += Mode(cameraId, resolution, size, fps, highSpeed = true, encoderMime = mime)
                    }
                }
            }

        }

        val unique = modes
            .distinctBy { listOf(it.cameraId, it.resolution, it.fps, it.highSpeed, it.encoderMime) }
            .sortedWith(
                compareByDescending<Mode> { it.fps }
                    .thenByDescending { resolutionScore(it.resolution) }
            )

        if (unique.isEmpty()) diagnostics += "Nenhuma combinação câmera + encoder foi confirmada"
        val matrix = Matrix(
            modes = unique,
            diagnostics = diagnostics,
            cameras = cameraFeatures.distinctBy { it.cameraId },
            scannedAtMillis = System.currentTimeMillis()
        )
        cached = matrix
        persist(context.applicationContext, matrix)
        return matrix
    }

    @Synchronized
    fun invalidate(context: Context? = null) {
        cached = null
        context?.applicationContext
            ?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(CACHE_KEY)
            ?.apply()
    }

    private fun inspectFeatures(
        manager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics,
        physicalCharacteristics: List<CameraCharacteristics>,
        hdrEncoderSupport: Support
    ): CameraFeatures {
        val requestKeys = runCatching { characteristics.availableCaptureRequestKeys.toSet() }
            .getOrDefault(emptySet())

        val stabilizationModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val stabilization = linkedMapOf(
            CaptureSettings.STABILIZATION_PREVIEW to modeSupport(
                stabilizationModes,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in requestKeys
            ),
            CaptureSettings.STABILIZATION_EIS to modeSupport(
                stabilizationModes,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in requestKeys
            ),
            CaptureSettings.STABILIZATION_OIS to oisSupport(
                manager,
                cameraId,
                characteristics
            ),
            CaptureSettings.STABILIZATION_OFF to Support.SUPPORTED
        )

        val focusModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )
        val focus = linkedMapOf(
            CaptureSettings.FOCUS_CONTINUOUS_VIDEO to modeSupport(
                focusModes,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE in requestKeys
            ),
            CaptureSettings.FOCUS_CONTINUOUS_PICTURE to modeSupport(
                focusModes,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE in requestKeys
            ),
            CaptureSettings.FOCUS_AUTO to modeSupport(
                focusModes,
                CameraMetadata.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE in requestKeys
            ),
            CaptureSettings.FOCUS_OFF to modeSupport(
                focusModes,
                CameraMetadata.CONTROL_AF_MODE_OFF,
                CaptureRequest.CONTROL_AF_MODE in requestKeys
            )
        )

        val noiseModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )
        val noiseReduction = linkedMapOf(
            CaptureSettings.PROCESSING_AUTO to anyModeSupport(
                noiseModes,
                setOf(CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG, CameraMetadata.NOISE_REDUCTION_MODE_FAST),
                CaptureRequest.NOISE_REDUCTION_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_OFF to modeSupport(
                noiseModes,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                CaptureRequest.NOISE_REDUCTION_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_FAST to modeSupport(
                noiseModes,
                CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_HIGH_QUALITY to modeSupport(
                noiseModes,
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                CaptureRequest.NOISE_REDUCTION_MODE in requestKeys
            )
        )

        val edgeModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES
        )
        val edge = linkedMapOf(
            CaptureSettings.PROCESSING_AUTO to anyModeSupport(
                edgeModes,
                setOf(CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG, CameraMetadata.EDGE_MODE_FAST),
                CaptureRequest.EDGE_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_OFF to modeSupport(
                edgeModes,
                CameraMetadata.EDGE_MODE_OFF,
                CaptureRequest.EDGE_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_FAST to modeSupport(
                edgeModes,
                CameraMetadata.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE in requestKeys
            ),
            CaptureSettings.PROCESSING_HIGH_QUALITY to modeSupport(
                edgeModes,
                CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                CaptureRequest.EDGE_MODE in requestKeys
            )
        )

        val antibandingModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
        )
        val antibanding = linkedMapOf(
            CaptureSettings.ANTIBANDING_AUTO to modeSupport(
                antibandingModes,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE in requestKeys
            ),
            CaptureSettings.ANTIBANDING_50HZ to modeSupport(
                antibandingModes,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE in requestKeys
            ),
            CaptureSettings.ANTIBANDING_60HZ to modeSupport(
                antibandingModes,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE in requestKeys
            ),
            CaptureSettings.ANTIBANDING_OFF to modeSupport(
                antibandingModes,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE in requestKeys
            )
        )

        val awbModes = collectIntModes(
            characteristics,
            physicalCharacteristics,
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        )
        val whiteBalance = linkedMapOf(
            CaptureSettings.WHITE_BALANCE_AUTO to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_INCANDESCENT to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_FLUORESCENT to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_WARM_FLUORESCENT to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_DAYLIGHT to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_CLOUDY to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_TWILIGHT to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, CaptureRequest.CONTROL_AWB_MODE in requestKeys),
            CaptureSettings.WHITE_BALANCE_SHADE to modeSupport(awbModes, CameraMetadata.CONTROL_AWB_MODE_SHADE, CaptureRequest.CONTROL_AWB_MODE in requestKeys)
        )

        val exposureRange = characteristics.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
        ) ?: Range(0, 0)
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        ) ?: intArrayOf()
        val awbLock = when (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)) {
            true -> Support.SUPPORTED
            false -> Support.UNSUPPORTED
            null -> if (CaptureRequest.CONTROL_AWB_LOCK in requestKeys) Support.UNVERIFIED else Support.UNSUPPORTED
        }
        val manualPostProcessing = if (
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        ) Support.SUPPORTED else Support.UNSUPPORTED
        return CameraFeatures(
            cameraId = cameraId,
            stabilization = stabilization,
            focus = focus,
            noiseReduction = noiseReduction,
            edge = edge,
            antibanding = antibanding,
            whiteBalance = whiteBalance,
            hdrHlg10 = hdrSupport(characteristics, physicalCharacteristics, hdrEncoderSupport),
            awbLock = awbLock,
            manualPostProcessing = manualPostProcessing,
            minimumExposureCompensation = exposureRange.lower,
            maximumExposureCompensation = exposureRange.upper
        )
    }

    private fun collectIntModes(
        logical: CameraCharacteristics,
        physical: List<CameraCharacteristics>,
        key: CameraCharacteristics.Key<IntArray>
    ): CollectedModes {
        val advertised = buildList<IntArray?> {
            add(runCatching { logical.get(key) }.getOrNull())
            physical.forEach { add(runCatching { it.get(key) }.getOrNull()) }
        }
        val reported = advertised.filterNotNull()
        return CollectedModes(
            values = reported.flatMap { it.asIterable() }.toSet(),
            metadataComplete = reported.isNotEmpty()
        )
    }

    private fun modeSupport(
        modes: CollectedModes,
        requestedMode: Int,
        requestKeyAvailable: Boolean
    ): Support = HardwareSupportPolicy.modeSupport(
        advertisedModes = modes.values,
        metadataComplete = modes.metadataComplete,
        requestedMode = requestedMode,
        requestKeyAvailable = requestKeyAvailable
    )

    private fun anyModeSupport(
        modes: CollectedModes,
        requestedModes: Set<Int>,
        requestKeyAvailable: Boolean
    ): Support = when {
        modes.values.any { it in requestedModes } -> Support.SUPPORTED
        modes.metadataComplete -> Support.UNSUPPORTED
        requestKeyAvailable -> Support.UNVERIFIED
        else -> Support.UNSUPPORTED
    }

    private fun oisSupport(
        manager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics
    ): Support {
        val capability = OpticalStabilizationCapability.inspect(manager, cameraId, characteristics)
        return when (capability.decision.source) {
            OisSupportPolicy.Source.LOGICAL_METADATA -> Support.SUPPORTED
            OisSupportPolicy.Source.PHYSICAL_METADATA ->
                if (capability.logicalRequestAvailable) Support.SUPPORTED else Support.UNSUPPORTED
            OisSupportPolicy.Source.REQUEST_KEY_FALLBACK ->
                if (capability.logicalRequestAvailable) Support.SUPPORTED else Support.UNSUPPORTED
            OisSupportPolicy.Source.NONE -> Support.UNSUPPORTED
        }
    }

    private fun hdrSupport(
        characteristics: CameraCharacteristics,
        physicalCharacteristics: List<CameraCharacteristics>,
        encoderSupport: Support
    ): Support {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Support.UNSUPPORTED

        val logicalSupport = cameraHlg10MetadataSupport(characteristics)
        val physicalSupport = physicalCharacteristics.map(::cameraHlg10MetadataSupport)
        val cameraSupport = when {
            logicalSupport == Support.SUPPORTED -> Support.SUPPORTED
            physicalSupport.any { it == Support.SUPPORTED } -> Support.UNVERIFIED
            logicalSupport == Support.UNVERIFIED || physicalSupport.any { it == Support.UNVERIFIED } ->
                Support.UNVERIFIED
            else -> Support.UNSUPPORTED
        }
        return combineSupport(cameraSupport, encoderSupport)
    }

    /**
     * Samsung e outros fabricantes podem omitir parte dos metadados 10-bit na câmera
     * lógica mesmo quando uma lente física/encoder consegue HLG10. Ausência de um bloco
     * de metadados não é prova de incompatibilidade: nesses casos o modo fica UNVERIFIED
     * e pode ser validado quando usado. Só marcamos UNSUPPORTED quando os metadados
     * presentes realmente excluem HLG10.
     */
    private fun cameraHlg10MetadataSupport(characteristics: CameraCharacteristics): Support {
        val capabilities = runCatching {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        }.getOrNull()
        val tenBitAdvertised = capabilities?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
        ) == true
        val profiles = runCatching {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles
        }.getOrNull()

        return when {
            profiles?.contains(DynamicRangeProfiles.HLG10) == true -> Support.SUPPORTED
            profiles != null && tenBitAdvertised -> Support.UNVERIFIED
            profiles != null -> Support.UNSUPPORTED
            tenBitAdvertised -> Support.UNVERIFIED
            capabilities == null -> Support.UNVERIFIED
            else -> Support.UNSUPPORTED
        }
    }

    private fun combineSupport(first: Support, second: Support): Support = when {
        first == Support.UNSUPPORTED || second == Support.UNSUPPORTED -> Support.UNSUPPORTED
        first == Support.UNVERIFIED || second == Support.UNVERIFIED -> Support.UNVERIFIED
        else -> Support.SUPPORTED
    }

    private fun queryRegularSessionSupport(
        manager: CameraManager,
        cameraId: String,
        size: Size,
        fps: Int
    ): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val range = Range(fps, fps)
        return runCatching {
            if (!manager.isCameraDeviceSetupSupported(cameraId)) return@runCatching null
            val setup = manager.getCameraDeviceSetup(cameraId)
            val output = OutputConfiguration(size, MediaCodec::class.java)
            val session = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                mutableListOf(output)
            )
            val request = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            }.build()
            session.setSessionParameters(request)
            setup.isSessionConfigurationSupported(session)
        }.getOrNull()
    }

    private fun knownSizes(): List<Pair<String, Size>> = listOf(
        CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE,
        CaptureSettings.RESOLUTION_4K to CaptureSettings.UHD_SIZE,
        CaptureSettings.RESOLUTION_2K to CaptureSettings.QHD_SIZE,
        CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE,
        CaptureSettings.RESOLUTION_720P to CaptureSettings.HD_SIZE
    )

    private fun minimumFrameDurationNs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size
    ): Long {
        runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }
            .getOrNull()?.takeIf { it > 0L }?.let { return it }
        runCatching { map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size) }
            .getOrNull()?.takeIf { it > 0L }?.let { return it }
        return runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) }
            .getOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private fun findHardwareEncoders(
        size: Size,
        fps: Int,
        allowRateMetadataFallback: Boolean
    ): List<String> {
        val preferredMimes = listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC
        )
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return preferredMimes.filter { mime ->
            var sizeSupportedByHardware = false
            codecInfos.any { codecInfo ->
                if (!codecInfo.isEncoder || codecInfo.isSoftwareOnly) return@any false
                if (codecInfo.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
                val capabilities = try {
                    codecInfo.getCapabilitiesForType(mime)
                } catch (_: Throwable) {
                    return@any false
                }
                if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                    return@any false
                }
                val videoCapabilities = capabilities.videoCapabilities ?: return@any false
                val sizeSupported = runCatching {
                    videoCapabilities.isSizeSupported(size.width, size.height)
                }.getOrDefault(false)
                if (!sizeSupported) return@any false
                sizeSupportedByHardware = true
                runCatching {
                    videoCapabilities.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                }.getOrDefault(false)
            } || (allowRateMetadataFallback && sizeSupportedByHardware)
        }
    }

    private fun hardwareHlg10EncoderSupport(): Support {
        var surfaceHevcFound = false
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (codecInfo in codecInfos) {
            if (!codecInfo.isEncoder || codecInfo.isSoftwareOnly) continue
            if (codecInfo.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) continue
            val capabilities = runCatching {
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: continue
            if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            surfaceHevcFound = true
            val profiles = capabilities.profileLevels.map { it.profile }
            if (
                profiles.any {
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
            ) {
                return Support.SUPPORTED
            }
        }
        // Alguns encoders proprietários aceitam Main10 mesmo omitindo profileLevels.
        // HEVC de superfície sem confirmação fica testável, nunca vira falso negativo.
        return if (surfaceHevcFound) Support.UNVERIFIED else Support.UNSUPPORTED
    }

    private fun frameDurationNs(fps: Int): Long =
        1_000_000_000L / fps.coerceAtLeast(1)

    private fun frameToleranceNs(fps: Int): Long = when {
        fps >= 120 -> 500_000L
        fps >= 60 -> 1_000_000L
        else -> 2_000_000L
    }

    private fun resolutionScore(value: String): Int = when (value) {
        CaptureSettings.RESOLUTION_8K -> 5
        CaptureSettings.RESOLUTION_4K -> 4
        CaptureSettings.RESOLUTION_2K -> 3
        CaptureSettings.RESOLUTION_1080P -> 2
        CaptureSettings.RESOLUTION_720P -> 1
        else -> 0
    }

    private fun persist(context: Context, matrix: Matrix) {
        runCatching {
            val root = JSONObject()
                .put("schema", CACHE_SCHEMA)
                .put("fingerprint", Build.FINGERPRINT)
                .put("appVersionCode", appVersionCode(context))
                .put("scannedAt", matrix.scannedAtMillis)

            root.put("diagnostics", JSONArray().apply {
                matrix.diagnostics.forEach { value -> put(value) }
            })
            root.put("modes", JSONArray().apply {
                matrix.modes.forEach { mode ->
                    put(
                        JSONObject()
                            .put("cameraId", mode.cameraId)
                            .put("resolution", mode.resolution)
                            .put("width", mode.size.width)
                            .put("height", mode.size.height)
                            .put("fps", mode.fps)
                            .put("highSpeed", mode.highSpeed)
                            .put("encoderMime", mode.encoderMime)
                    )
                }
            })
            root.put("cameras", JSONArray().apply {
                matrix.cameras.forEach { camera ->
                    put(
                        JSONObject()
                            .put("cameraId", camera.cameraId)
                            .put("stabilization", supportMapJson(camera.stabilization))
                            .put("focus", supportMapJson(camera.focus))
                            .put("noiseReduction", supportMapJson(camera.noiseReduction))
                            .put("edge", supportMapJson(camera.edge))
                            .put("antibanding", supportMapJson(camera.antibanding))
                            .put("whiteBalance", supportMapJson(camera.whiteBalance))
                            .put("hdrHlg10", camera.hdrHlg10.name)
                            .put("awbLock", camera.awbLock.name)
                            .put("manualPostProcessing", camera.manualPostProcessing.name)
                            .put("minExposure", camera.minimumExposureCompensation)
                            .put("maxExposure", camera.maximumExposureCompensation)
                    )
                }
            })
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CACHE_KEY, root.toString())
                .apply()
        }
    }

    private fun loadPersisted(context: Context): Matrix? = runCatching {
        val raw = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return null
        val root = JSONObject(raw)
        if (root.optInt("schema", -1) != CACHE_SCHEMA) return null
        if (root.optString("fingerprint") != Build.FINGERPRINT) return null
        if (root.optLong("appVersionCode", -1L) != appVersionCode(context)) return null

        val modes = root.getJSONArray("modes").jsonObjects().mapNotNull { item ->
            val fps = item.getInt("fps")
            val highSpeed = item.optBoolean("highSpeed", false)
            if (fps !in CaptureSettings.supportedFpsValues) return@mapNotNull null
            Mode(
                cameraId = item.getString("cameraId"),
                resolution = item.getString("resolution"),
                size = Size(item.getInt("width"), item.getInt("height")),
                fps = fps,
                highSpeed = highSpeed,
                encoderMime = item.getString("encoderMime")
            )
        }
        val cameras = root.getJSONArray("cameras").jsonObjects().map { item ->
            CameraFeatures(
                cameraId = item.getString("cameraId"),
                stabilization = supportMap(item.getJSONObject("stabilization")),
                focus = supportMap(item.getJSONObject("focus")),
                noiseReduction = supportMap(item.getJSONObject("noiseReduction")),
                edge = supportMap(item.getJSONObject("edge")),
                antibanding = supportMap(item.getJSONObject("antibanding")),
                whiteBalance = supportMap(item.getJSONObject("whiteBalance")),
                hdrHlg10 = support(item.optString("hdrHlg10")),
                awbLock = support(item.optString("awbLock")),
                manualPostProcessing = support(item.optString("manualPostProcessing")),
                minimumExposureCompensation = item.optInt("minExposure", 0),
                maximumExposureCompensation = item.optInt("maxExposure", 0)
            )
        }
        val diagnostics = root.optJSONArray("diagnostics")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty()
        Matrix(
            modes = modes,
            diagnostics = diagnostics,
            cameras = cameras,
            scannedAtMillis = root.optLong("scannedAt", 0L)
        )
    }.getOrNull()

    private fun supportMapJson(values: Map<String, Support>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value.name) }
    }

    private fun supportMap(json: JSONObject): Map<String, Support> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, support(json.optString(key)))
        }
    }

    private fun support(value: String): Support =
        runCatching { Support.valueOf(value) }.getOrDefault(Support.UNVERIFIED)

    private fun JSONArray.jsonObjects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private fun appVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    }.getOrDefault(0L)

    private const val CACHE_PREFS = "steadyvault_hardware_capabilities"
    private const val CACHE_KEY = "matrix_json"
    private const val CACHE_SCHEMA = 12
}
