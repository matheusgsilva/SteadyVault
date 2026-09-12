from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(text.replace(old, new, 1))

backend = "app/src/main/java/com/steadyvault/camera/capture/recorder/DirectMediaRecorderBackend.kt"
service = "app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt"

replace_once(
    backend,
    '''    override val videoBitrateBps: Long get() = selectedProfile?.videoProfile?.bitrate?.toLong() ?: videoBitrate.toLong()''',
    '''    override val videoBitrateBps: Long
        get() = if (usesExactOemProfile) selectedProfile!!.videoProfile.bitrate.toLong() else videoBitrate.toLong()''',
    "report actual bitrate for explicit 120fps recorder config"
)

replace_once(
    backend,
    '''    private val usesExactOemProfile: Boolean
        get() = selectedProfile != null && (hdrHlg10 || targetFps >= 120)''',
    '''    private val usesExactOemProfile: Boolean
        get() = selectedProfile != null && (hdrHlg10 || targetFps >= 240)''',
    "keep OEM profile only for HDR or 240fps"
)

replace_once(
    backend,
    '''            append((selectedProfile?.videoProfile?.bitrate ?: videoBitrate) / 1_000_000).append(" Mbps")''',
    '''            append((if (usesExactOemProfile) selectedProfile!!.videoProfile.bitrate else videoBitrate) / 1_000_000).append(" Mbps")''',
    "show actual configured bitrate"
)

replace_once(
    backend,
    '''                val oemProfile = selectedProfile
                val useOemProfile = oemProfile != null && (hdrHlg10 || targetFps >= 120)
                if (useOemProfile) {
                    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Perfil OEM direto exige Android 12 ou superior"
                    }
                    // Em constrained high-speed, deixar o MediaRecorder reconstruir
                    // manualmente codec/fps/bitrate pode quebrar o pacing da HAL Samsung.
                    // Use o VideoProfile OEM inteiro para 120/240 FPS e não sobrescreva
                    // seus parâmetros depois de setVideoProfile().
                    setOutputFormat(oemProfile.outputFormat)
                    setVideoProfile(oemProfile.videoProfile)
                } else {''',
    '''                val oemProfile = selectedProfile
                if (usesExactOemProfile) {
                    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Perfil OEM direto exige Android 12 ou superior"
                    }
                    // 240 FPS preserva o VideoProfile OEM: no S25 Ultra ele mantém
                    // a cadência temporal correta. Em 120 FPS SDR, porém, o perfil
                    // OEM gerou timestamps em blocos (~4/4/4/21 ms) apesar da câmera
                    // estar em [120,120]. Nesse modo configure o MediaRecorder
                    // explicitamente para que o muxer receba 120 FPS reais.
                    setOutputFormat(oemProfile!!.outputFormat)
                    setVideoProfile(oemProfile.videoProfile)
                } else {''',
    "use explicit MediaRecorder parameters at SDR 120fps"
)

replace_once(
    service,
    '''        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()''',
    '''        setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)
        setSafely(builder, CaptureRequest.CONTROL_AE_LOCK, false)

        // Constrained high-speed não herdava o anti-banding do request regular.
        // Em 240 FPS isso deixa LEDs ligados em rede aparecerem como quadros/faixas
        // alternadamente escuras. Preserve a preferência existente (AUTO/50/60/OFF)
        // também na sessão high-speed para a HAL sincronizar a exposição quando puder.
        val antibandingModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()
        val requestedAntibanding = when (recordingSettings.antibanding) {
            CaptureSettings.ANTIBANDING_50HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            CaptureSettings.ANTIBANDING_60HZ -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            CaptureSettings.ANTIBANDING_OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        if (antibandingModes.contains(requestedAntibanding)) {
            setSafely(builder, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAntibanding)
        }
        setSafely(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        val afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()''',
    "apply anti-banding to constrained high-speed request"
)
