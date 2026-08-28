#!/usr/bin/env python3
from __future__ import annotations

from collections import Counter, defaultdict
from pathlib import Path
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
MAIN = APP / "src" / "main"
JAVA = MAIN / "java"
RES = MAIN / "res"
MANIFEST = MAIN / "AndroidManifest.xml"

errors: list[str] = []
warnings: list[str] = []


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT))


# 1. Files and XML syntax. Generated/cache/IDE folders are deliberately ignored.
IGNORED_DIRS = {".git", ".gradle", ".gradle-agent", ".idea", ".kotlin", "build", ".cxx", ".externalNativeBuild"}


def is_project_file(path: Path) -> bool:
    return path.is_file() and not any(part in IGNORED_DIRS for part in path.parts)


for path in ROOT.rglob("*"):
    if is_project_file(path) and path.stat().st_size == 0:
        errors.append(f"arquivo vazio: {rel(path)}")

for path in ROOT.rglob("*.xml"):
    if not is_project_file(path):
        continue
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        errors.append(f"XML inválido em {rel(path)}: {exc}")

# 2. Build a local Android resource index.
resource_defs: dict[str, set[str]] = defaultdict(set)
file_resource_types = {"anim", "color", "drawable", "font", "layout", "menu", "mipmap", "raw", "xml"}
for path in RES.rglob("*"):
    if not path.is_file():
        continue
    resource_type = path.parent.name.split("-", 1)[0]
    if resource_type in file_resource_types:
        resource_defs[resource_type].add(path.stem)

for values_dir in RES.glob("values*"):
    if not values_dir.is_dir():
        continue
    qualifier_defs: dict[tuple[str, str], list[Path]] = defaultdict(list)
    for path in values_dir.glob("*.xml"):
        tree = ET.parse(path)
        for node in tree.getroot():
            resource_type = node.attrib.get("type") if node.tag == "item" else node.tag
            name = node.attrib.get("name")
            if resource_type and name:
                resource_defs[resource_type].add(name)
                qualifier_defs[(resource_type, name)].append(path)
    for (resource_type, name), paths in qualifier_defs.items():
        if len(paths) > 1:
            errors.append(
                f"recurso duplicado em {values_dir.name}: {resource_type}/{name} -> "
                + ", ".join(rel(path) for path in paths)
            )

# AAPT2 rejects file-based resources with invalid identifiers before Kotlin compilation.
for path in RES.rglob("*"):
    if not path.is_file():
        continue
    resource_type = path.parent.name.split("-", 1)[0]
    if resource_type not in file_resource_types:
        continue
    resource_name = path.name[:-6] if path.name.endswith(".9.png") else path.stem
    if not re.fullmatch(r"[a-z][a-z0-9_]*", resource_name):
        errors.append(f"nome de recurso inválido para AAPT2: {rel(path)}")

# IDs are generated resources too. Index every @+id declaration so an R.id typo is
# treated exactly like a missing drawable/layout/string instead of being ignored.
for path in RES.rglob("*.xml"):
    text = path.read_text(errors="ignore")
    for match in re.finditer(r"@\+id/([A-Za-z0-9_]+)", text):
        resource_defs["id"].add(match.group(1))

# 3. Validate all local resource references in XML/Kotlin.
local_refs: set[tuple[str, str]] = set()
xml_ref = re.compile(r"(?<!@android:)@\+?([A-Za-z0-9_]+)/([A-Za-z0-9_.]+)")
kotlin_ref = re.compile(r"(?<!android\.)\bR\.([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)")
source_files = [
    path for path in ROOT.rglob("*")
    if is_project_file(path) and path.suffix in {".kt", ".kts", ".xml", ".properties"}
]
for path in source_files:
    text = path.read_text(errors="ignore")
    for match in xml_ref.finditer(text):
        local_refs.add((match.group(1), match.group(2)))
    for match in kotlin_ref.finditer(text):
        local_refs.add((match.group(1), match.group(2)))

for resource_type, name in sorted(local_refs):
    if resource_type not in resource_defs:
        errors.append(f"tipo de recurso desconhecido @{resource_type}/{name}")
    elif name not in resource_defs[resource_type]:
        errors.append(f"recurso inexistente @{resource_type}/{name}")

# 4. Manifest components must point to source classes.
manifest_tree = ET.parse(MANIFEST)
manifest_root = manifest_tree.getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
package_name = "com.steadyvault.camera"
classes: dict[str, Path] = {}
fqcn_sources: dict[str, list[Path]] = defaultdict(list)
for path in JAVA.rglob("*.kt"):
    text = path.read_text(errors="ignore")
    package_match = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
    if not package_match:
        errors.append(f"arquivo Kotlin de produção sem package: {rel(path)}")
        continue
    package = package_match.group(1)
    expected_package = ".".join(path.parent.relative_to(JAVA).parts)
    if package != expected_package:
        errors.append(f"package divergente do caminho em {rel(path)}: {package} != {expected_package}")
    for match in re.finditer(
        r"^(?:(?:public|internal|private)\s+)?(?:data\s+|sealed\s+|enum\s+|annotation\s+|value\s+)?(?:class|object|interface)\s+(\w+)",
        text,
        re.MULTILINE,
    ):
        fqcn = f"{package}.{match.group(1)}"
        fqcn_sources[fqcn].append(path)
        classes[fqcn] = path
for fqcn, paths in fqcn_sources.items():
    unique_paths = sorted({rel(path) for path in paths})
    if len(unique_paths) > 1:
        errors.append(f"tipo Kotlin duplicado {fqcn}: {', '.join(unique_paths)}")

for tag in ("application", "activity", "service", "receiver", "provider"):
    for node in manifest_root.iter(tag):
        name = node.attrib.get(android_ns + "name")
        if not name:
            continue
        fqcn = package_name + name if name.startswith(".") else name
        if fqcn not in classes:
            errors.append(f"componente do manifest sem classe: {fqcn}")

manifest_permissions = {node.attrib.get(android_ns + "name") for node in manifest_root.iter("uses-permission")}
fgs_permissions = {
    "camera": "android.permission.FOREGROUND_SERVICE_CAMERA",
    "microphone": "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "dataSync": "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "mediaProcessing": "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "mediaProjection": "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
}
for service in manifest_root.iter("service"):
    service_name = service.attrib.get(android_ns + "name", "<sem nome>")
    types = service.attrib.get(android_ns + "foregroundServiceType", "")
    if types and "android.permission.FOREGROUND_SERVICE" not in manifest_permissions:
        errors.append(f"serviço foreground sem permissão obrigatória: {service_name}")
    for service_type in filter(None, types.split("|")):
        required_permission = fgs_permissions.get(service_type)
        if required_permission and required_permission not in manifest_permissions:
            errors.append(f"serviço {service_name} usa {service_type} sem {required_permission}")

# Activity aliases are manifest components without backing classes. Validate their
# targets and every string-based alias reference used by QuickCaptureLauncher.
manifest_aliases: set[str] = set()
for node in manifest_root.iter("activity-alias"):
    name = node.attrib.get(android_ns + "name")
    target = node.attrib.get(android_ns + "targetActivity")
    if name:
        manifest_aliases.add(package_name + name if name.startswith(".") else name)
    if not target:
        errors.append("activity-alias sem targetActivity no Manifest")
        continue
    target_fqcn = package_name + target if target.startswith(".") else target
    if target_fqcn not in classes:
        errors.append(f"activity-alias aponta para classe inexistente: {target_fqcn}")
quick_launcher_path = JAVA / "com/steadyvault/camera/ui/capture/QuickCaptureLauncher.kt"
if quick_launcher_path.exists():
    quick_launcher_text = quick_launcher_path.read_text(errors="ignore")
    for alias in re.findall(r'aliasClassName\s*=\s*"([^"]+)"', quick_launcher_text):
        if alias not in manifest_aliases:
            errors.append(f"alias dinâmico não existe no Manifest: {alias}")

# 4b. Custom XML view classes must exist in source.
for path in RES.rglob("*.xml"):
    root = ET.parse(path).getroot()
    for node in root.iter():
        tag = node.tag.rsplit("}", 1)[-1]
        if "." not in tag or tag.startswith("android.") or tag.startswith("androidx."):
            continue
        if tag.startswith(package_name + ".") and tag not in classes:
            errors.append(f"view customizada sem classe em {rel(path)}: {tag}")

# 5. Os widgets reais mantêm todos os controles; o 6x1 usa uma prévia proporcional menor para não estourar no seletor da One UI.
required_action_ids = {"widgetSequence", "widgetPhoto", "widgetStart", "widgetStop"}
widget_runtime_layouts = [
    "widget_control_compact_large",
    "widget_control_expanded_large",
    "widget_control_lock_screen",
]


def layout_ids(name: str) -> set[str]:
    path = RES / "layout" / f"{name}.xml"
    if not path.exists():
        errors.append(f"layout de widget ausente: {name}")
        return set()
    text = path.read_text(errors="ignore")
    return set(re.findall(r"@\+id/([A-Za-z0-9_]+)", text))


for runtime_name in widget_runtime_layouts:
    runtime_ids = layout_ids(runtime_name)
    required = required_action_ids - ({"widgetSequence"} if runtime_name == "widget_control_lock_screen" else set())
    missing_runtime = required - runtime_ids
    if missing_runtime:
        errors.append(f"{runtime_name} sem IDs: {sorted(missing_runtime)}")


expanded_preview_ids = layout_ids("widget_control_expanded_preview")
missing_preview = required_action_ids - expanded_preview_ids
if missing_preview:
    errors.append(f"widget_control_expanded_preview sem IDs: {sorted(missing_preview)}")

# 6. Widget layouts may only use classes supported by RemoteViews.
remote_views_tags = {
    "AdapterViewFlipper", "AnalogClock", "Button", "Chronometer", "FrameLayout",
    "GridLayout", "GridView", "ImageButton", "ImageView", "LinearLayout", "ListView",
    "ProgressBar", "RelativeLayout", "StackView", "TextClock", "TextView", "ViewFlipper"
}
for layout_name in (
    "widget_control_compact_large",
    "widget_control_expanded_large",
    "widget_control_expanded_preview",
    "widget_control_lock_screen",
):
    layout_root = ET.parse(RES / "layout" / f"{layout_name}.xml").getroot()
    for node in layout_root.iter():
        tag = node.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
        if tag not in remote_views_tags:
            errors.append(f"{layout_name} usa classe incompatível com RemoteViews: {tag}")

# Runtime widget actions use neutral ImageViews. ImageButton adds a host-specific
# pressed-state flash on One UI, including before a partial RemoteViews update.
runtime_widget_actions = {
    "widgetZoom", "widgetSequence", "widgetPhoto", "widgetStart", "widgetStop"
}
for layout_name in (
    "widget_control_compact_large",
    "widget_control_expanded_large",
    "widget_control_lock_screen",
):
    layout_root = ET.parse(RES / "layout" / f"{layout_name}.xml").getroot()
    for node in layout_root.iter():
        view_id = node.attrib.get(android_ns + "id", "").rsplit("/", 1)[-1]
        if view_id not in runtime_widget_actions:
            continue
        tag = node.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
        if tag == "ImageButton":
            errors.append(f"{layout_name}: {view_id} ainda pode piscar como ImageButton")
        if node.attrib.get(android_ns + "alpha") != "1":
            errors.append(f"{layout_name}: {view_id} sem alpha inicial integral")

widget_renderer_text = (
    JAVA / "com/steadyvault/camera/widgets/WidgetRenderer.kt"
).read_text(errors="ignore")
for required_token in (
    'views.setFloat(R.id.widgetZoom, "setAlpha", 1f)',
    'views.setFloat(viewId, "setAlpha", 1f)',
):
    if required_token not in widget_renderer_text:
        errors.append(f"estado visual sem piscada incompleto: {required_token}")

protected_apps_layout_text = (
    RES / "layout/activity_protected_apps.xml"
).read_text(errors="ignore")
for forbidden_id in (
    "protectedAppsHomeSettings",
    "protectedAppsBiometric",
    "protectedAppsCaptureControl",
):
    if forbidden_id in protected_apps_layout_text:
        errors.append(f"configuração ainda duplicada na tela Apps: {forbidden_id}")
settings_activity_text = (
    JAVA / "com/steadyvault/camera/ui/settings/SettingsActivity.kt"
).read_text(errors="ignore")
for required_token in (
    "Biometria para a aba Apps",
    "Gerenciar PIN dos apps",
    "Os ícones extras de foto e vídeo são controlados diretamente",
    "Ativar controles protegidos de print e gravação de tela",
    "chooseProtectedCaptureDestination()",
):
    if required_token not in settings_activity_text:
        errors.append(f"configuração dos apps ausente em Ajustes: {required_token}")
if "ACTION_HOME_SETTINGS" in settings_activity_text:
    errors.append("Ajustes ainda abre a tela genérica do launcher para ocultar ícones")

