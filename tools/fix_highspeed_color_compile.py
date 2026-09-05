from pathlib import Path

path = Path("app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt")
text = path.read_text(encoding="utf-8")
old = '''        val cameraId = settings.selectedCameraId
            ?: CameraLensCatalog.resolveCameraId(this, null, settings.resolution)
        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
'''
new = '''        val cameraId = (
            settings.selectedCameraId
                ?: CameraLensCatalog.resolveCameraId(this, null, settings.resolution)
        ) ?: return Support.UNVERIFIED
        val characteristics = runCatching {
            getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
'''
if text.count(old) != 1:
    raise SystemExit(f"trecho nullable esperado 1 vez, encontrado {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("HIGHSPEED_COLOR_COMPILE_FIX_OK")
