from pathlib import Path

capture_path = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
build_path = Path("app/build.gradle.kts")

capture = capture_path.read_text(encoding="utf-8")

old_start = '''            val manualSensor = supportsManualSensor(profile)
            if (!profile.hdrHlg10 && manualSensor) {
                val recent = Camera3AStateStore.recentExposure(profile.cameraId)
                val immediatePlan = recent?.let { fixedCadencePlan(profile, it.exposureTimeNs, it.sensitivityIso) }
                if (immediatePlan != null) {
                    val fixedRequest = buildFixedCadenceRequest(profile, immediatePlan)
                        ?: throw IllegalStateException("câmera não disponível para request de cadência fixa")
                    session.setRepeatingRequest(fixedRequest, null, mainHandler)
                    commitRecorderStart(profile, token, highSpeed = false)
                } else {
                    startWithFixedSensorCadence(session, request, profile, token)
                }
            } else {
                session.setRepeatingRequest(request, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }
'''

new_start = '''            val manualSensor = supportsManualSensor(profile)
            if (!profile.hdrHlg10 && manualSensor) {
                // Preview aberto já fornece exposição recente e não adiciona atraso.
                // Captura headless (widget/tela preta) ignora valor antigo e mede a cena atual.
                val recent = if (headlessCaptureRequested) {
                    null
                } else {
                    Camera3AStateStore.recentExposure(profile.cameraId)
                }
                val immediatePlan = recent?.let { fixedCadencePlan(profile, it.exposureTimeNs, it.sensitivityIso) }
                if (immediatePlan != null) {
                    val fixedRequest = buildFixedCadenceRequest(profile, immediatePlan)
                        ?: throw IllegalStateException("câmera não disponível para request de cadência fixa")
                    session.setRepeatingRequest(fixedRequest, null, mainHandler)
                    commitRecorderStart(profile, token, highSpeed = false)
                } else if (headlessCaptureRequested) {
                    startHeadlessFastExposureWarmup(session, request, profile, token)
                } else {
                    startWithFixedSensorCadence(session, request, profile, token)
                }
            } else {
                session.setRepeatingRequest(request, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }
'''

if capture.count(old_start) != 1:
    raise SystemExit(f"bloco startStabilizedRecording esperado 1 vez, encontrado {capture.count(old_start)}")
capture = capture.replace(old_start, new_start, 1)

anchor = '''    private fun startWithFixedSensorCadence(
        session: CameraCaptureSession,
        autoRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
'''

helper = '''    /**
     * Captura headless precisa priorizar o instante do usuário. O AE recebe uma janela
     * curtíssima antes do MediaRecorder começar: até 3 resultados e nunca mais de 50 ms.
     * Se houver exposição/ISO válidos, a cadência é congelada antes do primeiro sample.
     * Se não houver, inicia imediatamente com o request CLEAN automático, sem esperar mais.
     */
    private fun startHeadlessFastExposureWarmup(
        session: CameraCaptureSession,
        autoRequest: CaptureRequest,
        profile: CameraProfile,
        token: Int
    ) {
        val resolved = AtomicBoolean(false)
        val completed = AtomicInteger(0)
        var candidateRequest: CaptureRequest? = null
        val minFramesForLock = if (profile.targetFps <= 30) 1 else 2

        fun finish(requestToUse: CaptureRequest?) {
            if (!isAttemptValid(token) || !resolved.compareAndSet(false, true)) return
            runCatching {
                session.setRepeatingRequest(requestToUse ?: autoRequest, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }.onFailure {
                failSelectedConfigurationFromWorker(
                    token,
                    "não foi possível concluir o aquecimento rápido da exposição: ${errorText(it)}"
                )
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                captureSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!isAttemptValid(token) || resolved.get()) return

                val frameCount = completed.incrementAndGet()
                val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val plan = if (exposureNs != null && sensitivityIso != null) {
                    fixedCadencePlan(profile, exposureNs, sensitivityIso)
                } else null
                val fixedRequest = plan?.let { buildFixedCadenceRequest(profile, it) }
                if (fixedRequest != null) candidateRequest = fixedRequest

                when {
                    candidateRequest != null && frameCount >= minFramesForLock -> finish(candidateRequest)
                    frameCount >= HEADLESS_AE_WARMUP_MAX_FRAMES -> finish(candidateRequest)
                }
            }
        }

        session.setRepeatingRequest(autoRequest, callback, mainHandler)
        mainHandler.postDelayed(
            { finish(candidateRequest) },
            HEADLESS_AE_WARMUP_MAX_MS
        )
    }

'''

if capture.count(anchor) != 1:
    raise SystemExit(f"âncora startWithFixedSensorCadence esperado 1 vez, encontrado {capture.count(anchor)}")
capture = capture.replace(anchor, helper + anchor, 1)

constant_anchor = '''        private const val RAW_FILE_PREFIX = "steadyvault_raw_"
'''
constant_block = '''        private const val HEADLESS_AE_WARMUP_MAX_MS = 50L
        private const val HEADLESS_AE_WARMUP_MAX_FRAMES = 3

        private const val RAW_FILE_PREFIX = "steadyvault_raw_"
'''
if capture.count(constant_anchor) != 1:
    raise SystemExit("âncora de constantes não encontrada")
capture = capture.replace(constant_anchor, constant_block, 1)

required = [
    "startHeadlessFastExposureWarmup(session, request, profile, token)",
    "HEADLESS_AE_WARMUP_MAX_MS = 50L",
    "HEADLESS_AE_WARMUP_MAX_FRAMES = 3",
    "val minFramesForLock = if (profile.targetFps <= 30) 1 else 2",
    "session.setRepeatingRequest(requestToUse ?: autoRequest, null, mainHandler)",
    "commitRecorderStart(profile, token, highSpeed = false)",
    "val recent = if (headlessCaptureRequested)",
]
for marker in required:
    if marker not in capture:
        raise SystemExit(f"marcador obrigatório ausente: {marker}")

for forbidden in [
    "applyHybridAe60ExposurePriority",
    "HYBRID_AE60_EXPOSURE_NS",
    "frameGapCaptureCallback",
    "setSingleRepeatingRequest",
]:
    if forbidden in capture:
        raise SystemExit(f"trecho proibido presente: {forbidden}")

capture_path.write_text(capture, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
if "versionCode = 1000138" not in build:
    raise SystemExit("versionCode 1000138 não encontrado")
build = build.replace("versionCode = 1000138", "versionCode = 1000139", 1)
build_path.write_text(build, encoding="utf-8")

print("FAST_WIDGET_AE_WARMUP_OK")