# 6. Widget provider metadata must reference valid layouts and preserve requested cell sizes.
provider_expectations = {
    "widget_control_compact_info.xml": ("widget_control_compact_large", "widget_control_compact_large", "4", "1"),
    "widget_control_expanded_info.xml": ("widget_control_expanded_large", "widget_control_expanded_preview", "6", "1"),
    "widget_control_lock_screen_info.xml": ("widget_control_lock_screen", "widget_control_lock_screen", "4", "1"),
}
for filename, (initial, preview, cells_w, cells_h) in provider_expectations.items():
    path = RES / "xml" / filename
    if not path.exists():
        errors.append(f"provider de widget ausente: {filename}")
        continue
    root = ET.parse(path).getroot()
    attrs = root.attrib
    if attrs.get(android_ns + "initialLayout") != f"@layout/{initial}":
        errors.append(f"{filename}: initialLayout incorreto")
    if attrs.get(android_ns + "previewLayout") != f"@layout/{preview}":
        errors.append(f"{filename}: previewLayout incorreto")
    if attrs.get(android_ns + "targetCellWidth") != cells_w:
        errors.append(f"{filename}: targetCellWidth incorreto")
    if attrs.get(android_ns + "targetCellHeight") != cells_h:
        errors.append(f"{filename}: targetCellHeight incorreto")

# 7. Confirm widget fixed-width contents fit inside their declared minimum width.
def dp_value(value: str | None) -> int:
    if not value or not value.endswith("dp"):
        return 0
    try:
        return int(float(value[:-2]))
    except ValueError:
        return 0


def fixed_widget_width(layout_name: str) -> int:
    root = ET.parse(RES / "layout" / f"{layout_name}.xml").getroot()
    width = dp_value(root.attrib.get(android_ns + "paddingStart")) + dp_value(root.attrib.get(android_ns + "paddingEnd"))
    for child in list(root):
        child_width = child.attrib.get(android_ns + "layout_width")
        if child_width == "0dp" and child.attrib.get(android_ns + "layout_weight"):
            for button in list(child):
                width += dp_value(button.attrib.get(android_ns + "layout_width"))
                width += dp_value(button.attrib.get(android_ns + "layout_marginStart"))
                width += dp_value(button.attrib.get(android_ns + "layout_marginEnd"))
        else:
            width += dp_value(child_width)
            width += dp_value(child.attrib.get(android_ns + "layout_marginStart"))
            width += dp_value(child.attrib.get(android_ns + "layout_marginEnd"))
    return width


for provider_file, layout_name in (
    ("widget_control_compact_info.xml", "widget_control_compact_large"),
    ("widget_control_expanded_info.xml", "widget_control_expanded_large"),
):
    provider = ET.parse(RES / "xml" / provider_file).getroot()
    minimum = dp_value(provider.attrib.get(android_ns + "minWidth"))
    required = fixed_widget_width(layout_name)
    if required > minimum:
        errors.append(f"{layout_name} exige {required}dp, mas o provider declara apenas {minimum}dp")

expanded_preview_root = ET.parse(RES / "layout/widget_control_expanded_preview.xml").getroot()
expanded_preview_width = dp_value(expanded_preview_root.attrib.get(android_ns + "layout_width"))
expanded_preview_height = dp_value(expanded_preview_root.attrib.get(android_ns + "layout_height"))
if expanded_preview_width <= 0 or expanded_preview_width > 240:
    errors.append(f"preview 6x1 deve ser representativo e compacto na One UI; largura atual={expanded_preview_width}dp")
if expanded_preview_height <= 0 or expanded_preview_height > 52:
    errors.append(f"preview 6x1 deve caber no cartão da One UI; altura atual={expanded_preview_height}dp")

# 7. Product robustness invariants added for the full SteadyVault audit.
all_kotlin_text = "\n".join(path.read_text(errors="ignore") for path in JAVA.rglob("*.kt"))

# Every interactive XML control needs a runtime reference, except RemoteViews action IDs
# which are wired through WidgetRenderer rather than an Activity findViewById call.
widget_action_ids = {"widgetZoom", "widgetSequence", "widgetPhoto", "widgetStart", "widgetStop"}
for layout_path in (RES / "layout").glob("*.xml"):
    layout_root = ET.parse(layout_path).getroot()
    for node in layout_root.iter():
        tag = node.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
        clickable = node.attrib.get(android_ns + "clickable") == "true"
        if tag not in {"Button", "ImageButton"} and not clickable:
            continue
        raw_id = node.attrib.get(android_ns + "id", "")
        if not raw_id:
            continue
        view_id = raw_id.rsplit("/", 1)[-1]
        if view_id in widget_action_ids:
            continue
        if not re.search(rf"\bR\.id\.{re.escape(view_id)}\b", all_kotlin_text):
            errors.append(f"controle interativo sem referência de runtime: {layout_path.name}:{view_id}")

settings_activity_text = (JAVA / "com/steadyvault/camera/ui/settings/SettingsActivity.kt").read_text(errors="ignore")
resolution_pos = settings_activity_text.find('"Resolução do arquivo de vídeo"')
fps_pos = settings_activity_text.find('"Taxa de quadros da gravação (FPS)"')
if resolution_pos < 0 or fps_pos < 0 or resolution_pos > fps_pos:
    errors.append("Resolução precisa continuar como a primeira configuração de vídeo")

for required_file in (
    JAVA / "com/steadyvault/camera/ui/vault/RecoveryActivity.kt",
    JAVA / "com/steadyvault/camera/ui/settings/DiagnosticsActivity.kt",
    JAVA / "com/steadyvault/camera/widgets/WidgetPreviewPublisher.kt",
    JAVA / "com/steadyvault/camera/core/diagnostics/AppLogRepository.kt",
    JAVA / "com/steadyvault/camera/core/settings/RecordingDisplayPreferences.kt",
):
    if not required_file.exists():
        errors.append(f"recurso robusto ausente: {rel(required_file)}")

manifest_text = MANIFEST.read_text(errors="ignore")
if 'android:appCategory="game"' in manifest_text:
    errors.append("aplicativo de câmera não deve entrar na política de Game Mode")
if 'android.game_mode_config' in manifest_text:
    errors.append("meta-data Game Mode ainda presente")
game_mode_config = RES / "xml" / "game_mode_config.xml"
if game_mode_config.exists():
    errors.append("game_mode_config.xml ainda presente")
for required_manifest_token in (
    '.ui.settings.DiagnosticsActivity',
    '.ui.vault.RecoveryActivity',
    'android.intent.action.VIEW',
    'android.intent.category.BROWSABLE',
):
    if required_manifest_token not in manifest_text:
        errors.append(f"integração de manifest ausente: {required_manifest_token}")

recovery_text = (JAVA / "com/steadyvault/camera/storage/vault/RecordingRecoveryRepository.kt").read_text(errors="ignore")
for required_token in ("preserveInterrupted", "recoverOne", "deleteCandidate", "moveRecovered", "INCOMPLETE", "RECOVERED"):
    if required_token not in recovery_text:
        errors.append(f"fluxo de recuperação incompleto: {required_token}")
if re.search(r"raw.*delete.*age|age.*raw.*delete", recovery_text, re.IGNORECASE):
    errors.append("arquivos brutos de recuperação não podem ser apagados automaticamente por idade")

bulk_import_text = (JAVA / "com/steadyvault/camera/ui/vault/VaultBulkImportRunner.kt").read_text(errors="ignore")
for required_token in ("cancelRequested", "isCancellationRequested", "fun cancel("):
    if required_token not in bulk_import_text:
        errors.append(f"cancelamento de importação incompleto: {required_token}")

widget_preview_text = (JAVA / "com/steadyvault/camera/widgets/WidgetPreviewPublisher.kt").read_text(errors="ignore")
if "setWidgetPreview" not in widget_preview_text:
    errors.append("preview dinâmico de widget Android 15+ ausente")
widget_renderer_text = (JAVA / "com/steadyvault/camera/widgets/WidgetRenderer.kt").read_text(errors="ignore")
if "supportsUsefulZoom" not in widget_renderer_text or "View.GONE" not in widget_renderer_text:
    errors.append("zoom do widget precisa ser ocultado quando o hardware não suporta")

capture_service_text = (JAVA / "com/steadyvault/camera/capture/service/CaptureService.kt").read_text(errors="ignore")
for required_token in ("Notification.VISIBILITY_PUBLIC", '"Parar e salvar"', "FOREGROUND_SERVICE_IMMEDIATE"):
    if required_token not in capture_service_text:
        errors.append(f"controle de gravação na notificação/lock screen incompleto: {required_token}")

# Branding from the old downloader must never leak into this independent app.
for path in list(JAVA.rglob("*.kt")) + list(RES.rglob("*.xml")):
    text = path.read_text(errors="ignore")
    if re.search(r"media\s*grab|mediagrab", text, re.IGNORECASE):
        errors.append(f"branding externo encontrado em {rel(path)}")

# No known placeholder/dead-code markers are accepted in production sources.
for path in list(JAVA.rglob("*.kt")) + list(RES.rglob("*.xml")):
    text = path.read_text(errors="ignore")
    if re.search(r"\bTODO\b|\bFIXME\b|NotImplementedError|UnsupportedOperationException", text):
        errors.append(f"placeholder/dead code encontrado em {rel(path)}")

# 7. Kotlin unused-import and definitely-dead private declaration checks across main and tests/tools.
for path in ROOT.rglob("*.kt"):
    if not is_project_file(path):
        continue
    text = path.read_text(errors="ignore")
    if "@Suppress" in text or "SuppressWarnings" in text:
        errors.append(f"supressão de warning não permitida: {rel(path)}")
    if re.search(r"private\s+fun\s+\w+\s*\([^)]*\)\s*=\s*Unit\b", text):
        errors.append(f"método privado vazio: {rel(path)}")
    body = "\n".join(line for line in text.splitlines() if not line.strip().startswith("import "))
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("import "):
            continue
        import_body = stripped[len("import "):]
        import_target = import_body.split(" as ", 1)[0].strip()
        alias = import_body.split(" as ", 1)[1].strip() if " as " in import_body else None
        symbol = alias or import_target.rsplit(".", 1)[-1]
        if symbol != "*" and not re.search(rf"\b{re.escape(symbol)}\b", body):
            errors.append(f"import não usado em {rel(path)}: {stripped}")

    for match in re.finditer(
        r"(?m)^\s*private\s+(?:const\s+)?(?:lateinit\s+)?(?:var|val|fun)\s+(\w+)",
        text,
    ):
        symbol = match.group(1)
        if len(re.findall(rf"\b{re.escape(symbol)}\b", text)) == 1:
            errors.append(f"declaração privada sem uso em {rel(path)}: {symbol}")

# 7b. Production non-private helpers must have at least one textual consumer.
# Overrides are framework entry points and are excluded by definition. This catches
# public/internal helpers accidentally left behind after refactors without relying on
# IDE-only inspections.
production_kotlin = list(JAVA.rglob("*.kt"))
production_sources = [(path, path.read_text(errors="ignore")) for path in production_kotlin]
production_tokens = Counter(token for _, text in production_sources for token in re.findall(r"\b[A-Za-z_]\w*\b", text))
for path, text in production_sources:
    for match in re.finditer(
        r"(?m)^\s*(?!(?:private|protected|override)\b)(?:(?:public|internal)\s+)?fun\s+(\w+)\s*\(",
        text,
    ):
        symbol = match.group(1)
        if symbol != "main" and production_tokens[symbol] == 1:
            errors.append(f"método de produção sem consumidor: {rel(path)} ({symbol})")

# 7c. Build-toolchain invariants. API 36 must use an AGP line that officially supports it.
root_build_text = (ROOT / "build.gradle.kts").read_text(errors="ignore")
app_build_text = (APP / "build.gradle.kts").read_text(errors="ignore")
wrapper_properties = ROOT / "gradle/wrapper/gradle-wrapper.properties"
wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
agp_match = re.search(r'id\("com\.android\.application"\)\s+version\s+"([0-9.]+)"', root_build_text)
kotlin_match = re.search(r'id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([0-9.]+)"', root_build_text)
compile_sdk_match = re.search(r"\bcompileSdk\s*=\s*(\d+)", app_build_text)
if not agp_match:
    errors.append("versão do Android Gradle Plugin não encontrada")
if agp_match:
    agp_major = int(agp_match.group(1).split(".")[0])
    if agp_major >= 9 and kotlin_match:
        errors.append("AGP 9+ deve usar Kotlin embutido; remova org.jetbrains.kotlin.android")
    if agp_major < 9 and not kotlin_match:
        errors.append("versão do Kotlin Gradle Plugin não encontrada para AGP anterior ao 9")
if not compile_sdk_match:
    errors.append("compileSdk não encontrado")
if agp_match and compile_sdk_match:
    agp_parts = tuple(int(part) for part in agp_match.group(1).split(".")[:2])
    compile_sdk = int(compile_sdk_match.group(1))
    if compile_sdk >= 36 and agp_parts < (8, 10):
        errors.append(f"AGP {agp_match.group(1)} não deve ser usado com compileSdk {compile_sdk}; use AGP 8.10+")
if "androidx.fragment:fragment-ktx:1.8.9" not in app_build_text:
    errors.append("FragmentActivity é usado diretamente; mantenha androidx.fragment:fragment-ktx:1.8.9 como dependência explícita")
stable_dependencies = {
    "androidx.core:core-ktx:1.19.0": "AndroidX Core 1.19.0",
    "androidx.activity:activity-ktx:1.13.0": "AndroidX Activity 1.13.0",
    "androidx.webkit:webkit:1.16.0": "AndroidX WebKit 1.16.0",
    "androidx.media3:media3-exoplayer:1.10.1": "Media3 ExoPlayer 1.10.1 estável",
    "androidx.media3:media3-ui:1.10.1": "Media3 UI 1.10.1 estável",
}
for dependency, label in stable_dependencies.items():
    if dependency not in app_build_text:
        errors.append(f"dependência estável esperada ausente: {label}")
