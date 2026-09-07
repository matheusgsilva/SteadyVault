from pathlib import Path

path = Path('app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt')
text = path.read_text()

old = '''        runCatching {
            armRecorderForFirstFrame(token)
            val manualSensor = supportsManualSensor(profile)
            if (
                !recordingSettings.autoFpsLowLight &&
                profile.targetFps == CaptureModeStore.FPS_60 &&
                !profile.hdrHlg10 &&
                manualSensor
            ) {
                // Caminho AE60 CLEAN medido como o mais estável no S25 Ultra.
                // 30 FPS nunca entra aqui. Não existe warm-up especial do widget.
                val recent = Camera3AStateStore.recentExposure(profile.cameraId)
                val immediatePlan = recent?.let {
                    fixedCadencePlan(profile, it.exposureTimeNs, it.sensitivityIso)
                }
                if (immediatePlan != null) {
                    val fixedRequest = buildFixedCadenceRequest(profile, immediatePlan)
                        ?: throw IllegalStateException("câmera não disponível para request de cadência fixa")
                    session.setRepeatingRequest(fixedRequest, null, mainHandler)
                    commitRecorderStart(profile, token, highSpeed = false)
                } else {
                    startWithFixedSensorCadence(session, request, profile, token)
                }
            } else {
                // 30 FPS e Auto FPS seguem AE contínuo, como o comportamento do AVFoundation.
                session.setRepeatingRequest(request, null, mainHandler)
                commitRecorderStart(profile, token, highSpeed = false)
            }
        }.onFailure {
'''
new = '''        runCatching {
            armRecorderForFirstFrame(token)
            // Gravação regular usa um único request durante toda a captura.
            // Em 60 FPS com Auto FPS desligado, resolveStandardFpsRange() entrega
            // exatamente [60,60]. O AE permanece ligado para ajustar exposição/ISO,
            // sem trocar para SENSOR_FRAME_DURATION/ISO manual depois que o MP4 começou.
            // Isso evita a transição AE -> sensor manual observada no início dos raws.
            session.setRepeatingRequest(request, null, mainHandler)
            commitRecorderStart(profile, token, highSpeed = false)
        }.onFailure {
'''
if text.count(old) != 1:
    raise SystemExit('startStabilizedRecording baseline not found exactly once')
text = text.replace(old, new)

old_revision = 'private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-clean-1.8.259"'
new_revision = 'private const val CAPTURE_PIPELINE_REVISION = "ios-like-ae-auto60-1.8.260"'
if text.count(old_revision) != 1:
    raise SystemExit('pipeline revision baseline not found exactly once')
text = text.replace(old_revision, new_revision)

path.write_text(text)
