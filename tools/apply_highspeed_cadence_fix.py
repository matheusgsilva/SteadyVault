from pathlib import Path

service_path = Path('app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt')
build_path = Path('app/build.gradle.kts')
service = service_path.read_text(encoding='utf-8')

# Imports para validar a capacidade real do encoder na combinação resolução/FPS.
service = service.replace(
    'import android.media.MediaCodec\nimport android.media.MediaFormat\n',
    'import android.media.MediaCodec\nimport android.media.MediaCodecInfo\nimport android.media.MediaCodecList\nimport android.media.MediaFormat\n',
    1
)

old_resolver = '''    private fun resolveCaptureResolutionForFps(
        cameraId: String?,
        targetFps: Int,
        storedResolution: String
    ): String {
        if (cameraId.isNullOrBlank()) return storedResolution
        val cachedMatrix = CaptureCapabilityMatrix.cached(this)?.forCamera(cameraId)
        val catalogResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = storedResolution,
            matrix = cachedMatrix
        )
        if (targetFps < CaptureModeStore.FPS_120 || cachedMatrix != null) {
            return catalogResolution
        }

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return storedResolution
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return storedResolution
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        val candidates = listOf(
            CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE,
            CaptureSettings.RESOLUTION_4K to CaptureSettings.UHD_SIZE,
            CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE,
            CaptureSettings.RESOLUTION_720P to CaptureSettings.HD_SIZE
        )
        return candidates.firstOrNull { (_, size) ->
            size in highSpeedSizes && runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)?.any { range ->
                    StrictCaptureModePolicy.acceptsFpsRange(targetFps, range.lower, range.upper)
                } == true
            }.getOrDefault(false)
        }?.first ?: storedResolution
    }
'''
new_resolver = '''    private fun resolveCaptureResolutionForFps(
        cameraId: String?,
        targetFps: Int,
        storedResolution: String
    ): String {
        if (cameraId.isNullOrBlank()) return storedResolution
        val cachedMatrix = CaptureCapabilityMatrix.cached(this)?.forCamera(cameraId)
        val catalogResolution = CaptureModeCatalog.preferredResolution(
            context = this,
            fps = targetFps,
            requestedResolution = storedResolution,
            matrix = cachedMatrix
        )
        if (targetFps < CaptureModeStore.FPS_120) return catalogResolution

        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return catalogResolution
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return catalogResolution
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        val mime = recordingSettings.codecMimes(false).singleOrNull() ?: return catalogResolution
        val candidates = listOf(
            CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE,
            CaptureSettings.RESOLUTION_4K to CaptureSettings.UHD_SIZE,
            CaptureSettings.RESOLUTION_1080P to CaptureSettings.FHD_SIZE,
            CaptureSettings.RESOLUTION_720P to CaptureSettings.HD_SIZE
        )
        val cameraCandidates = candidates.filter { (_, size) ->
            size in highSpeedSizes && runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)?.any { range ->
                    StrictCaptureModePolicy.acceptsFpsRange(targetFps, range.lower, range.upper)
                } == true
            }.getOrDefault(false)
        }
        if (cameraCandidates.isEmpty()) return catalogResolution

        // Não confie só no metadata da câmera: 240 pode existir no sensor e não na
        // combinação HEVC/resolução do encoder. Prioriza taxa confirmada pelo codec.
        cameraCandidates.firstOrNull { (_, size) ->
            highSpeedEncoderSupportLevel(size, targetFps, mime) >= 2
        }?.let { return it.first }

        val sizeSupported = cameraCandidates.filter { (_, size) ->
            highSpeedEncoderSupportLevel(size, targetFps, mime) >= 1
        }
        if (sizeSupported.isNotEmpty()) {
            // Em 240, metadata de rate ausente é tratada de forma conservadora:
            // usa a menor resolução high-speed comum para maximizar chance de 240 real.
            return if (targetFps >= CaptureModeStore.FPS_240) {
                sizeSupported.last().first
            } else {
                sizeSupported.first().first
            }
        }
        return catalogResolution
    }

    private fun highSpeedEncoderSupportLevel(size: Size, fps: Int, mime: String): Int {
        var sizeSupported = false
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in codecInfos) {
            if (!info.isEncoder || info.isSoftwareOnly) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val videoCaps = caps.videoCapabilities ?: continue
            if (!runCatching { videoCaps.isSizeSupported(size.width, size.height) }.getOrDefault(false)) continue
            sizeSupported = true
            if (runCatching {
                    videoCaps.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                }.getOrDefault(false)) {
                return 2
            }
        }
        // EncoderProfiles OEM com FPS exato também vale como confirmação forte.
        val oemExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                DirectMediaRecorderBackend.findExactSelection(
                    cameraId = preferredCameraId.orEmpty(),
                    width = size.width,
                    height = size.height,
                    fps = fps,
                    mime = mime,
                    hdrHlg10 = false,
                    requestedBitrate = configuredVideoBitrate()
                )
            }.getOrNull()
        } else null
        if (oemExact != null) return 2
        return if (sizeSupported) 1 else 0
    }
'''
if old_resolver not in service:
    raise SystemExit('resolver high-speed antigo não encontrado')