if "androidx.media3:media3-exoplayer:1.11.0" in app_build_text or "androidx.media3:media3-ui:1.11.0" in app_build_text:
    errors.append("Media3 1.11.0 ainda não é estável; use 1.10.1 nesta versão robusta")
EXPECTED_GRADLE_951_BIN_SHA256 = "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
KNOWN_GRADLE_WRAPPER_SHA256 = {
    # Wrapper launcher published by Gradle 8.10 through 8.12.1.
    "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046",
    # Newer official launcher is also valid: Gradle documents that changing the
    # distribution properties without refreshing the wrapper files is supported.
    "b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13",
}
if not wrapper_properties.exists():
    errors.append("gradle-wrapper.properties ausente")
else:
    wrapper_text = wrapper_properties.read_text(errors="ignore")
    if "gradle-9.5.1-bin.zip" not in wrapper_text:
        errors.append("Gradle Wrapper deve usar 9.5.1 com AGP 9.3.x")
    if f"distributionSha256Sum={EXPECTED_GRADLE_951_BIN_SHA256}" not in wrapper_text:
        errors.append("checksum da distribuição Gradle 9.5.1 ausente ou incorreto")
if not wrapper_jar.exists() or wrapper_jar.stat().st_size == 0:
    errors.append("gradle-wrapper.jar ausente ou vazio")
else:
    wrapper_digest = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
    if wrapper_digest not in KNOWN_GRADLE_WRAPPER_SHA256:
        errors.append(f"gradle-wrapper.jar não reconhecido pelo auditor: {wrapper_digest}")
for wrapper_script in (ROOT / "gradlew", ROOT / "gradlew.bat"):
    if not wrapper_script.is_file() or wrapper_script.stat().st_size < 1_000:
        errors.append(f"arquivo do Gradle Wrapper ausente/inválido: {wrapper_script.name}")
settings_text = (ROOT / "settings.gradle.kts").read_text(errors="ignore")
for repository_token in ("google()", "mavenCentral()", "gradlePluginPortal()"):
    if repository_token not in settings_text:
        errors.append(f"repositório Gradle obrigatório ausente: {repository_token}")
if 'android:extractNativeLibs="true"' not in manifest_text:
    errors.append("Manifest precisa manter android:extractNativeLibs=true para os executáveis nativos do downloader")
downloader_text = (JAVA / "com/steadyvault/camera/ui/browser/SocialMediaDownloader.kt").read_text(errors="ignore")
if 'addOption("--downloader", "libaria2c.so")' not in downloader_text:
    errors.append("downloader aria2c precisa usar o executável Android libaria2c.so fornecido pela dependência")
if 'addOption("--downloader", "aria2c")' in downloader_text:
    errors.append("nome desktop aria2c ainda está configurado como executável no Android")

# 8. Duplicate files. Identical launcher normal/round XML is intentional.
by_hash: dict[str, list[Path]] = defaultdict(list)
for path in ROOT.rglob("*"):
    if is_project_file(path):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        by_hash[digest].append(path)
for group in by_hash.values():
    if len(group) < 2:
        continue
    relative = [rel(path) for path in group]
    if all("mipmap-anydpi" in item and item.endswith(("ic_launcher.xml", "ic_launcher_round.xml")) for item in relative):
        continue
    warnings.append("arquivos idênticos: " + ", ".join(relative))

# 9. Stale migration reports should not ship in the source package.
for path in ROOT.glob("TEST_REPORT_V*.txt"):
    errors.append(f"relatório antigo ainda empacotado: {path.name}")


# 10. Reject deprecated APIs explicitly left in source and horizontal action strips.
deprecated_patterns = {
    '@Suppress("DEPRECATION")': 'supressão de API depreciada',
    '@Deprecated(': 'declaração depreciada',
    'startActivityForResult(': 'startActivityForResult depreciado',
    'override fun onActivityResult(': 'onActivityResult depreciado',
    'override fun onBackPressed(': 'onBackPressed depreciado',
    'systemUiVisibility': 'systemUiVisibility depreciado',
    'overridePendingTransition(': 'overridePendingTransition depreciado',
}
for path in source_files:
    text = path.read_text(errors="ignore")
    for pattern, description in deprecated_patterns.items():
        if pattern in text:
            errors.append(f"{description} em {rel(path)}")

    if path.suffix == ".kt":
        if re.search(r"override\s+fun\s+onNewIntent\s*\([^)]*Intent\?", text, re.DOTALL):
            errors.append(f"assinatura inválida de onNewIntent em {rel(path)}: Intent deve ser não nulo")
        if re.search(
            r"override\s+fun\s+onRequestPermissionsResult\s*\([^)]*Array<\s*out\s+String\s*>",
            text,
            re.DOTALL,
        ):
            errors.append(
                f"assinatura inválida de onRequestPermissionsResult em {rel(path)}: use Array<String>"
            )
        uses_build = "Build.VERSION" in text or "Build.VERSION_CODES" in text
        imports_build = "import android.os.Build" in text or "android.os.Build." in text
        if uses_build and not imports_build:
            errors.append(f"uso de Build sem import android.os.Build em {rel(path)}")

    if '<HorizontalScrollView' in text:
        allowed_profile_carousel = (
            rel(path) == 'app/src/main/res/layout/activity_capture.xml' and
            '@+id/previewCameraProfilesScroll' in text and
            '@+id/previewCameraProfilesRow' in text
        )
        if not allowed_profile_carousel:
            errors.append(f"barra horizontal de ações que exige arrastar em {rel(path)}")

# 11. Detect source files whose only top-level class/object declaration is never referenced.
all_project_text = "\n".join(path.read_text(errors="ignore") for path in source_files)
for path in JAVA.rglob("*.kt"):
    text = path.read_text(errors="ignore")
    declaration = re.search(
        r"(?m)^(?:data\s+|sealed\s+|enum\s+)?(?:class|object|interface)\s+(\w+)",
        text,
    )
    if not declaration:
        continue
    symbol = declaration.group(1)
    if len(re.findall(rf"\b{re.escape(symbol)}\b", all_project_text)) == 1:
        errors.append(f"arquivo Kotlin sem referência: {rel(path)} ({symbol})")

# 12. Detect unreferenced file-based resources.
for resource_type in file_resource_types:
    for name in resource_defs.get(resource_type, set()):
        if (resource_type, name) not in local_refs:
            path_candidates = list(RES.glob(f"{resource_type}*/{name}.*"))
            for candidate in path_candidates:
                errors.append(f"recurso de arquivo sem referência: {rel(candidate)}")

# 13. Regression checks for the requested camera/navigation/security behavior.
capture_text = (JAVA / "com/steadyvault/camera/ui/capture/CaptureActivity.kt").read_text(errors="ignore")
if "private var previewPhotoMode = true" not in capture_text:
    errors.append("o preview não inicia em modo foto")
if "previewBurstButton" in capture_text or "@+id/previewBurstButton" in (RES / "layout/activity_capture.xml").read_text(errors="ignore"):
    errors.append("o botão de sequência ainda existe dentro do preview")
nav_text = (JAVA / "com/steadyvault/camera/ui/navigation/BottomNavigation.kt").read_text(errors="ignore")
if "activity.finish()" in nav_text or "FLAG_ACTIVITY_CLEAR_TOP" in nav_text:
    errors.append("a navegação inferior ainda destrói o histórico necessário ao botão voltar")
if "FLAG_ACTIVITY_REORDER_TO_FRONT" not in nav_text:
    errors.append("a navegação inferior pode empilhar cópias das mesmas telas")

vault_text = (JAVA / "com/steadyvault/camera/ui/vault/PrimaryVaultActivity.kt").read_text(errors="ignore")
for required_token in ("pinUnlockDialogActive", "BIOMETRIC_CANCEL_FALLBACK_MS", "showPinUnlockOnce"):
    if required_token not in vault_text:
        errors.append(f"fluxo de biometria/PIN sem proteção obrigatória: {required_token}")

vault_layout = (RES / "layout/activity_primary_vault.xml").read_text(errors="ignore")
for required_id in ("lockedVaultPanel", "biometricUnlockButton", "pinUnlockButton", "trashButton"):
    if f"@+id/{required_id}" not in vault_layout:
        errors.append(f"layout do cofre sem o controle obrigatório: {required_id}")


# 14. Regression checks for V168: biometric vault chooser, resilient trash and compact icon menu.
for required_token in (
    "showBiometricVaultChooser",
    "openVaultAfterBiometric",
    "SecondaryVaultLock.unlockSession()",
    "TertiaryVaultLock.unlockSession()",
):
    if required_token not in vault_text:
        errors.append(f"seleção biométrica de cofres incompleta: {required_token}")

trash_text = (JAVA / "com/steadyvault/camera/ui/vault/VaultTrashActivity.kt").read_text(errors="ignore")
for required_token in (
    "class VaultTrashActivity : ComponentActivity()",
    "refreshGeneration",
    "val items = VaultTrashRepository.list(this)",
    "if (::adapter.isInitialized) adapter.release()",
):
    if required_token not in trash_text:
        errors.append(f"proteção da lixeira incompleta: {required_token}")

vault_layout_text = (RES / "layout/activity_primary_vault.xml").read_text(errors="ignore")
for icon_name in ("ic_album", "ic_sort", "ic_grid", "ic_trash"):
    if f"@drawable/{icon_name}" not in vault_layout_text:
        errors.append(f"menu compacto da galeria sem ícone: {icon_name}")
if 'android:layout_height="94dp"' not in vault_layout_text:
    errors.append("menu da galeria não foi compactado para uma única faixa")

for required_token in (
    "STANDARD_VIDEO_PREVIEW_BUFFER_WIDTH = 1280",
    "CameraLensCatalog.previewSize(",
    "currentPreviewDisplayAspect()",
):
    if required_token not in capture_text:
        errors.append(f"correção de proporção do preview de vídeo ausente: {required_token}")

# 15. Regression checks for V169: discreet lock screen, biometric association and consistent icon spacing.
locked_panel_match = re.search(
    r'android:id="@\+id/lockedVaultPanel"(?P<body>.*?)</LinearLayout>',
    vault_layout_text,
    re.DOTALL,
)
locked_panel_text = locked_panel_match.group("body").lower() if locked_panel_match else ""
for forbidden in ("disfarce", "terciário", "escolher cofre", "outro cofre"):
    if forbidden in locked_panel_text:
        errors.append(f"a tela bloqueada revela cofres adicionais: {forbidden}")

security_text = (JAVA / "com/steadyvault/camera/storage/security/VaultSecuritySettings.kt").read_text(errors="ignore")
for required_token in (
    "BIOMETRIC_TARGET_ASK",
    "biometricVaultTarget",
    "setBiometricVaultTarget",
    "showBiometricAssociationChooser",
):
    source = security_text if required_token != "showBiometricAssociationChooser" else vault_text
    if required_token not in source:
        errors.append(f"associação biométrica incompleta: {required_token}")

for required_id in (
    "albumFilterLabel", "filterSortLabel", "gridSizeLabel", "trashLabel",
    "selectAllLabel", "albumSelectedLabel", "exportSelectedLabel", "deleteSelectedLabel",
):
    if f"@+id/{required_id}" not in vault_layout_text:
        errors.append(f"menu de ações sem rótulo centralizado: {required_id}")
if vault_layout_text.count('android:tint="@color/accent"') < 8:
    errors.append("os ícones do menu da galeria não usam uma cor única")
if vault_layout_text.count('android:layout_marginEnd="@dimen/action_icon_text_spacing"') < 8:
    errors.append("o espaçamento entre ícone e texto não está padronizado no menu da galeria")

capture_layout_text = (RES / "layout/activity_capture.xml").read_text(errors="ignore")
for background in (
    "bg_capture_action_cyan", "bg_capture_action_green",
    "bg_capture_action_teal", "bg_capture_action_disabled",
):
    if f"@drawable/{background}" not in capture_layout_text:
        errors.append(f"tela inicial fora do padrão de cores do widget: {background}")
if "R.drawable.bg_capture_action_red" not in capture_text:
    errors.append("o botão Parar não muda para o estado vermelho durante a gravação")

widget_start_text = (
    JAVA / "com/steadyvault/camera/widgets/WidgetStartReceiver.kt"
).read_text(errors="ignore")
service_start_position = widget_start_text.find(
    "RecordingServiceRouter.startHeadless(context, fps)"
)
widget_update_position = widget_start_text.find(
    "WidgetRenderer.updateRecordingControls(context)",
    widget_start_text.find("CaptureStateStore.update(context, preparing)")
)
if (
    service_start_position < 0 or
    widget_update_position < 0 or
    service_start_position > widget_update_position
):
    errors.append("o widget atualiza o RemoteViews antes de iniciar a gravação")

widget_renderer_text = (
    JAVA / "com/steadyvault/camera/widgets/WidgetRenderer.kt"
).read_text(errors="ignore")
for widget_type in ("EXPANDED", "COMPACT", "LOCK_SCREEN"):
    if f"WidgetType.{widget_type}" not in widget_renderer_text:
        errors.append(f"o renderizador não atualiza o widget {widget_type}")
for required_token in (
    "WidgetStartReceiver.ACTION_START",
    "PendingIntent.getService(",
    "CaptureService.ACTION_STOP",
    "CaptureService.EXTRA_USER_REQUESTED_STOP",
):
    if required_token not in widget_renderer_text:
        errors.append(f"controle obrigatório ausente nos widgets: {required_token}")
