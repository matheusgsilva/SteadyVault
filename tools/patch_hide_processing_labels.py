from pathlib import Path

path = Path('app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt')
text = path.read_text()
old = '''        if (initialFps >= CaptureModeStore.FPS_60) {
            noiseReduction.visibility = View.GONE
            edge.visibility = View.GONE
            addInfo("Em 60, 120 e 240 FPS o SteadyVault usa redução de ruído mínima e nitidez desligada para priorizar a cadência. Esses dois controles aparecem em 30 FPS, onde a escolha realmente é aplicada.")
        }
'''
new = '''        if (initialFps >= CaptureModeStore.FPS_60) {
            listOf(noiseReduction, edge).forEach { control ->
                control.visibility = View.GONE
                labelBySpinner[control]?.visibility = View.GONE
                helperBySpinner[control]?.visibility = View.GONE
            }
            addInfo("Em 60, 120 e 240 FPS o SteadyVault usa redução de ruído mínima e nitidez desligada para priorizar a cadência. Esses dois controles aparecem em 30 FPS, onde a escolha realmente é aplicada.")
        }
'''
if text.count(old) != 1:
    raise SystemExit(f'trecho 60fps esperado uma vez; encontrado {text.count(old)}')
path.write_text(text.replace(old, new, 1))