service = service.replace(old_resolver, new_resolver, 1)

old_select = '''        // 120/240 FPS continuam dependentes da lista high-speed porque exigem uma
        // sessão de alta velocidade válida segundo a API pública do Camera2.
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())

        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps || size !in highSpeedSizes) continue
            // 120/240 são contrato de constrained high-speed. Nunca caia para sessão
            // regular, pois ela pode aceitar a Surface e ainda entregar apenas ~60 FPS.
            val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed = true) ?: continue
            val profile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = size,
                targetFps = fps,
                fpsRange = fpsRange,
                highSpeed = true,
                dynamicRangeProfile = dynamicRange
            )
            if (!matchesRequestedMode(profile, targetFps)) continue
            if (!profileSatisfiesExplicitStabilization(profile)) continue
            val encoder = selectDirectRecorderEncoder(profile) ?: continue
            if (profile.hasExactFpsRange()) return profile to encoder
        }
        return null
'''
new_select = '''        val requestedSize = recordingSettings.exactPreferredSize() ?: return null

        // A API permite high-FPS em sessão regular quando a câmera publica a faixa
        // exata em CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. Preferimos esse caminho
        // quando também há cadence pública suficiente, pois ele evita o batching do
        // constrained high-speed observado no S25 (rajadas + buracos em 120 FPS).
        val regularRange = resolveRegularHighFpsRange(characteristics, requestedSize, targetFps)
        if (regularRange != null) {
            val regularProfile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = requestedSize,
                targetFps = targetFps,
                fpsRange = regularRange,
                highSpeed = false,
                dynamicRangeProfile = dynamicRange
            )
            if (matchesRequestedMode(regularProfile, targetFps)) {
                val encoder = selectDirectRecorderEncoder(regularProfile)
                if (encoder != null) return regularProfile to encoder
            }
        }

        // Se a câmera não confirma sessão regular, use constrained high-speed com
        // request mínimo e somente uma Surface de gravação.
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toSet().orEmpty() }
            .getOrDefault(emptySet())

        for ((size, fps) in configurationRequestOrder(targetFps)) {
            if (fps != targetFps || size !in highSpeedSizes) continue
            val fpsRange = resolveFpsRange(characteristics, size, fps, highSpeed = true) ?: continue
            val profile = createCameraProfile(
                cameraId = cameraId,
                characteristics = characteristics,
                videoSize = size,
                targetFps = fps,
                fpsRange = fpsRange,
                highSpeed = true,
                dynamicRangeProfile = dynamicRange
            )
            if (!matchesRequestedMode(profile, targetFps)) continue
            val encoder = selectDirectRecorderEncoder(profile) ?: continue
            if (profile.hasExactFpsRange()) return profile to encoder
        }
        return null
'''
if old_select not in service:
    raise SystemExit('seleção high-speed antiga não encontrada')
service = service.replace(old_select, new_select, 1)

old_resolve = '''        if (!highSpeed) {
            // Em sessão regular o request é forçado para a taxa fixa escolhida, mesmo
            // quando a HAL não a anuncia na lista pública. Se o firmware recusar, a
            // sessão falha claramente; nunca substituímos por uma faixa variável.
            return Range(targetFps, targetFps)
        }
'''
new_resolve = '''        if (!highSpeed) {
            if (targetFps >= CaptureModeStore.FPS_120) {
                return resolveRegularHighFpsRange(characteristics, size, targetFps)
            }
            // 30/60 mantêm exatamente o comportamento CLEAN já validado.
            return Range(targetFps, targetFps)
        }
'''
if old_resolve not in service:
    raise SystemExit('resolveFpsRange regular antigo não encontrado')
service = service.replace(old_resolve, new_resolve, 1)

anchor = '''    /**
     * FPS selecionado é contrato exato. Nenhuma faixa variável é aceita: 30 usa
'''
helper = '''    private fun resolveRegularHighFpsRange(
        characteristics: CameraCharacteristics,
        size: Size,
        targetFps: Int
    ): Range<Int>? {
        if (targetFps < CaptureModeStore.FPS_120) return Range(targetFps, targetFps)
        val exact = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper) }
            ?: return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val minFrameNs = encoderSurfaceMinFrameDurationNs(map, size, preferMediaRecorder = true)
        val targetFrameNs = frameDurationNs(targetFps)
        if (minFrameNs > 0L && minFrameNs > targetFrameNs + HIGH_FPS_FRAME_TOLERANCE_NS) return null
        return exact
    }

'''
if anchor not in service:
    raise SystemExit('âncora para helper regular high fps não encontrada')