if "WidgetStopReceiver" in widget_renderer_text:
    errors.append("a parada do widget ainda depende de um BroadcastReceiver intermediário")
for required_token in (
    "fun forceRecordingControls(context: Context)",
    "renderRecordingControls(context, force = true)",
):
    if required_token not in widget_renderer_text:
        errors.append(f"restauração forçada do widget ausente: {required_token}")

capture_state_receiver_text = (
    JAVA / "com/steadyvault/camera/core/state/CaptureStateReceiver.kt"
).read_text(errors="ignore")
if "WidgetRenderer.updateRecordingControls(context)" not in capture_state_receiver_text:
    errors.append("o receptor final não atualiza os controles dos widgets")
if "wasBusy" in capture_state_receiver_text or "wasFinalizing" in capture_state_receiver_text:
    errors.append("o receptor ainda pode ignorar o estado final já persistido pelo serviço")
for required_token in (
    "CaptureService.EXTRA_STATE_OWNER",
    "CaptureService.EXTRA_STARTED_AT_ELAPSED",
):
    if required_token not in capture_state_receiver_text:
        errors.append(f"estado de sessão não saiu do caminho crítico: {required_token}")

for provider_name in (
    "ExpandedControlWidget.kt",
    "CompactControlWidget.kt",
    "LockScreenControlWidget.kt",
):
    provider_text = (
        JAVA / f"com/steadyvault/camera/widgets/{provider_name}"
    ).read_text(errors="ignore")
    if "RecordingServiceRouter.stop(" in provider_text or "onDisabled(" in provider_text:
        errors.append(f"remover o widget pode parar a gravação: {provider_name}")
    if "Intent.ACTION_MY_PACKAGE_REPLACED" not in provider_text:
        errors.append(f"widget não renova ações depois de atualizar o APK: {provider_name}")

recording_router_text = (
    JAVA / "com/steadyvault/camera/capture/service/RecordingServiceRouter.kt"
).read_text(errors="ignore")
capture_service_text = (
    JAVA / "com/steadyvault/camera/capture/service/CaptureService.kt"
).read_text(errors="ignore")
deprecated_source_tokens = {
    '@Suppress("DEPRECATION")': "supressão de API depreciada",
    ".databaseEnabled": "WebSQL/databaseEnabled depreciado",
    ".statusBarColor": "setter de statusBarColor depreciado",
    ".navigationBarColor": "setter de navigationBarColor depreciado",
}
for source in JAVA.rglob("*.kt"):
    text = source.read_text(errors="ignore")
    for token, label in deprecated_source_tokens.items():
        if token in text:
            errors.append(f"{label} em {rel(source)}")
    if re.search(r'Chrome/\d', text):
        errors.append(f"User-Agent de Chrome com versão fixa em {rel(source)}")
for source in RES.rglob("*.xml"):
    text = source.read_text(errors="ignore")
    for token in ("android:statusBarColor", "android:navigationBarColor", "android:navigationBarDividerColor"):
        if token in text:
            errors.append(f"atributo de barra do sistema depreciado em {rel(source)}: {token}")

required_architecture = {
    JAVA / "com/steadyvault/camera/ui/settings/StorageManagementActivity.kt": ("class StorageManagementActivity", "clearApplicationUserData", "ID_BROWSER_DATA"),
    JAVA / "com/steadyvault/camera/storage/vault/RecordingRecoveryRepository.kt": ('File(context.filesDir, "vaults/recovery")', "preserveBroken"),
    JAVA / "com/steadyvault/camera/storage/vault/PublicMediaExporter.kt": ("object PublicMediaExporter", "PublicMediaRegistry.add", "MediaStore.MediaColumns.IS_PENDING"),
    JAVA / "com/steadyvault/camera/storage/vault/PublicMediaRegistry.kt": ("object PublicMediaRegistry", "contentResolver.delete", "OpenableColumns.SIZE"),
    JAVA / "com/steadyvault/camera/storage/vault/AppStorageCatalog.kt": ("ID_PUBLIC_GALLERY", "ID_NO_BACKUP", "PublicMediaRegistry.metrics", "PublicMediaRegistry.removeAll"),
    JAVA / "com/steadyvault/camera/ui/browser/BrowserWebViewConfigurator.kt": ("object BrowserWebViewConfigurator", "WebStorageCompat.deleteBrowsingData", "getCurrentWebViewPackage"),
    JAVA / "com/steadyvault/camera/core/camera/CctWhiteBalanceController.kt": ("COLOR_CORRECTION_MODE_CCT", "COLOR_CORRECTION_COLOR_TEMPERATURE", "COLOR_CORRECTION_COLOR_TINT"),
    JAVA / "com/steadyvault/camera/capture/health/RecordingHealthMonitor.kt": ("class RecordingHealthMonitor", "Handler"),
    JAVA / "com/steadyvault/camera/capture/finalize/RecordingFinalizer.kt": ("object RecordingFinalizer", "FileDurability"),
    JAVA / "com/steadyvault/camera/ui/vault/PrivateVaultGalleryActivity.kt": ("abstract class PrivateVaultGalleryActivity", "listVaultAll", "updateMetadata"),
    JAVA / "com/steadyvault/camera/storage/vault/PrivateVaultRepositoryCore.kt": ("object PrivateVaultRepositoryCore", "fun list("),
}
for source, tokens in required_architecture.items():
    text = source.read_text(errors="ignore") if source.exists() else ""
    for token in tokens:
        if token not in text:
            errors.append(f"arquitetura obrigatória ausente em {rel(source)}: {token}")

# A grade dos cofres usa a coleção completa; rolar não pode disparar uma nova "página".
for vault_activity in (
    JAVA / "com/steadyvault/camera/ui/vault/PrimaryVaultActivity.kt",
    JAVA / "com/steadyvault/camera/ui/vault/PrivateVaultGalleryActivity.kt",
):
    vault_scroll_text = vault_activity.read_text(errors="ignore")
    for forbidden in ("setOnScrollListener", "loadNextVaultPage", "loadNextPage", "PAGE_PREFETCH_THRESHOLD"):
        if forbidden in vault_scroll_text:
            errors.append(f"paginação visual voltou a interferir no scroll em {vault_activity.name}: {forbidden}")

# O request Camera2 permanece congelado durante a gravação.
for forbidden in (
    "EXTRA_EXPOSURE_DIAGNOSTICS",
    "createExposureCaptureCallback",
    "submitExposureAdjustedRequest",
):
    if forbidden in capture_service_text:
        errors.append(f"núcleo direto voltou a adaptar exposição durante a gravação: {forbidden}")
if "CaptureRequest.SENSOR_EXPOSURE_TIME" in capture_service_text:
    errors.append("CaptureService não deve fixar exposição diretamente")


camera_zoom_text = (JAVA / "com/steadyvault/camera/core/camera/CameraZoom.kt").read_text(errors="ignore")
for token in ("CaptureRequest.CONTROL_ZOOM_METHOD", "CONTROL_ZOOM_METHOD_ZOOM_RATIO"):
    if token not in camera_zoom_text:
        errors.append(f"zoom explícito do Android 16 ausente: {token}")

recorder_text = (JAVA / "com/steadyvault/camera/capture/recorder/HardwareRecorder.kt").read_text(errors="ignore")
if "setInteger(MediaFormat.KEY_LATENCY" in recorder_text:
    errors.append("encoder voltou a forçar latência mínima; gravação deve usar a fila nativa do hardware")
if "MediaRecorder.AudioSource.MIC" in recorder_text:
    errors.append("gravação de vídeo deve usar fonte de áudio CAMCORDER")
if "MediaRecorder.AudioSource.CAMCORDER" not in recorder_text:
    errors.append("fonte CAMCORDER ausente no gravador")
if "setPreferredMicrophoneDirection" not in recorder_text:
    errors.append("direcionamento do microfone conforme câmera ausente")
if "AudioTimestamp.TIMEBASE_BOOTTIME" not in recorder_text or "resolveFirstVideoCaptureNs" not in recorder_text:
    errors.append("áudio e vídeo não estão ancorados à mesma época monotônica de captura")
if "MediaFormat.KEY_PROFILE" not in recorder_text or "MediaFormat.KEY_LEVEL" not in recorder_text:
    errors.append("encoder deve configurar profile + level quando disponíveis")
if (JAVA / "com/steadyvault/camera/core/performance/GamePerformanceController.kt").exists():
    errors.append("GamePerformanceController ainda presente")
for forbidden_token in ("GameManager", "GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE", "setGameState"):
    if forbidden_token in all_kotlin_text:
        errors.append(f"integração Game State ainda presente: {forbidden_token}")
if "RealtimePerformanceHint" in all_kotlin_text:
    errors.append("RealtimePerformanceHint não deve entrar no pipeline de captura")
if "setSustainedPerformanceMode" in all_kotlin_text:
    errors.append("modo de desempenho sustentado voltou a limitar o pico disponível durante a gravação")

dual_tokens = ("ConcurrentCameraService", "dualCamera", "dual_camera", "dual_video", "câmera dupla", "duas câmeras")
for source in list(JAVA.rglob("*.kt")) + list(RES.rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]:
    text = source.read_text(errors="ignore")
    for token in dual_tokens:
        if token.lower() in text.lower():
            errors.append(f"resíduo de câmera dupla em {rel(source)}: {token}")

for source_name, source_text in (
    ("roteador", recording_router_text),
    ("serviço principal", capture_service_text),
):
    if "EXTRA_USER_REQUESTED_STOP" not in source_text:
        errors.append(f"parada sem autorização explícita no {source_name}")
for required_token in (
    "fun stop(context: Context, hapticAcknowledged: Boolean = false): Boolean",
    'CaptureService::class.java',
    "EXTRA_STOP_HAPTIC_ACKNOWLEDGED",
):
    if required_token not in recording_router_text:
        errors.append(f"roteamento direto da parada ausente: {required_token}")
if "commandDelivered" in recording_router_text:
    errors.append("a parada ainda tenta entregar o comando para mais de um serviço")
for required_token in (
    "scheduleCameraRecovery(",
    "cameraRecoveryScheduled",
    "Gravando • câmera temporariamente ocupada",
):
    if required_token not in capture_service_text:
        errors.append(f"recuperação de câmera interrompida: {required_token}")
for forbidden_token in (
    "retryNextConfigurationFromWorker(",
    "fourK60CompatibilityAttempted",
    "hdrFallbackAttempted",
    "failedConfigurations",
    "configurationFailures",
):
    if forbidden_token in capture_service_text:
        errors.append(f"captura ainda possui fallback pré-gravação: {forbidden_token}")
for required_token in (
    "failSelectedConfigurationFromWorker(",
    "Captura cancelada antes do primeiro quadro",
):
    if required_token not in capture_service_text:
        errors.append(f"fluxo direto de captura incompleto: {required_token}")
for required_token in (
    "rawContainsData",
    "claimRecording()",
    "if (!rawContainsData) runCatching { rawFile?.delete() }",
    "finishWithRecoveredPartial(",
    '"Parar e salvar"',
):
    if required_token not in capture_service_text:
        errors.append(f"parada pela notificação pode descartar o trecho: {required_token}")
if '"Gravação cancelada"' in capture_service_text:
    errors.append("a parada pela notificação ainda pode declarar e apagar uma gravação iniciada")
for source_name, source_text in (("serviço principal", capture_service_text),):
    send_state_match = re.search(
        r"private fun sendState\(message: String\)(?P<body>.*?)\n    private fun ",
        source_text,
        re.DOTALL,
    )
    if not send_state_match or "CaptureStateStore.update(" in send_state_match.group("body"):
        errors.append(f"o {source_name} persiste preferências no caminho de abertura da câmera")
    if "UUID.randomUUID()" in source_text:
        errors.append(f"o {source_name} ainda inicializa entropia no clique de gravação")
for source_name, source_text in (("serviço principal", capture_service_text),):
    for required_token in (
        "CaptureStateStore.completeSessionIfBusy",
        "WidgetRenderer.forceRecordingControls",
        "RecordingStorageMonitor",
    ):
        if required_token not in source_text:
            errors.append(f"finalização visual/leve incompleta no {source_name}: {required_token}")

storage_monitor_text = (
    JAVA / "com/steadyvault/camera/core/storage/RecordingStorageMonitor.kt"
).read_text(errors="ignore")
for required_token in (
    "THREAD_PRIORITY_BACKGROUND",
    "CHECK_INTERVAL_SECONDS = 30L",
    "RecordingStorageGuard.checkOngoing",
):
    if required_token not in storage_monitor_text:
        errors.append(f"monitor de armazenamento fora da thread leve: {required_token}")
if "RecordingStorageGuard.checkOngoing" in capture_service_text:
    errors.append("consulta de armazenamento voltou para a thread do serviço de câmera")

hardware_recorder_text = (
    JAVA / "com/steadyvault/camera/capture/recorder/HardwareRecorder.kt"
).read_text(errors="ignore")
media_player_text = (
    JAVA / "com/steadyvault/camera/ui/vault/MediaPlayerActivity.kt"
).read_text(errors="ignore")
for required_token in ("Exportar mídia", "Arquivos do celular", "Compartilhar", "saveMediaToFiles", "Intent.ACTION_SEND", "FileProvider.getUriForFile"):
    if required_token not in media_player_text:
        errors.append(f"exportação fácil de mídia incompleta: {required_token}")
