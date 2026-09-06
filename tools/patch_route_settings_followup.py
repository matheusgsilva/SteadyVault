from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{label}: esperado 1, encontrado {n}')
    path.write_text(text.replace(old, new, 1))

# Remove referencia residual ao spinner de preview que nao existe mais.
settings = Path('app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt')
replace_once(
    settings,
    '''        listOf(
            stabilization, focus, noiseReduction, edge, antibanding, whiteBalance,
            yellowReduction, colorProfile, previewMode
        )
''',
    '''        listOf(
            stabilization, focus, noiseReduction, edge, antibanding, whiteBalance,
            yellowReduction, colorProfile
        )
''',
    'preview residual'
)

# O PendingIntent do widget pode ter sido criado antes da ultima troca de FPS.
# Sempre consulte o perfil atual no momento do toque.
widget = Path('app/src/main/java/com/steadyvault/camera/widgets/WidgetStartReceiver.kt')
replace_once(
    widget,
    '''        val fps = intent.getIntExtra(
            EXTRA_TARGET_FPS,
            CaptureModeStore.getTargetFps(context)
        )
''',
    '''        // O PendingIntent pode ter sido criado antes da ultima alteracao nos ajustes.
        // O toque sempre usa o FPS atualmente salvo, igual ao botao Gravar do app.
        val fps = CaptureModeStore.getTargetFps(context)
''',
    'widget stale fps'
)

# O widget com tela preta faz o mesmo preflight do widget direto antes de abrir a camera.
discreet = Path('app/src/main/java/com/steadyvault/camera/ui/capture/DiscreetRecordingActivity.kt')
text = discreet.read_text()
if 'import com.steadyvault.camera.core.storage.RecordingStorageGuard\n' not in text:
    text = text.replace(
        'import com.steadyvault.camera.core.state.CaptureStateStore\n',
        'import com.steadyvault.camera.core.state.CaptureStateStore\nimport com.steadyvault.camera.core.storage.RecordingStorageGuard\n',
        1
    )
old = '''            val targetFps = CaptureModeStore.getTargetFps(this)
            val effectiveSettings = RecordingServiceRouter.effectiveSettings(this, targetFps)
            val started = runCatching {
'''
new = '''            val targetFps = CaptureModeStore.getTargetFps(this)
            val effectiveSettings = RecordingServiceRouter.effectiveSettings(this, targetFps)
            val spaceCheck = RecordingStorageGuard.checkProfile(this, effectiveSettings)
            if (!spaceCheck.allowed) {
                showFailure("Espaço insuficiente para iniciar", IllegalStateException(spaceCheck.message))
                finish()
                return
            }
            val started = runCatching {
'''
if text.count(old) != 1:
    raise SystemExit(f'black widget preflight: esperado 1, encontrado {text.count(old)}')
discreet.write_text(text.replace(old, new, 1))

# Verificacoes extras.
settings_text = settings.read_text()
widget_text = widget.read_text()
black_text = discreet.read_text()
assert 'previewMode' not in settings_text
assert 'intent.getIntExtra(\n            EXTRA_TARGET_FPS' not in widget_text
assert 'RecordingStorageGuard.checkProfile(this, effectiveSettings)' in black_text
print('FOLLOWUP AUDIT OK')