service = service.replace(anchor, helper + anchor, 1)

old_config_start = '''    private fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile,
        manualCadence: SensorCadencePolicy.Plan? = null
    ) {
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualCadence == null) {
'''
new_config_start = '''    private fun configureCaptureRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile,
        manualCadence: SensorCadencePolicy.Plan? = null
    ) {
        if (profile.highSpeed) {
            configureConstrainedHighSpeedRequest(builder, profile)
            return
        }
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (manualCadence == null) {
'''
if old_config_start not in service:
    raise SystemExit('início configureCaptureRequest não encontrado')
service = service.replace(old_config_start, new_config_start, 1)

anchor2 = '''    private fun requestedAwbMode(value: String): Int = when (value) {
'''
helper2 = '''    /**
     * Constrained high-speed possui um conjunto de controles intencionalmente
     * reduzido. Não reaproveite o request regular: estabilização e pós-processamento
     * extra podem fazer a HAL Samsung aceitar a sessão mas quebrar a cadência.
     */
    private fun configureConstrainedHighSpeedRequest(
        builder: CaptureRequest.Builder,
        profile: CameraProfile
    ) {
        setSafely(builder, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            setSafely(builder, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        val awbModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
            setSafely(builder, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        setSafely(builder, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)

        // High-speed prioriza cadence. EIS/Preview stabilization/OIS ficam fora do
        // request constrained e podem ser testados depois de 120/240 estabilizarem.
        setSafely(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        OpticalStabilizationCapability.apply(builder, profile.oisCapability, enabled = false)
        CameraZoom.apply(builder, profile.characteristics, recordingSettings.zoomRatio)
    }

'''
if anchor2 not in service:
    raise SystemExit('âncora requestedAwbMode não encontrada')
service = service.replace(anchor2, helper2 + anchor2, 1)

old_stab_name = '''    private fun stabilizationName(profile: CameraProfile): String = when (recordingSettings.stabilization) {
        CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
        CaptureSettings.STABILIZATION_EIS -> "EIS"
        CaptureSettings.STABILIZATION_OIS -> "OIS"
        CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
        else -> "estabilização inválida"
    }
'''
new_stab_name = '''    private fun stabilizationName(profile: CameraProfile): String {
        if (profile.highSpeed) return "sem estabilização (high-speed)"
        return when (recordingSettings.stabilization) {
            CaptureSettings.STABILIZATION_PREVIEW -> "preview stabilization"
            CaptureSettings.STABILIZATION_EIS -> "EIS"
            CaptureSettings.STABILIZATION_OIS -> "OIS"
            CaptureSettings.STABILIZATION_OFF -> "sem estabilização"
            else -> "estabilização inválida"
        }
    }
'''
if old_stab_name not in service:
    raise SystemExit('stabilizationName antigo não encontrado')
service = service.replace(old_stab_name, new_stab_name, 1)

# Nova revisão invalida configuração lembrada; 30/60 continuam inalterados.
service = service.replace(
    'private const val CAPTURE_PIPELINE_REVISION = "strict-config-direct-mediarecorder-1.8.255"',
    'private const val CAPTURE_PIPELINE_REVISION = "highspeed-cadence-regular-first-1.8.257"',
    1
)
const_anchor = '        private const val HEADLESS_AE_WARMUP_MAX_MS = 50L\n'
if const_anchor not in service:
    raise SystemExit('constante HEADLESS_AE_WARMUP_MAX_MS não encontrada')
service = service.replace(
    const_anchor,
    const_anchor + '        private const val HIGH_FPS_FRAME_TOLERANCE_NS = 500_000L\n',
    1
)

service_path.write_text(service, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
if 'versionCode = 1000141' not in build:
    raise SystemExit('versionCode 1000141 não encontrado')
build = build.replace('versionCode = 1000141', 'versionCode = 1000142', 1)
build_path.write_text(build, encoding='utf-8')

checks = [
    'configureConstrainedHighSpeedRequest',
    'resolveRegularHighFpsRange',
    'highSpeedEncoderSupportLevel',
    'highspeed-cadence-regular-first-1.8.257',
    'HIGH_FPS_FRAME_TOLERANCE_NS',
]
final = service_path.read_text(encoding='utf-8')
for marker in checks:
    if marker not in final:
        raise SystemExit(f'marcador ausente: {marker}')
print('HIGH_SPEED_CADENCE_FIX_OK')