for required_token in (
    "videoSilenceDurationMs(",
    "lastVideoSampleElapsedMs.set(SystemClock.elapsedRealtime())",
    "startVideoMuxThread()",
    "ArrayBlockingQueue<QueuedVideoSample>",
    "VIDEO_MUX_QUEUE_CAPACITY = 180",
    "enqueueVideoSample(buffer, sampleInfo, outputPtsUs)",
    "muxerCoordinator.writeVideo(ByteBuffer.wrap(data, 0, sample.size)",
    "MediaFormat.KEY_OPERATING_RATE",
    "MediaFormat.KEY_PRIORITY, 0",
    "MediaFormat.KEY_ALLOW_FRAME_DROP",
    "directInfo.set(sourceInfo.offset, sourceInfo.size, ptsUs, flags)",
    "writeVideoEndOfStream",
    "writeAudioEndOfStream",
    "drainAudioEncoder(outputInfo, endOfStream = false)",
):
    if required_token not in hardware_recorder_text:
        errors.append(f"núcleo Camera2 -> MediaCodec -> MediaMuxer incompleto: {required_token}")
for forbidden_token in (
    "PresentationOrderBuffer",
    "HIGH_SPEED_PRESENTATION_REORDER_DEPTH",
    "MediaFormat.KEY_CAPTURE_RATE",
    "MediaFormat.KEY_MAX_FPS_TO_ENCODER",
    "outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE",
    "bufferedAudio",
    "MAX_BUFFERED_AUDIO_SAMPLES",
    "flushBufferedAudio(",
    "lastWrittenVideoPtsUs",
    "SOFT_LIMIT_CURVE",
    "kotlin.math.exp(",
):
    if forbidden_token in hardware_recorder_text:
        errors.append(f"núcleo direto voltou a manipular ou sobrecarregar frames: {forbidden_token}")
for removed_path in (
    JAVA / "com/steadyvault/camera/capture/timing/PresentationOrderBuffer.kt",
):
    if removed_path.exists():
        errors.append(f"utilitário antigo de manipulação temporal voltou ao projeto: {removed_path.name}")

optimization_service_text = (
    JAVA / "com/steadyvault/camera/processing/service/VideoOptimizationService.kt"
).read_text(errors="ignore")
bulk_import_text = (
    JAVA / "com/steadyvault/camera/ui/vault/VaultBulkImportRunner.kt"
).read_text(errors="ignore")
if "Process.THREAD_PRIORITY_BACKGROUND" not in optimization_service_text:
    errors.append("otimização voltou a competir em prioridade normal com a gravação")
for required_token in (
    "Process.THREAD_PRIORITY_BACKGROUND",
    "VaultStartupCoordinator.isCapturePriorityActive(context)",
    "ItemStatus.PAUSED_FOR_CAPTURE",
    "shouldYieldToActiveCapture(context)",
    "seenUris.remove(uriKey)",
):
    if required_token not in bulk_import_text:
        errors.append(f"importação não respeita prioridade da gravação: {required_token}")

screen_capture_text = (
    JAVA / "com/steadyvault/camera/ui/apps/VaultScreenCaptureService.kt"
).read_text(errors="ignore")
for required_token in (
    "VaultStartupCoordinator.isCapturePriorityActive(this)",
    "fun yieldToCameraCapture(context: Context)",
    "Process.THREAD_PRIORITY_BACKGROUND",
):
    if required_token not in screen_capture_text:
        errors.append(f"captura de tela não cede prioridade à câmera: {required_token}")
if "VaultScreenCaptureService.yieldToCameraCapture(this)" not in capture_service_text:
    errors.append("CaptureService não reserva o encoder antes de gravar")

if "StabilizationSelectionPolicy" in capture_service_text:
    errors.append("CaptureService ainda referencia seleção automática de estabilização")
if (JAVA / "com/steadyvault/camera/core/camera/StabilizationSelectionPolicy.kt").exists():
    errors.append("política automática de estabilização ainda existe no código de produção")
if (ROOT / "app/src/test/java/com/steadyvault/camera/core/camera/StabilizationSelectionPolicyTest.kt").exists():
    errors.append("teste órfão da política automática de estabilização ainda existe")
if "resolveAutomaticStabilization" in capture_service_text:
    errors.append("CaptureService ainda contém resolução automática de estabilização")
if "StabilizationPerformanceStore" in capture_service_text:
    errors.append("aprendizado persistente de estabilização voltou ao caminho de captura")

ois_capability_text = (
    JAVA / "com/steadyvault/camera/core/camera/OpticalStabilizationCapability.kt"
).read_text(errors="ignore")
ois_policy_text = (
    JAVA / "com/steadyvault/camera/core/camera/OisSupportPolicy.kt"
).read_text(errors="ignore")
preview_controller_text = (
    JAVA / "com/steadyvault/camera/ui/capture/IdleCameraPreviewController.kt"
).read_text(errors="ignore")
for required_token in (
    "characteristics.physicalCameraIds",
    "availablePhysicalCameraRequestKeys",
    "logicalRequestAvailable",
):
    if required_token not in ois_capability_text:
        errors.append(f"OIS de câmera lógica/física incompleto: {required_token}")
for forbidden_token in (
    "setPhysicalCameraKey(",
    "getPhysicalCameraKey(",
    "createCaptureRequest(template, physicalIds)",
):
    if forbidden_token in ois_capability_text:
        errors.append(f"OIS ainda força request físico inseguro: {forbidden_token}")
for required_token in (
    "logicalRequestAvailable ->",
):
    if required_token not in ois_policy_text:
        errors.append(f"fallback seguro de metadado OIS incompleto: {required_token}")
if "profileSatisfiesExplicitStabilization" not in capture_service_text:
    errors.append("CaptureService sem validação explícita de estabilização")
if "mayUseAlternativeCameraForRequestedOis" in capture_service_text:
    errors.append("OIS não pode trocar silenciosamente para outra câmera")
if "buildFinalRecordingRequest" in capture_service_text:
    errors.append("pipeline de vídeo não deve reconstruir um segundo request antes de iniciar a gravação")
if "OpticalStabilizationCapability.apply(builder, it, enabled = true)" not in preview_controller_text:
    errors.append("preview não aplica a mesma capacidade OIS da gravação")

capability_matrix_text = (
    JAVA / "com/steadyvault/camera/core/capability/CaptureCapabilityMatrix.kt"
).read_text(errors="ignore")
for required_token in (
    "Build.FINGERPRINT",
    "physicalCharacteristics",
    "findHardwareEncoders",
    "persist(context.applicationContext, matrix)",
):
    if required_token not in capability_matrix_text:
        errors.append(f"matriz persistente de capacidades incompleta: {required_token}")
for required_token in (
    "ImageFormat.PRIVATE",
    "queryRegularSessionSupport",
    "isSessionConfigurationSupported",
    "CameraDevice.TEMPLATE_RECORD",
):
    if required_token not in capability_matrix_text:
        errors.append(f"validação real de resolução/FPS incompleta: {required_token}")
for required_token in (
    "HardwareSupportPolicy.shouldExpose",
    "refreshCapabilityMatrix()",
):
    if required_token not in capture_text:
        errors.append(f"interface de captura não acompanha capacidades: {required_token}")
if "synchronizeSelectedFeaturesWithCapabilities" in capture_text:
    errors.append("interface voltou a substituir configurações do usuário após analisar capacidades")
for required_token in (
    "refreshHardwareFeatureOptions",
    "updateSpinnerVisibility",
    "CaptureCapabilityMatrix.cached(this)",
):
    if required_token not in settings_activity_text:
        errors.append(f"ajustes não acompanham capacidades: {required_token}")
if "Zebra / peaking" in capture_text:
    errors.append("interface ainda oferece zebra/focus peaking sem implementação")

for required_token in (
    "sessionConfiguration",
    ".setSessionParameters(",
    "selectTargetFpsRange",
):
    if required_token not in capture_service_text:
        errors.append(f"sessão Camera2 sem política estrita de 60 FPS: {required_token}")
for required_token in (
    "START_REDELIVER_INTENT",
    "Intent.ACTION_SCREEN_OFF",
    "Intent.ACTION_SCREEN_ON",
    "recordingHealthMonitor",
    "VIDEO_FRAME_STALL_RECOVERY_MS",
    "preserveInterruptedRecording()",
    "trecho preservado no cofre",
    "recoverRecordingSegment(",
    "retryRecordingSession(",
    "segmentRecoveryInProgress",
    "scheduleInitialCameraAvailabilityRetry(",
    "CameraManager.AvailabilityCallback",
    "ERROR_CAMERA_IN_USE",
    "ERROR_MAX_CAMERAS_IN_USE",
    "INITIAL_CAMERA_BUSY_RETRY_MS",
):
    if required_token not in capture_service_text:
        errors.append(f"proteção ao apagar/acender a tela incompleta: {required_token}")

initial_camera_retry_match = re.search(
    r"private fun scheduleInitialCameraAvailabilityRetry\((?P<body>.*?)\n    private fun retryInitialCameraOpen",
    capture_service_text,
    re.DOTALL,
)
if (
    not initial_camera_retry_match or
    "releaseRecordingResources(" in initial_camera_retry_match.group("body") or
    "prepareAndOpenBestConfiguration(" in initial_camera_retry_match.group("body")
):
    errors.append("a espera pela câmera do desbloqueio facial refaz a preparação da gravação")

recovery_text = (
    JAVA / "com/steadyvault/camera/storage/vault/RecordingRecoveryRepository.kt"
).read_text(errors="ignore")
for required_token in (
    "recoverStaleRecordings(",
    "recoverOne(",
    "hasUsableVideo(",
    "shouldProtectFromCacheCleanup(",
    'File(context.filesDir, "vaults/recovery")',
    "preserveBroken(",
):
    if required_token not in recovery_text:
        errors.append(f"recuperação de gravação interrompida incompleta: {required_token}")

state_store_text = (
    JAVA / "com/steadyvault/camera/core/state/CaptureStateStore.kt"
).read_text(errors="ignore")
for required_token in (
    "markRecordingServiceAlive(",
    "clearRecordingServiceHeartbeat(",
    "reconcileInterruptedRecording(",
):
    if required_token not in state_store_text:
        errors.append(f"heartbeat do serviço de gravação incompleto: {required_token}")

cleanup_text = (
    JAVA / "com/steadyvault/camera/storage/vault/VaultCleanupRepository.kt"
).read_text(errors="ignore")
if "RecordingRecoveryRepository.shouldProtectFromCacheCleanup(child)" not in cleanup_text:
    errors.append("a limpeza de cache pode apagar um trecho de gravação recuperável")

vault_repository_text = (
    JAVA / "com/steadyvault/camera/storage/vault/VaultRepository.kt"
).read_text(errors="ignore")
for required_token in (
    "cleanupStalePrivateCaptures(",
    "PRIVATE_CAPTURE_RETENTION_MS",
    "RecordingRecoveryRepository.hasUsableVideo(working)",
):
    if required_token not in vault_repository_text:
        errors.append(f"recuperação de captura privada incompleta: {required_token}")

screen_capture_text = (
    JAVA / "com/steadyvault/camera/ui/apps/VaultScreenCaptureService.kt"
).read_text(errors="ignore")
for required_token in (
    "EXTRA_USER_REQUESTED_CLOSE",
    "finalizeScreenRecording(",
    "RecordingRecoveryRepository.hasUsableVideo(activeFiles.working)",
):
    if required_token not in screen_capture_text:
        errors.append(f"finalização segura da gravação de tela incompleta: {required_token}")

discreet_text = (
    JAVA / "com/steadyvault/camera/ui/capture/DiscreetRecordingActivity.kt"
).read_text(errors="ignore")
for required_token in (
    "installDoubleTapStop()",
    "stopRecordingAndExit()",
    "RecordingServiceRouter.stop(this, hapticAcknowledged = hapticAcknowledged)",
    "RecordingServiceRouter.startHeadless(this, targetFps)",
    "finishAndRemoveTask()",
):
    if required_token not in discreet_text:
        errors.append(f"parada por duplo toque da tela preta incompleta: {required_token}")
for forbidden_token in (
    "installDoubleTapRestore()",
    "restoreRecordingUi()",
):
    if forbidden_token in discreet_text:
        errors.append(f"a tela preta ainda restaura controles no duplo toque: {forbidden_token}")

close_preview_match = re.search(
    r"private fun requestClosePreview\(\)(?P<body>.*?)\n    private fun ",
    capture_text,
    re.DOTALL,
)
if not close_preview_match or "stopRecordingSafely()" in close_preview_match.group("body"):
    errors.append("fechar o preview ainda pode parar a gravação")

# 16. Regression checks for V170: immersive media viewer, one-time hint, swipe dismissal and safe import return.
player_text = (JAVA / "com/steadyvault/camera/ui/vault/MediaPlayerActivity.kt").read_text(errors="ignore")
player_layout_text = (RES / "layout/activity_media_player.xml").read_text(errors="ignore")
for required_token in (
    "setSystemBarsVisible(false)",
    "viewerControlsVisible = false",
    "toggleViewerControls()",
    "maybeShowGestureHint()",
    "KEY_GESTURE_HINT_SHOWN",
    "imageView.setOnDismissListener { dismissToVault() }",
    "videoView.setOnDismissListener(null)",
):
    if required_token not in player_text:
        errors.append(f"visualizador imersivo incompleto: {required_token}")
