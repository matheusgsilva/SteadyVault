from pathlib import Path

# The preview is regular-session-only now. Keeping the existing generic branch
# unreachable minimizes risk to the already-working 30/60 request path.
p = Path("app/src/main/java/com/steadyvault/camera/ui/capture/IdleCameraPreviewController.kt")
t = p.read_text()
t = t.replace("CaptureModeStore.FPS_120", "Int.MAX_VALUE")
t = t.replace("// especialmente em 120 FPS. O preview HFR usa o caminho mínimo da HAL.", "// O preview usa somente a sessão regular da HAL.")
t = t.replace("// Preview HFR segue o mesmo contrato do arquivo: usa somente a faixa fixa\n        // solicitada e nunca substitui 120 por 240 ou por uma faixa variável.", "// Caminho legado mantido inacessível; os modos ativos são somente 30/60.")
p.write_text(t)

# Remove legacy high-rate wording before the main patch validates CaptureService.
p = Path("app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt")
t = p.read_text()
t = t.replace("120 FPS", "alta taxa")
t = t.replace("240 FPS", "alta taxa")
t = t.replace("120/240", "alta taxa")
p.write_text(t)

print("high-speed preview path disabled")
