from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: esperado 1 trecho, encontrado {count}: {old!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


capture_path = "app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt"
diagnostics_path = "app/src/main/java/com/steadyvault/camera/ui/settings/DiagnosticsActivity.kt"

replace_once(
    capture_path,
    "import com.steadyvault.camera.core.diagnostics.AppLogRepository\n",
    "import com.steadyvault.camera.core.diagnostics.AppLogRepository\nimport com.steadyvault.camera.core.diagnostics.CaptureExposureFpsTrace\n",
)
replace_once(
    capture_path,
    "import android.hardware.camera2.CaptureRequest\n",
    "import android.hardware.camera2.CaptureRequest\nimport android.hardware.camera2.CaptureFailure\n",
)
replace_once(
    capture_path,
    "import android.os.Handler\n",
    "import android.os.Handler\nimport android.os.HandlerThread\n",
)
replace_once(
    capture_path,
    "    private val mainHandler = Handler(Looper.getMainLooper())\n",
    "    private val mainHandler = Handler(Looper.getMainLooper())\n    private var fpsExposureTraceThread: HandlerThread? = null\n    private var fpsExposureTraceHandler: Handler? = null\n    private var fpsExposureTrace: CaptureExposureFpsTrace? = null\n",
)

helper = '''    private fun ensureFpsExposureTraceHandler(): Handler {\n        fpsExposureTraceHandler?.let { return it }\n        val thread = HandlerThread("SteadyVault-FpsExposureTrace", Process.THREAD_PRIORITY_DEFAULT).apply { start() }\n        return Handler(thread.looper).also {\n            fpsExposureTraceThread = thread\n            fpsExposureTraceHandler = it\n        }\n    }\n\n    private fun beginFpsExposureTrace(profile: CameraProfile): CaptureExposureFpsTrace? {\n        if (profile.highSpeed || profile.targetFps != CaptureModeStore.FPS_60) return null\n        val recent = Camera3AStateStore.recentExposure(profile.cameraId)\n        fpsExposureTrace?.finish(finalOutputFile, "substituído por nova sessão")\n        return CaptureExposureFpsTrace.begin(\n            context = this,\n            sessionId = captureSessionId,\n            cameraId = profile.cameraId,\n            width = profile.videoSize.width,\n            height = profile.videoSize.height,\n            targetFps = profile.targetFps,\n            previewExposureNs = recent?.exposureTimeNs ?: 0L,\n            previewIso = recent?.sensitivityIso ?: 0\n        ).also { fpsExposureTrace = it }\n    }\n\n    private fun fpsExposureCallback(trace: CaptureExposureFpsTrace): CameraCaptureSession.CaptureCallback =\n        object : CameraCaptureSession.CaptureCallback() {\n            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {\n                trace.record(request, result)\n            }\n\n            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {\n                trace.recordFailure(failure)\n            }\n\n            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {\n                trace.recordBufferLost()\n            }\n        }\n\n    private fun finishFpsExposureTrace(outputFile: File?, reason: String) {\n        val trace = fpsExposureTrace ?: return\n        fpsExposureTrace = null\n        trace.finish(outputFile, reason)\n    }\n\n    private fun stopFpsExposureTraceHandler() {\n        fpsExposureTraceHandler = null\n        fpsExposureTraceThread?.quitSafely()\n        fpsExposureTraceThread = null\n    }\n\n'''
replace_once(
    capture_path,
    "    /**\n     * Gravação regular com request congelado desde a primeira submissão.\n",
    helper + "    /**\n     * Gravação regular com request congelado desde a primeira submissão.\n",
)

replace_once(
    capture_path,
    "            val manualSensor = supportsManualSensor(profile)\n            if (profile.targetFps == CaptureModeStore.FPS_60 && !profile.hdrHlg10 && manualSensor) {",
    "            val manualSensor = supportsManualSensor(profile)\n            val trace = beginFpsExposureTrace(profile)\n            if (profile.targetFps == CaptureModeStore.FPS_60 && !profile.hdrHlg10 && manualSensor) {",
)
replace_once(
    capture_path,
    "                    session.setRepeatingRequest(fixedRequest, null, mainHandler)\n                    commitRecorderStart(profile, token, highSpeed = false)",
    "                    if (trace != null) session.setRepeatingRequest(fixedRequest, fpsExposureCallback(trace), ensureFpsExposureTraceHandler())\n                    else session.setRepeatingRequest(fixedRequest, null, mainHandler)\n                    commitRecorderStart(profile, token, highSpeed = false)",
)
replace_once(
    capture_path,
    "                    startWithFixedSensorCadence(session, request, profile, token)",
    "                    startWithFixedSensorCadence(session, request, profile, token, trace)",
)
replace_once(
    capture_path,
    "                session.setRepeatingRequest(request, null, mainHandler)\n                commitRecorderStart(profile, token, highSpeed = false)",
    "                if (trace != null) session.setRepeatingRequest(request, fpsExposureCallback(trace), ensureFpsExposureTraceHandler())\n                else session.setRepeatingRequest(request, null, mainHandler)\n                commitRecorderStart(profile, token, highSpeed = false)",
)
replace_once(
    capture_path,
    "        token: Int\n    ) {\n        val locked = AtomicBoolean(false)",
    "        token: Int,\n        trace: CaptureExposureFpsTrace?\n    ) {\n        val locked = AtomicBoolean(false)",
)
replace_once(
    capture_path,
    "            ) {\n                if (!isAttemptValid(token) || locked.get() || completed.incrementAndGet() < 3) return",
    "            ) {\n                trace?.record(request, result)\n                if (!isAttemptValid(token) || locked.get() || completed.incrementAndGet() < 3) return",
)
replace_once(
    capture_path,
    "                runCatching {\n                    captureSession.setRepeatingRequest(fixedRequest ?: autoRequest, null, mainHandler)\n                }\n            }\n        }\n\n        session.setRepeatingRequest(autoRequest, callback, mainHandler)",
    "                runCatching {\n                    if (trace != null) captureSession.setRepeatingRequest(fixedRequest ?: autoRequest, fpsExposureCallback(trace), ensureFpsExposureTraceHandler())\n                    else captureSession.setRepeatingRequest(fixedRequest ?: autoRequest, null, mainHandler)\n                }\n            }\n\n            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {\n                trace?.recordFailure(failure)\n            }\n\n            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {\n                trace?.recordBufferLost()\n            }\n        }\n\n        session.setRepeatingRequest(autoRequest, callback, ensureFpsExposureTraceHandler())",
)
replace_once(
    capture_path,
    "            setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)\n            setSafely(builder, CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs)",
    "            setSafely(builder, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)\n            setSafely(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange)\n            setSafely(builder, CaptureRequest.SENSOR_FRAME_DURATION, manualCadence.frameDurationNs)",
)
replace_once(
    capture_path,
    "        releaseCameraOnly()\n        runCatching { recorder?.release() }\n        sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))",
    "        releaseCameraOnly()\n        runCatching { recorder?.release() }\n        finishFpsExposureTrace(finalFile, \"stop validOutput=$validOutput\")\n        sendBroadcast(Intent(ACTION_RECORDING_VISUAL_FINISHED).setPackage(packageName))",
)
replace_once(
    capture_path,
    "    private fun releaseRecordingResources(deleteOutput: Boolean) {\n        releaseCameraOnly()",
    "    private fun releaseRecordingResources(deleteOutput: Boolean) {\n        finishFpsExposureTrace(finalOutputFile, \"release_resources deleteOutput=$deleteOutput\")\n        releaseCameraOnly()",
)
replace_once(
    capture_path,
    "    private fun finishService() {\n        if (!serviceActive.compareAndSet(true, false)) return\n\n        attemptToken++",
    "    private fun finishService() {\n        if (!serviceActive.compareAndSet(true, false)) return\n\n        finishFpsExposureTrace(finalOutputFile, \"service_finish\")\n        stopFpsExposureTraceHandler()\n        attemptToken++",
)

replace_once(
    diagnostics_path,
    "import com.steadyvault.camera.storage.vault.VaultAreaId\n",
    "import com.steadyvault.camera.storage.vault.VaultAreaId\nimport android.content.Intent\n",
)
replace_once(
    diagnostics_path,
    "import androidx.fragment.app.FragmentActivity\n",
    "import androidx.fragment.app.FragmentActivity\nimport androidx.core.content.FileProvider\n",
)
replace_once(
    diagnostics_path,
    "import com.steadyvault.camera.core.diagnostics.AppLogRepository\n",
    "import com.steadyvault.camera.core.diagnostics.AppLogRepository\nimport com.steadyvault.camera.core.diagnostics.CaptureExposureFpsTrace\n",
)
replace_once(
    diagnostics_path,
    "            val logs = AppLogRepository.snapshot(this)\n            val primary = sizeOf(VaultRepository.primaryDirectory(this))",
    "            val logs = AppLogRepository.snapshot(this)\n            val fpsExposureSummaryFile = CaptureExposureFpsTrace.latestSummary(this)\n            val fpsExposureCsvFile = fpsExposureSummaryFile?.let { File(it.parentFile, it.name.removeSuffix(\".txt\") + \".csv\") }?.takeIf(File::isFile)\n            val fpsExposureText = fpsExposureSummaryFile?.takeIf(File::isFile)?.let { runCatching { it.readText().take(18_000) }.getOrNull() }\n            val primary = sizeOf(VaultRepository.primaryDirectory(this))",
)
replace_once(
    diagnostics_path,
    "                addStorage(primary, secondary, tertiary, trash, cache.totalDiskBytes, recovery, logs.bytes)\n                addPhotoPerformance(photoPerformance)",
    "                addStorage(primary, secondary, tertiary, trash, cache.totalDiskBytes, recovery, logs.bytes)\n                addFpsExposureTrace(fpsExposureText, fpsExposureSummaryFile, fpsExposureCsvFile)\n                addPhotoPerformance(photoPerformance)",
)

diag_methods = '''    private fun addFpsExposureTrace(text: String?, summaryFile: File?, csvFile: File?) {\n        val detail = text ?: "Nenhum diagnóstico FPS + exposição salvo ainda. Grave 4K60 e pare normalmente."\n        val block = card("Rastreio FPS + exposição da última gravação", detail)\n        if (summaryFile?.isFile == true || csvFile?.isFile == true) {\n            block.addView(action("Compartilhar resumo + CSV") { shareFpsExposureTrace(summaryFile, csvFile) })\n        }\n    }\n\n    private fun shareFpsExposureTrace(summaryFile: File?, csvFile: File?) {\n        val files = listOfNotNull(summaryFile?.takeIf(File::isFile), csvFile?.takeIf(File::isFile))\n        if (files.isEmpty()) {\n            Toast.makeText(this, "Nenhum diagnóstico disponível", Toast.LENGTH_SHORT).show()\n            return\n        }\n        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "${packageName}.fileprovider", it) })\n        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {\n            type = "text/*"\n            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)\n            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)\n            putExtra(Intent.EXTRA_SUBJECT, "SteadyVault - FPS + exposição")\n        }\n        startActivity(Intent.createChooser(intent, "Compartilhar diagnóstico"))\n    }\n\n'''
replace_once(
    diagnostics_path,
    "    private fun addPhotoPerformance(report: PhotoPerformanceTracker.Report?) {",
    diag_methods + "    private fun addPhotoPerformance(report: PhotoPerformanceTracker.Report?) {",
)

capture = Path(capture_path).read_text(encoding="utf-8")
diagnostics = Path(diagnostics_path).read_text(encoding="utf-8")
assert "CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange" in capture
assert "CaptureExposureFpsTrace" in capture
assert "fpsExposureCallback" in capture
assert "Rastreio FPS + exposição" in diagnostics
print("FPS_EXPOSURE_DIAG_PATCH_OK")