for required_id in ("viewerHeader", "viewerActions", "viewerToolBar", "videoControls", "videoCenterControls"):
    match = re.search(
        rf'android:id="@\+id/{required_id}"(?P<body>.*?)(?:</LinearLayout>|</FrameLayout>)',
        player_layout_text,
        re.DOTALL,
    )
    if not match or 'android:visibility="gone"' not in match.group("body"):
        errors.append(f"controle do visualizador não inicia oculto: {required_id}")

zoom_video_text = (JAVA / "com/steadyvault/camera/ui/gesture/ZoomableVideoView.kt").read_text(errors="ignore")
zoom_image_text = (JAVA / "com/steadyvault/camera/ui/gesture/ZoomableImageView.kt").read_text(errors="ignore")
zoom_controller_text = (JAVA / "com/steadyvault/camera/ui/gesture/ZoomGestureController.kt").read_text(errors="ignore")
for source_name, source_text in (
    ("vídeo", zoom_video_text),
    ("imagem", zoom_image_text),
    ("controlador", zoom_controller_text),
):
    if "Dismiss" not in source_text and "dismiss" not in source_text:
        errors.append(f"gesto de minimizar ausente em {source_name}")

for activity_name in ("PrimaryVaultActivity.kt", "PrivateVaultGalleryActivity.kt"):
    activity_text = (JAVA / f"com/steadyvault/camera/ui/vault/{activity_name}").read_text(errors="ignore")
    for required_token in ("VaultImportSession()", "importSession.begin()", "importSession.consume()"):
        if required_token not in activity_text:
            errors.append(f"retorno seguro da importação incompleto em {activity_name}: {required_token}")
for activity_name in ("SecondaryVaultActivity.kt", "TertiaryVaultActivity.kt"):
    activity_text = (JAVA / f"com/steadyvault/camera/ui/vault/{activity_name}").read_text(errors="ignore")
    if "PrivateVaultGalleryActivity()" not in activity_text:
        errors.append(f"cofre privado não reutiliza a tela compartilhada em {activity_name}")



# 17. Regression checks for V171: compact icon groups and categorized home actions.
dimens_text = (RES / "values/dimens.xml").read_text(errors="ignore")
if '<dimen name="action_icon_text_spacing">4dp</dimen>' not in dimens_text:
    errors.append("o espaçamento global entre ícone e texto não foi reduzido para 4dp")

trash_layout_text = (RES / "layout/activity_vault_trash.xml").read_text(errors="ignore")
for required_id in ("trashBackButton", "trashEmptyButton"):
    match = re.search(rf'<LinearLayout android:id="@\+id/{required_id}"', trash_layout_text)
    if not match:
        errors.append(f"botão da lixeira sem grupo centralizado de ícone e texto: {required_id}")
for forbidden in ('android:id="@+id/trashBackButton"[^>]*drawableStart', 'android:id="@+id/trashEmptyButton"[^>]*drawableStart'):
    if re.search(forbidden, trash_layout_text):
        errors.append("a lixeira ainda usa drawable composto com espaçamento irregular")

for required_id in ("biometricUnlockButton", "pinUnlockButton"):
    if not re.search(rf'<LinearLayout android:id="@\+id/{required_id}"', vault_layout_text):
        errors.append(f"desbloqueio sem grupo centralizado de ícone e texto: {required_id}")

for required_id in ("photoActionsGroup", "videoActionsGroup", "photoActionsTitle", "videoActionsTitle"):
    if f'@+id/{required_id}' not in capture_layout_text:
        errors.append(f"tela inicial sem agrupamento por categoria: {required_id}")
start_button_match = re.search(r'android:id="@\+id/startButton"(?P<body>[^>]*)', capture_layout_text)
if not start_button_match or 'android:layout_height="66dp"' not in start_button_match.group("body"):
    errors.append("o botão Gravar vídeo não está maior que as ações secundárias")


# 18. Regression checks for V176: Samsung-inspired selector, per-camera profiles,
# selfie support and non-distorted preview.
camera_catalog_path = JAVA / "com/steadyvault/camera/core/camera/CameraLensCatalog.kt"
if not camera_catalog_path.exists():
    errors.append("catálogo dinâmico de câmeras ausente")
else:
    camera_catalog_text = camera_catalog_path.read_text(errors="ignore")
    for required_token in ("LENS_FACING_FRONT", "resolveCameraId", "previewSize", "Selfie"):
        if required_token not in camera_catalog_text:
            errors.append(f"catálogo de câmeras incompleto: {required_token}")

for required_id in (
    "previewCameraSwitchButton",
    "previewSettingsSheet",
    "previewCameraProfilesRow",
    "previewSettingsGeneralTab",
    "previewSettingsCamerasTab",
    "previewSettingsPhotoTab",
    "previewSettingsVideoTab",
):
    if f"@+id/{required_id}" not in capture_layout_text:
        errors.append(f"interface profissional de câmeras sem controle obrigatório: {required_id}")
for required_token in (
    "flipPreviewCamera",
    "selectPreviewCamera",
    "renderCameraProfileCards",
    "CameraProfileStore",
    "selectedCameraId",
    "CameraLensCatalog.previewSize(",
):
    if required_token not in capture_text:
        errors.append(f"integração profissional das câmeras incompleta: {required_token}")

camera_profile_path = JAVA / "com/steadyvault/camera/core/settings/CameraProfileStore.kt"
if not camera_profile_path.exists():
    errors.append("armazenamento de perfis por câmera ausente")
else:
    camera_profile_text = camera_profile_path.read_text(errors="ignore")
    for required_token in ("FunctionMode", "PHOTO", "VIDEO", "activate", "onSnapshotSaved", "summary"):
        if required_token not in camera_profile_text:
            errors.append(f"perfis por câmera incompletos: {required_token}")

settings_text = (JAVA / "com/steadyvault/camera/core/settings/CaptureSettings.kt").read_text(errors="ignore")
if 'val selectedCameraId: String?' not in settings_text or '"selected_camera_id"' not in settings_text:
    errors.append("a câmera escolhida não é persistida nas configurações")

idle_preview_text = (JAVA / "com/steadyvault/camera/ui/capture/IdleCameraPreviewController.kt").read_text(errors="ignore")
photo_service_text = (JAVA / "com/steadyvault/camera/photo/service/PhotoService.kt").read_text(errors="ignore")
capture_service_text = (JAVA / "com/steadyvault/camera/capture/service/CaptureService.kt").read_text(errors="ignore")
if "settings.selectedCameraId" not in idle_preview_text:
    errors.append("o preview não aplica a câmera selecionada")
if "lensFacing" not in photo_service_text or "LENS_FACING_FRONT" not in photo_service_text:
    errors.append("o serviço de foto não está preparado para selfie")
if "isEligibleCamera" not in capture_service_text:
    errors.append("o serviço de vídeo não aplica a câmera selecionada")
for required_token in (
    "BackgroundRecordingZoom.cameraSelection(this)",
    "quickCaptureZoom?.cameraId",
    "zoomRatio = selection.requestZoomRatio",
    "!previewCaptureRequested",
):
    if required_token not in photo_service_text:
        errors.append(f"zoom compartilhado de foto e vídeo incompleto: {required_token}")
if re.search(
    r"val quickCaptureZoom = if \(\s*fromWidget",
    photo_service_text,
    re.DOTALL,
):
    errors.append("o zoom rápido ainda está limitado somente à foto do widget")


# 20. Regression checks for V175: 240 FPS playback continuity and vault lock on tab change.
for required_token in (
    "isVeryHighFrameRateSlowMotion()",
    "EXTENSION_RENDERER_MODE_OFF",
    "HIGH_FRAME_RATE_BACK_BUFFER_MS",
    "HEALTH_HFR_STALL_SAMPLES",
    "selectInitialEngine()",
):
    if required_token not in zoom_video_text:
        errors.append(f"otimização de 240 FPS incompleta: {required_token}")
if "requestEngineOpen(preservePosition = true)" in re.search(
    r"fun setPlaybackSpeed\(requested: Float\) \{(?P<body>.*?)\n    \}",
    zoom_video_text,
    re.DOTALL,
).group("body"):
    errors.append("a troca de velocidade ainda reinicia o player")
nav_text = (JAVA / "com/steadyvault/camera/ui/navigation/BottomNavigation.kt").read_text(errors="ignore")
for required_token in ("onBeforeNavigate", "onBeforeNavigate?.invoke()"):
    if required_token not in nav_text:
        errors.append(f"bloqueio do cofre ao trocar de aba incompleto: {required_token}")
if "lockVaultForBottomNavigation" not in vault_text:
    errors.append("o cofre não é bloqueado antes de sair pela navegação inferior")

# 21. Regression checks for V170 background import, discreet identity and S25-class 8K exposure.
import_service_text = (JAVA / "com/steadyvault/camera/ui/vault/VaultImportService.kt").read_text(errors="ignore")
queue_store_text = (JAVA / "com/steadyvault/camera/ui/vault/VaultImportQueueStore.kt").read_text(errors="ignore")
visual_identity_text = (JAVA / "com/steadyvault/camera/core/settings/VisualIdentityStore.kt").read_text(errors="ignore")
capture_settings_text = (JAVA / "com/steadyvault/camera/core/settings/CaptureSettings.kt").read_text(errors="ignore")
capability_matrix_text = (JAVA / "com/steadyvault/camera/core/capability/CaptureCapabilityMatrix.kt").read_text(errors="ignore")
for required_token in (
    "FOREGROUND_SERVICE_DATA_SYNC",
    '.ui.vault.VaultImportService',
    'android:foregroundServiceType="dataSync"',
):
    if required_token not in manifest_text:
        errors.append(f"foreground service de importação incompleto: {required_token}")
for required_token in (
    "ACTION_CANCEL",
    "onTimeout(startId: Int, fgsType: Int)",
    "buildPausedNotification",
    "Processando arquivos",
    "startForeground",
):
    if required_token not in import_service_text:
        errors.append(f"serviço persistente de importação incompleto: {required_token}")
for required_token in (
    "noBackupFilesDir",
    "next_index",
    "writeQueue",
):
    if required_token not in queue_store_text:
        errors.append(f"fila persistente de importação incompleta: {required_token}")
for required_token in (
    "KEY_SYSTEM_PAUSE_REQUESTED",
    "VaultImportQueueStore.setNextIndex",
    "Process.THREAD_PRIORITY_BACKGROUND",
):
    if required_token not in bulk_import_text:
        errors.append(f"checkpoint/pausa da importação incompleto: {required_token}")
for required_token in (
    "MODE_FILES", "MODE_UTILITY", "MODE_NOTES", "MODE_CAMERA",
    "customLabel", "neutralActions", "notificationIdentity", "widgetLogo",
):
    if required_token not in visual_identity_text:
        errors.append(f"identidade discreta incompleta: {required_token}")
if "VisualIdentityStore.widgetLogo(context)" not in widget_renderer_text:
    errors.append("widget não acompanha a identidade discreta")
for source in (capture_service_text, optimization_service_text, screen_capture_text, import_service_text):
    if "setSmallIcon(R.drawable.ic_stat_vault)" in source:
        errors.append("notificação de serviço ainda ignora a identidade discreta")
for required_token in ("RESOLUTION_8K", "EIGHT_K_SIZE = Size(7680, 4320)"):
    if required_token not in capture_settings_text:
        errors.append(f"8K dinâmico incompleto em CaptureSettings: {required_token}")
if "CaptureSettings.RESOLUTION_8K to CaptureSettings.EIGHT_K_SIZE" not in capability_matrix_text:
    errors.append("a matriz de hardware não valida 8K antes de expor o modo")
for required in ('put("appVersionCode", appVersionCode(context))', 'root.optLong("appVersionCode", -1L) != appVersionCode(context)', 'fun invalidate(context: Context? = null)'):
    if required not in capability_matrix_text:
        errors.append("cache-first de capacidades incompleto: " + required)
if 'addSmallButton("Reanalisar capacidades do hardware")' not in settings_activity_text:
    errors.append("Ajustes não expõe reanálise manual de hardware")

# 22. Manual capture configuration, capture-priority diagnostics, transactional imports and
# strict browser defaults. Automatic maximum-sustainable calibration is intentionally absent.
health_path = JAVA / "com/steadyvault/camera/core/diagnostics/RecordingQualityAnalyzer.kt"
photo_perf_path = JAVA / "com/steadyvault/camera/core/diagnostics/PhotoPerformanceTracker.kt"
writer_path = JAVA / "com/steadyvault/camera/storage/vault/PrivateMediaFileWriter.kt"
browser_store_path = JAVA / "com/steadyvault/camera/ui/browser/PrivateBrowserStore.kt"
browser_activity_path = JAVA / "com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt"
browser_activity_text = browser_activity_path.read_text(errors="ignore") if browser_activity_path.exists() else ""
for required_path, description in (
    (photo_perf_path, "telemetria leve da foto"),
    (writer_path, "publicação transacional da importação"),
):
    if not required_path.exists():
        errors.append(f"recurso V220 ausente: {description}")
for retired_path, description in (
    (JAVA / "com/steadyvault/camera/core/capability/DeviceAutoConfigurator.kt", "calibrador automático"),
    (JAVA / "com/steadyvault/camera/core/capability/DeviceCalibrationStore.kt", "store de calibração automática"),
    (JAVA / "com/steadyvault/camera/core/capability/AutoConfigurationPolicy.kt", "política de máximo sustentável"),
):
    if retired_path.exists():
        errors.append(f"configuração manual ainda contém código aposentado: {description}")
