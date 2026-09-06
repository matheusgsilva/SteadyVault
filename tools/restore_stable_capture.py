from pathlib import Path

capture = Path('app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt')
text = capture.read_text()

# Restore only external processing coordination around the known-good capture core.
text = text.replace(
    'import com.steadyvault.camera.storage.vault.VaultRepository\n',
    'import com.steadyvault.camera.storage.vault.VaultRepository\nimport com.steadyvault.camera.processing.auto.AutoGapRepairService\nimport com.steadyvault.camera.processing.service.VideoOptimizationService\n',
    1,
)

needle = '''        if (!serviceActive.compareAndSet(false, true)) {\n            sendState(currentState)\n            return\n        }\n        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)'''
replacement = '''        if (!serviceActive.compareAndSet(false, true)) {\n            sendState(currentState)\n            return\n        }\n        // Camera/encoder always win over any background transcode. Both calls set\n        // an in-process cancellation flag before the service IPC, so preparation of\n        // the next recording naturally gives GPU/codec work time to unwind.\n        AutoGapRepairService.pauseForCapture(this)\n        VideoOptimizationService.pauseForCapture(this)\n        VaultStartupCoordinator.suspendForCapture(cameraLeaseToken)'''
assert needle in text
text = text.replace(needle, replacement, 1)

needle = '''        if (!hasRequiredPermissions()) {\n            sendState("Falha: permissão de câmera é obrigatória")\n            serviceActive.set(false)\n            stopSelf()\n            return\n        }'''
replacement = '''        if (!hasRequiredPermissions()) {\n            sendState("Falha: permissão de câmera é obrigatória")\n            serviceActive.set(false)\n            AutoGapRepairService.resumeAfterCapture(this)\n            VideoOptimizationService.resumeAfterCapture()\n            stopSelf()\n            return\n        }'''
assert needle in text
text = text.replace(needle, replacement, 1)

needle = '''        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10" else "SDR BT.709"\n        val message = "Vídeo salvo no cofre • ${sizeName(size)} • $fps FPS • $qualityLabel • ${formatDuration(durationSeconds)}"'''
replacement = '''        val qualityLabel = if (profile?.hdrHlg10 == true) "HDR HLG10" else "SDR BT.709"\n        // The original is finalized and indexed before it ever enters repair.\n        AutoGapRepairService.enqueue(this, finalFile, fps)\n        val message = "Vídeo salvo no cofre • ${sizeName(size)} • $fps FPS • $qualityLabel • ${formatDuration(durationSeconds)}"'''
assert needle in text
text = text.replace(needle, replacement, 1)

needle = '''        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)\n        releaseWakeLock()\n        if (deferredRawCleanup) {'''
replacement = '''        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)\n        releaseWakeLock()\n        AutoGapRepairService.resumeAfterCapture(this)\n        VideoOptimizationService.resumeAfterCapture()\n        if (deferredRawCleanup) {'''
assert needle in text
text = text.replace(needle, replacement, 1)

needle = '''    private fun abortBeforeCaptureStart() {\n        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)\n        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)\n        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }'''
replacement = '''    private fun abortBeforeCaptureStart() {\n        CameraResourceCoordinator.releaseCapture(CameraResourceCoordinator.Owner.VIDEO, cameraLeaseToken)\n        VaultStartupCoordinator.resumeAfterCapture(cameraLeaseToken)\n        AutoGapRepairService.resumeAfterCapture(this)\n        VideoOptimizationService.resumeAfterCapture()\n        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }'''
assert needle in text
text = text.replace(needle, replacement, 1)

capture.write_text(text)

service = Path('app/src/main/java/com/steadyvault/camera/processing/service/VideoOptimizationService.kt')
s = service.read_text()

if 'import com.steadyvault.camera.core.state.CaptureStateStore' not in s:
    s = s.replace(
        'import com.steadyvault.camera.core.state.OptimizationStateStore\n',
        'import com.steadyvault.camera.core.state.OptimizationStateStore\nimport com.steadyvault.camera.core.state.CaptureStateStore\n',
        1,
    )

# Treat capture priority as cancellation everywhere the heavy pipeline can wait/run.
s = s.replace('policy.awaitSafeTemperature(config.thermalProtection, cancelled::get)',
              'policy.awaitSafeTemperature(config.thermalProtection, ::cancelledForCapture)', 1)
s = s.replace('cancelled = cancelled::get\n', 'cancelled = ::cancelledForCapture\n', 1)
s = s.replace('if (cancelled.get()) throw InterruptedException("Otimização cancelada")',
              'if (cancelledForCapture()) throw InterruptedException("Otimização pausada para a gravação")')

marker = '''    private fun publishProgress(\n'''
helper = '''    private fun cancelledForCapture(): Boolean =\n        cancelled.get() || capturePriorityRequested || CaptureStateStore.isBusy(this) || Thread.currentThread().isInterrupted\n\n'''
assert marker in s
if helper not in s:
    s = s.replace(marker, helper + marker, 1)

# Make pause synchronous in-process; IPC only wakes/cancels the Service instance.
needle = '''        fun cancel(context: Context) {\n            val intent = Intent(context, VideoOptimizationService::class.java).setAction(ACTION_CANCEL)\n            context.startService(intent)\n        }\n\n        fun start(context: Context, source: File, requestedConfig: OptimizationConfig): Boolean {'''
replacement = '''        fun cancel(context: Context) {\n            val intent = Intent(context, VideoOptimizationService::class.java).setAction(ACTION_CANCEL)\n            context.startService(intent)\n        }\n\n        fun pauseForCapture(context: Context) {\n            capturePriorityRequested = true\n            runCatching {\n                context.startService(Intent(context, VideoOptimizationService::class.java).setAction(ACTION_CANCEL))\n            }\n        }\n\n        fun resumeAfterCapture() {\n            capturePriorityRequested = false\n        }\n\n        fun start(context: Context, source: File, requestedConfig: OptimizationConfig): Boolean {'''
assert needle in s
s = s.replace(needle, replacement, 1)

# Add flag in companion object just before existing constants.
needle = '    companion object {\n'
assert needle in s
if 'capturePriorityRequested' not in s.split('companion object {',1)[1]:
    s = s.replace(needle, needle + '        @Volatile private var capturePriorityRequested = false\n', 1)

# Refuse to start manual optimization while camera is already active.
needle = '''        fun start(context: Context, source: File, requestedConfig: OptimizationConfig): Boolean {\n'''
replacement = '''        fun start(context: Context, source: File, requestedConfig: OptimizationConfig): Boolean {\n            if (capturePriorityRequested || CaptureStateStore.isBusy(context)) return false\n'''
s = s.replace(needle, replacement, 1)
service.write_text(s)

# Bump build only after the actual app changes are prepared.
build = Path('app/build.gradle.kts')
b = build.read_text()
b = b.replace('versionCode = 1000149', 'versionCode = 1000150', 1)
build.write_text(b)

print('stable capture restored with processing coordination')