for retired_token in (
    "DeviceAutoConfigurator",
    "DeviceCalibrationStore",
    "Calibrar e aplicar máximo sustentável",
    "Ver máximo sustentável",
    "showMaximumHardwareRecommendation",
):
    if retired_token in settings_activity_text:
        errors.append(f"Ajustes ainda contém configuração automática aposentada: {retired_token}")

if health_path.exists():
    errors.append("analisador automático pós-gravação ainda existe no código de produção")
if capture_service_text.count("stopCurrentRecording()") != 3:
    errors.append("CaptureService ganhou um novo caminho inesperado de parada; revisar corte prematuro")

# A complete Gradle Wrapper is part of a buildable source package.
wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if not wrapper_jar.is_file() or wrapper_jar.stat().st_size < 10_000:
    errors.append("gradle-wrapper.jar ausente ou inválido; gradlew não consegue iniciar")

# A val/var whose identifier occurs only once across all project Kotlin sources is definitely unused.
# Counting globally avoids false positives for properties/constants consumed from another file.
project_kotlin_paths = [path for path in ROOT.rglob("*.kt") if is_project_file(path)]
project_kotlin_texts = {path: path.read_text(errors="ignore") for path in project_kotlin_paths}
all_kotlin_tokens = Counter(
    token
    for text in project_kotlin_texts.values()
    for token in re.findall(r"\b[A-Za-z_]\w*\b", text)
)
for path, text in project_kotlin_texts.items():
    for match in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\b", text):
        symbol = match.group(1)
        if symbol != "_" and all_kotlin_tokens[symbol] == 1:
            errors.append(f"variável sem uso em {rel(path)}: {symbol}")

# Keep the three storage areas explicitly independent while centralizing public export behavior.
for repository_file in (
    JAVA / "com/steadyvault/camera/storage/vault/VaultRepository.kt",
    JAVA / "com/steadyvault/camera/storage/vault/SecondaryVaultRepository.kt",
    JAVA / "com/steadyvault/camera/storage/vault/TertiaryVaultRepository.kt",
):
    if not repository_file.exists():
        errors.append(f"área de cofre independente ausente: {repository_file.name}")
private_core_text = (JAVA / "com/steadyvault/camera/storage/vault/PrivateVaultRepositoryCore.kt").read_text(errors="ignore")
if "PublicMediaExporter.export(" not in (JAVA / "com/steadyvault/camera/storage/vault/VaultRepository.kt").read_text(errors="ignore"):
    errors.append("exportação pública não está componentizada em VaultRepository.kt")
if "PublicMediaExporter.export(" not in private_core_text:
    errors.append("exportação pública não está componentizada em PrivateVaultRepositoryCore.kt")
for repository_name in ("SecondaryVaultRepository.kt", "TertiaryVaultRepository.kt"):
    repository_text = (JAVA / f"com/steadyvault/camera/storage/vault/{repository_name}").read_text(errors="ignore")
    if "PrivateVaultRepositoryCore.exportToGallery" not in repository_text:
        errors.append(f"exportação pública não delega ao núcleo compartilhado em {repository_name}")

print(f"XML verificados: {len(list(ROOT.rglob('*.xml')))}")
print(f"Kotlin verificados: {len(list(JAVA.rglob('*.kt')))}")
print(f"Referências locais verificadas: {len(local_refs)}")
print(f"Componentes encontrados: {len(classes)}")
for warning in warnings:
    print(f"AVISO: {warning}")
# Regression checks for compile-time misses found by a real Android build.
app_log_text = (JAVA / "com/steadyvault/camera/core/diagnostics/AppLogRepository.kt").read_text(errors="ignore")
photo_service_text = (JAVA / "com/steadyvault/camera/photo/service/PhotoService.kt").read_text(errors="ignore")
widget_renderer_text = (JAVA / "com/steadyvault/camera/widgets/WidgetRenderer.kt").read_text(errors="ignore")
if "bytes.indexOf('\\n'.code.toByte(), start)" in app_log_text:
    errors.append("AppLogRepository ainda usa ByteArray.indexOf com startIndex inexistente")
if "fastPreviewCapture()" in photo_service_text:
    errors.append("PhotoService ainda referencia fastPreviewCapture inexistente")
indicator_match = re.search(r"private fun configureRecordingIndicator\((.*?)\n    \)", widget_renderer_text, re.DOTALL)
if indicator_match and "context: Context" not in indicator_match.group(1):
    errors.append("configureRecordingIndicator usa contexto sem recebê-lo")
if "setPhysicalCameraKey(" in capture_service_text or "physicalCameraResults" in capture_service_text:
    errors.append("CaptureService ainda depende de override/resultado físico no request lógico")

# V175: a matriz atual da câmera deve vencer histórico antigo e o Samsung não pode
# receber IDs físicos em requests lógicos. 4K/60 deve considerar PRIVATE + encoder
# e, no Android 15+, confirmar a sessão em runtime antes de ocultar o modo.
mode_catalog_text = (JAVA / "com/steadyvault/camera/core/capability/CaptureModeCatalog.kt").read_text(errors="ignore")
capability_matrix_text = (JAVA / "com/steadyvault/camera/core/capability/CaptureCapabilityMatrix.kt").read_text(errors="ignore")
ois_capability_text = (JAVA / "com/steadyvault/camera/core/camera/OpticalStabilizationCapability.kt").read_text(errors="ignore")
for forbidden in ("setPhysicalCameraKey(", "getPhysicalCameraKey(", "setPhysicalCameraId(", "physicalCameraResults"):
    for path in (JAVA / "com/steadyvault/camera").rglob("*.kt"):
        if forbidden in path.read_text(errors="ignore"):
            errors.append(f"request físico inseguro ainda presente em {rel(path)}: {forbidden}")
for required in (
    "getOutputSizes(ImageFormat.PRIVATE)",
    "getOutputSizes(MediaCodec::class.java)",
    "getOutputSizes(MediaRecorder::class.java)",
    "queryRegularSessionSupport",
    "isSessionConfigurationSupported",
    "CameraDevice.TEMPLATE_RECORD",
):
    if required not in capability_matrix_text:
        errors.append(f"matriz de vídeo não valida todas as fontes necessárias: {required}")
for required in (
    "ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }",
    "StrictCaptureModePolicy.requiresExactFpsRange(targetFps)",
    "StrictCaptureModePolicy.acceptsFpsRange(targetFps, it.lower, it.upper)",
    "CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, profile.fpsRange",
    "selectCaptureConfigurationWithFpsFallback",
    "recording_fps",
    "val exactRateSupported = runCatching",
    "CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL",
    "listOf(CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)",
):
    if required not in capture_service_text:
        errors.append(f"seleção de faixa fixa de 60 FPS incompleta: {required}")
if "armRecorderForFirstFrame(token)" not in capture_service_text or "commitRecorderStart(profile, token" not in capture_service_text:
    errors.append("arm/commit direto do HardwareRecorder ausente")
for required in ("professionalRecorder?.arm()", "professionalRecorder?.commitStart()"):
    if required not in capture_service_text:
        errors.append(f"protocolo de duas fases do recorder incompleto: {required}")
for forbidden in (
    "FrameCadenceMonitor",
    "rejectUnstableCadence",
    "cadência inicial",
    "não assentou em",
):
    if forbidden in capture_service_text:
        errors.append(f"gravação voltou a ser bloqueada por cadência inicial: {forbidden}")
for forbidden in (
    "RecordingWarmupPolicy",
    "warmup.minimumFrames",
    "setSingleRepeatingRequest(request, cameraExecutor, callback)",
    "setRepeatingBurstRequests(requests, cameraExecutor, callback)",
):
    if forbidden in capture_service_text:
        errors.append(f"request voltou a usar warm-up/callback no início: {forbidden}")
for required in (
    "session.setRepeatingRequest(request, null, null)",
    "session.setRepeatingBurst(requests, null, null)",
    "startMicrophoneWithoutBlockingVideo(token)",
    "professionalRecorder?.startAudioCapture() == true",
    "professionalRecorder?.continueWithoutAudio()",
    'CAPTURE_PIPELINE_REVISION = "vbr-dedicated-camera-4k60-1.8.243"',
):
    if required not in capture_service_text:
        errors.append(f"início imediato + request congelado incompleto: {required}")
for required in (
    "addTarget(surface)",
    "val outputConfigurations = listOf(outputConfiguration)",
    "Toda gravação usa a mesma sessão encoder-only",
):
    if required not in capture_service_text:
        errors.append(f"contrato encoder-only incompleto: {required}")
if (JAVA / "com/steadyvault/camera/capture/timing/FrameCadenceMonitor.kt").exists():
    errors.append("FrameCadenceMonitor ainda existe apesar de a cadência inicial não poder bloquear a gravação")
router_text = (JAVA / "com/steadyvault/camera/capture/service/RecordingServiceRouter.kt").read_text(errors="ignore")
if "fun startHeadless(" not in router_text:
    errors.append("contrato headless encoder-only incompleto: startHeadless")
if "CameraPreviewRegistry.snapshot()" in capture_service_text or "SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW" in capture_service_text:
    errors.append("gravação voltou a compartilhar Camera2/ISP com uma Surface de preview")
if "fromPreview = false" not in router_text or "headless = true" not in router_text:
    errors.append("startHeadless não força origem sem preview + contrato headless")
discreet_layout_text = (RES / "layout/activity_discreet_capture.xml").read_text(errors="ignore")
for forbidden_view in ("SurfaceView", "TextureView", "PreviewView"):
    if forbidden_view in discreet_layout_text:
        errors.append(f"tela preta voltou a renderizar preview de câmera: {forbidden_view}")

for forbidden in (
    "scheduleDirectRecorderStart(",
    "DIRECT_HEADLESS_SETTLE_MS",
    "DIRECT_HIGH_FPS_SETTLE_MS",
    "DIRECT_STANDARD_SETTLE_MS",
    "submitExposureAdjustedRequest(",
    "adaptBitrateForCadence",
):
    if forbidden in capture_service_text:
        errors.append(f"controle dinâmico voltou ao caminho de gravação: {forbidden}")
for forbidden in ("RESOLUTION_AUTO", "CODEC_AUTO", "STABILIZATION_AUTO"):
    for source_name, source_text in (
        ("CaptureSettings", capture_settings_text),
        ("CaptureService", capture_service_text),
        ("SettingsActivity", settings_activity_text),
        ("CaptureActivity", capture_text),
    ):
        if forbidden in source_text:
            errors.append(f"{source_name} voltou a expor seleção automática: {forbidden}")
for forbidden in ("encoderPerformanceScore", "getAchievableFrameRatesFor", "headroomProbeFps"):
    if forbidden in capture_service_text:
        errors.append("heurística automática de desempenho voltou ao encoder: " + forbidden)
if "supportedPerformancePoints" not in capture_service_text or "PerformancePoint(size.width, size.height, fps)" not in capture_service_text:
    errors.append("seleção do encoder perdeu a garantia oficial de PerformancePoint para 4K60/HFR")
thumbnail_repo_text = (JAVA / "com/steadyvault/camera/storage/vault/MediaThumbnailRepository.kt").read_text(errors="ignore")
vault_repo_text = (JAVA / "com/steadyvault/camera/storage/vault/VaultRepository.kt").read_text(errors="ignore")
import_post_text = (JAVA / "com/steadyvault/camera/ui/vault/VaultImportPostProcessor.kt").read_text(errors="ignore")
for source_name, source_text in (("thumbnails", thumbnail_repo_text), ("metadados", vault_repo_text), ("pós-importação", import_post_text)):
    if "VaultStartupCoordinator.isCapturePriorityActive" not in source_text:
        errors.append(f"{source_name} ainda pode disputar decoder/I/O com a gravação")
if "private val pending = ArrayDeque<PendingSample>()" in hardware_recorder_text:
    errors.append("MuxerCoordinator voltou a manter um segundo buffer de samples")
if "primeAudioTrack" in hardware_recorder_text or "AAC_PRIME_MAX_BUFFERS" in hardware_recorder_text:
    errors.append("priming artificial de silêncio AAC voltou ao início da gravação")
if "MAX_STARTUP_BUFFER_BYTES = 16L * 1024L * 1024L" not in hardware_recorder_text:
    errors.append("buffer único de startup não está limitado a 16 MB")
if "SERVICE_HEARTBEAT_INTERVAL_MS = 15_000L" not in capture_service_text:
    errors.append("heartbeat voltou a gravar SharedPreferences com frequência excessiva")
if "RECORDING_HEALTH_CHECK_INTERVAL_MS = 200L" not in capture_service_text:
    errors.append("watchdog de cadência não está na janela rápida de 200 ms")
if "preparePreviewRecordingTransition { beginRecordingFlow() }" in capture_text:
    errors.append("PixelCopy voltou ao caminho de início da gravação pelo preview")
frozen_request_pos = capture_service_text.find("session.setRepeatingRequest(request, null, null)")
recorder_arm_pos = capture_service_text.find("armRecorderForFirstFrame(token)", frozen_request_pos - 500)
recorder_commit_pos = capture_service_text.find("commitRecorderStart(profile, token, highSpeed = false)")
if frozen_request_pos < 0 or recorder_arm_pos < 0 or recorder_commit_pos < 0 or not (recorder_arm_pos < frozen_request_pos < recorder_commit_pos):
    errors.append("recorder precisa ser armado antes do único repeating e confirmado depois")
if "setSingleRepeatingRequest(request, cameraExecutor, callback)" in capture_service_text:
    errors.append("callback Camera2 voltou ao caminho crítico de gravação")
if "Cadência inicial" in capture_service_text:
    errors.append("mensagem de cadência usa capitalização inesperada e pode escapar da auditoria")
if "RecordingQualityAnalyzer.schedule" in capture_service_text:
    errors.append("CaptureService voltou a disparar análise automática após salvar o MP4")
hardware_recorder_text = (JAVA / "com/steadyvault/camera/capture/recorder/HardwareRecorder.kt").read_text(errors="ignore")
timestamp_normalizer_text = (JAVA / "com/steadyvault/camera/capture/timing/VideoTimestampNormalizer.kt").read_text(errors="ignore")
startup_gate_path = JAVA / "com/steadyvault/camera/capture/recorder/StartupVideoGate.kt"
startup_gate_text = startup_gate_path.read_text(errors="ignore") if startup_gate_path.exists() else ""
if "VideoTimestampNormalizer(videoConfig.fps)" not in hardware_recorder_text:
    errors.append("normalizador monotônico de PTS foi removido do gravador")
for forbidden in ("clockSlot", "outputFrameIndex", "firstRawPtsUs"):
    if forbidden in timestamp_normalizer_text:
        errors.append(f"normalizador voltou a encaixar PTS na grade nominal: {forbidden}")
for required in (
    "sourcePtsUs > lastOutputPtsUs",
    "lastOutputPtsUs + MINIMUM_PTS_ADVANCE_US",
    "const val MINIMUM_PTS_ADVANCE_US = 1L",
):
    if required not in timestamp_normalizer_text:
        errors.append(f"preservação monotônica do PTS real incompleta: {required}")
for required in (
    "fun arm()",
    "fun commitStart()",
    "StartupVideoGate(",
    "isClearlyBeforeRecordingCommit",
):
    if required not in hardware_recorder_text:
        errors.append(f"gate do primeiro IDR real incompleto no recorder: {required}")
for required in ("START_WITH_CURRENT_KEY_FRAME", "START_WITH_BUFFERED_GOP", "requestSyncFrame"):
    if required not in startup_gate_text:
        errors.append(f"fallback não bloqueante do primeiro IDR incompleto: {required}")
for forbidden in ("buffer.get(", "averageluminance", "isblackframe", "sample.size <"):
    if forbidden in startup_gate_text.lower():
        errors.append(f"gate de início voltou a classificar conteúdo da cena: {forbidden}")
if "format.setInteger(MediaFormat.KEY_FRAME_RATE, nominalVideoFps)" not in hardware_recorder_text:
    errors.append("metadata nominal de FPS do track não é corrigido antes do muxer")
if "if (videoConfig.hdrHlg10 && videoConfig.profile != null && videoConfig.level != null)" not in hardware_recorder_text:
    errors.append("SDR voltou a forçar profile/level do encoder em vez de usar o default do hardware")
if "promoteForegroundForMicrophoneWithRetry" not in capture_service_text:
    errors.append("promoção tardia do FGS de microfone foi removida")
required_permission_match = re.search(
    r"private fun hasRequiredPermissions\(\): Boolean\s*=\s*(.*?)(?=\n\s*private fun)",
    capture_service_text,
    re.DOTALL,
)
if not required_permission_match or "Manifest.permission.RECORD_AUDIO" in required_permission_match.group(1):
    errors.append("serviço voltou a tornar o microfone fatal para o vídeo")
capture_activity_text = (JAVA / "com/steadyvault/camera/ui/capture/CaptureActivity.kt").read_text(errors="ignore")
missing_permission_match = re.search(
    r"private fun missingRecordingPermissions\(\): Array<String> \{(.*?)(?=\n\s*private fun)",
    capture_activity_text,
    re.DOTALL,
)
if not missing_permission_match or "Manifest.permission.RECORD_AUDIO" in missing_permission_match.group(1):
    errors.append("Activity voltou a bloquear o comando de vídeo pelo microfone")
for retired_control in (
    "smoothHighFps",
    "bitrateMode",
    "bFrames",
    "useOperatingRate",
    "gapCorrection",
    "fixedFps",
    "streamUseCaseEnabled",
    "optimizeAfterRecording",
    "optimizationPreset",
    "optimizationFrameRepair",
    "optimizationCodec",
    "optimizationRateMode",
    "optimizationReplaceOriginal",
    "optimizationAiAssisted",
):
    if retired_control in capture_settings_text or retired_control in settings_activity_text:
        errors.append(f"controle alternativo aposentado voltou ao pipeline: {retired_control}")
if "val startIntent = if (!state.cameraGranted)" not in widget_renderer_text:
    errors.append("widget voltou a exigir áudio para iniciar vídeo")
settings_activity_text = (JAVA / "com/steadyvault/camera/ui/settings/SettingsActivity.kt").read_text(errors="ignore")
if "microphonePermission.launch(Manifest.permission.RECORD_AUDIO)" not in settings_activity_text:
    errors.append("microfone opcional não pode ser liberado pelos Ajustes")
for required in (
    "resolveFirstVideoCaptureNs",
    "AudioTimestamp.TIMEBASE_BOOTTIME",
    "writeVideoEndOfStream",
    "writeAudioEndOfStream",
    "hasWrittenVideoSample()",
    "releasePipelineResourcesWhenQuiescent",
):
    if required not in hardware_recorder_text:
        errors.append(f"pipeline direto 1.8.220 incompleto: {required}")
if mode_catalog_text.find("detected?.let { current ->") > mode_catalog_text.find("validated?.let { return@map it }"):
    errors.append("histórico antigo voltou a vencer a matriz atual e pode esconder 4K60")
if "allowFileAccessFromFileURLs" in browser_activity_text or "allowUniversalAccessFromFileURLs" in browser_activity_text:
    errors.append("WebView ainda usa flags file-URL obsoletas")
if "physicalOisCameraIds" not in ois_capability_text or "builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)" not in ois_capability_text:
    errors.append("OIS perdeu diagnóstico físico ou aplicação lógica segura")

# V198: bitrate escolhido pelo usuário não pode ser reescrito por mudanças vizinhas,
# o fim do vídeo precisa abrir ações imediatamente e a fluidez deve usar teto seguro
# sem reconfigurar o encoder no meio da gravação.
capture_settings_text = (JAVA / "com/steadyvault/camera/core/settings/CaptureSettings.kt").read_text(errors="ignore")
camera_profile_store_text = (JAVA / "com/steadyvault/camera/core/settings/CameraProfileStore.kt").read_text(errors="ignore")
settings_activity_text = (JAVA / "com/steadyvault/camera/ui/settings/SettingsActivity.kt").read_text(errors="ignore")
capture_activity_text = (JAVA / "com/steadyvault/camera/ui/capture/CaptureActivity.kt").read_text(errors="ignore")
media_player_text = (JAVA / "com/steadyvault/camera/ui/vault/MediaPlayerActivity.kt").read_text(errors="ignore")
hardware_recorder_text = (JAVA / "com/steadyvault/camera/capture/recorder/HardwareRecorder.kt").read_text(errors="ignore")
if re.search(r"fun updateResolutionAndFps\(.*?bitrateMbps\s*=\s*defaultBitrateMbps", capture_settings_text, re.DOTALL):
    errors.append("updateResolutionAndFps voltou a apagar o bitrate manual")
for forbidden in (
    "settings.copy(resolution = value, bitrateMbps = bitrate)",
    "settings.copy(codec = codec, bitrateMbps = bitrate)",
    "showLiveBitrateInput()",
):
    if forbidden in capture_activity_text:
        errors.append("ajuste rápido voltou a perder bitrate ou usar campo de texto: " + forbidden)
if "stored.copy(bitrateMbps = fallback.bitrateMbps)" not in camera_profile_store_text:
    errors.append("troca de perfil voltou a restaurar bitrate antigo")
if "cadenceSafeVideoBitrate" in capture_service_text:
    errors.append("bitrate manual voltou a ser reduzido por teto automático de cadência")
if "val bitrate = desiredBitrate.coerceIn(videoCapabilities.bitrateRange.lower, videoCapabilities.bitrateRange.upper)" not in capture_service_text:
    errors.append("bitrate manual não está sendo enviado diretamente ao intervalo do encoder")
if "* 1.08" in capture_service_text or "desiredVideoBitrate(" in capture_service_text:
    errors.append("bitrate manual ainda recebe ajuste automático por HDR/codec")
if "private fun configuredVideoBitrate()" not in capture_service_text:
    errors.append("bitrate manual perdeu a origem única configurada pelo usuário")
if "adaptBitrateForCadence" in hardware_recorder_text or "PARAMETER_KEY_VIDEO_BITRATE" in hardware_recorder_text:
    errors.append("o encoder voltou a mudar bitrate durante a gravação")
if "StabilizationPerformanceStore.record" in capture_service_text:
    errors.append("aprendizado de estabilização voltou a registrar gravações concluídas")
if "showPlaybackEndMenu()" in media_player_text or "prefetchNextVideo(media)" in media_player_text:
    errors.append("player voltou a abrir popup/pré-carregar próximo vídeo no fim")
if "setOnCompletionListener" not in media_player_text or "playbackCompleted = true" not in media_player_text or "R.drawable.ic_refresh" not in media_player_text:
    errors.append("reprodução novamente inline no player está incompleta")
if "videoCenterControls" not in media_player_text or "videoView.setOnSingleTapListener { toggleViewerControls() }" not in media_player_text:
    errors.append("controles centrais estilo player não estão ligados ao toque no vídeo")

# V207: o estado exibido precisa ser o perfil realmente ativo, a tela preta deve
# respeitar a origem correta e textos longos não podem voltar a ser truncados.
one_ui_dialog_text = (JAVA / "com/steadyvault/camera/ui/components/OneUiDialog.kt").read_text(errors="ignore")
one_ui_spinner_text = (JAVA / "com/steadyvault/camera/ui/components/OneUiSpinner.kt").read_text(errors="ignore")
if "CameraProfileStore.activate(this, selectedId, CameraProfileStore.FunctionMode.VIDEO, normalized)" not in capture_activity_text:
    errors.append("tela Gravar voltou a exibir perfil sem ativá-lo na inicialização")
if "val activeSnapshot = current.selectedCameraId" not in settings_activity_text or "CameraProfileStore.activate(this, cameraId, requestedProfileMode, current)" not in settings_activity_text:
    errors.append("Ajustes voltou a montar controles antes de ativar o perfil persistido")
if "switchVideoFpsProfile(targetFps)" not in settings_activity_text or "CameraProfileStore.saveProfile(this, cameraId, CameraProfileStore.FunctionMode.VIDEO, previousForm)" not in settings_activity_text:
    errors.append("troca de FPS nos Ajustes voltou a misturar perfis")
main_record_pattern = re.compile(r"startButton\.setOnClickListener.*?recordingRequestedFromQuickShortcut = false.*?beginRecordingFlow\(\)", re.DOTALL)
shortcut_record_pattern = re.compile(r"consumeLauncherVideoShortcut.*?recordingRequestedFromQuickShortcut = true.*?beginRecordingFlow\(\)", re.DOTALL)
if not main_record_pattern.search(capture_activity_text):
    errors.append("botão Gravar voltou a usar preferência de tela preta do atalho rápido")
if not shortcut_record_pattern.search(capture_activity_text):
    errors.append("atalho rápido deixou de usar sua preferência própria de tela preta")
if "if (started && !keepPreviewControls && pendingBlackScreenForRecording)" not in capture_activity_text:
    errors.append("tela preta voltou a depender somente de broadcast tardio")
if re.search(r"choice\.subtitle.*?maxLines\s*=\s*3", one_ui_dialog_text, re.DOTALL):
    errors.append("descrições dos seletores voltaram a ser cortadas em três linhas")
if "position != choiceAdapter.selectedPosition" not in one_ui_spinner_text:
    errors.append("Aplicar sem mudança voltou a disparar alteração de spinner")
for forbidden in ("cadenceRawPtsUs", "cadenceRawPtsCount", "cadenceLock", "cadenceSummary()", "RawCadenceAnalyzer"):
    if forbidden in hardware_recorder_text:
        errors.append("telemetria de cadência por frame voltou ao HardwareRecorder: " + forbidden)
if "refreshCompatibilityOptions(true)" in settings_activity_text:
    errors.append("Ajustes voltou a trocar silenciosamente FPS/resolução durante atualização de capacidades")
if (JAVA / "com/steadyvault/camera/core/camera/StabilizationPerformanceStore.kt").exists():
    errors.append("store de aprendizado de estabilização ainda existe no código de produção")
section_order = [
    'addSection("Vídeo")',
    'addSection("Câmera e estabilização")',
    'addSection("Gravação discreta")',
    'addSection("Áudio")',
    'addSection("Processamento de vídeo")',
    'addSection("Reprodução")',
    'addSection("Privacidade e cofres")',
    'addSection("Apps protegidos")',
    'addSection("Execução em segundo plano")',
    'addSection("Cache e desempenho")',
    'addSection("Navegador e downloads")',
    'addSection("Aparência")',
    'addLauncherShortcutSettings()',
    'addSection("Diagnóstico")',
]
positions = [settings_activity_text.find(token) for token in section_order]
if any(position < 0 for position in positions) or positions != sorted(positions):
    errors.append("ordem lógica dos Ajustes premium foi quebrada")

if errors:
    for error in errors:
        print(f"ERRO: {error}")
    print(f"STATIC_AUDIT_FAILED ({len(errors)} erro(s))")
    sys.exit(1)
print("STATIC_AUDIT_OK")
