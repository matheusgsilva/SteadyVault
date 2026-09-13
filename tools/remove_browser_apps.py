from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def path(rel): return ROOT / rel
def read(rel): return path(rel).read_text(encoding="utf-8")
def write(rel, text): path(rel).write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1: raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)

def sub_once(text, pattern, repl, label, flags=re.S):
    text2, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1: raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text2

def delete_files():
    files = [
        "app/src/main/java/com/steadyvault/camera/ui/apps/ProtectedAppsActivity.kt",
        "app/src/main/java/com/steadyvault/camera/ui/apps/ProtectedAppsPackageReceiver.kt",
        "app/src/main/java/com/steadyvault/camera/ui/apps/VaultScreenCaptureService.kt",
        "app/src/main/java/com/steadyvault/camera/storage/security/AppVaultLock.kt",
        "app/src/main/java/com/steadyvault/camera/storage/security/ProtectedAppsIndexStore.kt",
        "app/src/main/java/com/steadyvault/camera/storage/security/ProtectedAppsStore.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserBlocker.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserDownloadRegistry.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserMediaExtractor.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserMediaThumbnailLoader.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserSharedLinkParser.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/BrowserWebViewConfigurator.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/InstagramPublicAccess.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/InstagramSessionMediaResolver.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserStore.kt",
        "app/src/main/java/com/steadyvault/camera/ui/browser/SocialMediaDownloader.kt",
        "app/src/main/res/layout/activity_private_browser.xml",
        "app/src/main/res/layout/activity_protected_apps.xml",
        "app/src/main/res/layout/item_protected_app.xml",
        "app/src/main/res/drawable/ic_nav_browser.xml",
        "app/src/main/res/drawable/ic_nav_apps.xml",
    ]
    for rel in files:
        p = path(rel)
        if not p.exists(): raise RuntimeError(f"arquivo esperado não existe: {rel}")
        p.unlink()

def patch_settings():
    rel = "app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt"
    s = read(rel)
    for line in [
        "import android.app.Activity\n",
        "import android.media.projection.MediaProjectionManager\n",
        "import android.net.Uri\n",
        "import android.os.Build\n",
        "import android.provider.Settings\n",
        "import com.steadyvault.camera.storage.security.AppVaultLock\n",
        "import com.steadyvault.camera.storage.security.ProtectedAppsStore\n",
        "import com.steadyvault.camera.ui.browser.PrivateBrowserStore\n",
        "import com.steadyvault.camera.ui.apps.ProtectedAppsActivity\n",
        "import com.steadyvault.camera.ui.apps.VaultScreenCaptureService\n",
    ]: s = replace_once(s, line, "", f"Settings import {line.strip()}")
    s = sub_once(s, r"\n    private var protectedCaptureFlowPending = false\n    private var pendingProtectedCaptureDestination =\n        VaultScreenCaptureService\.DESTINATION_PRIMARY\n", "\n", "Settings campos captura protegida")
    s = sub_once(s, r"\n    private val protectedProjectionPermission =.*?(?=\n    private val microphonePermission =)", "\n", "Settings launchers captura protegida")
    s = replace_once(s, "        if (protectedCaptureFlowPending) cancelProtectedCaptureFlow()\n", "", "Settings onDestroy captura protegida")
    s = replace_once(s, "Restaura resolução, FPS, codec e controles da câmera. Cofres, PINs, mídias, navegador e apps protegidos permanecem intactos.", "Restaura resolução, FPS, codec e controles da câmera. Cofres, PINs e mídias permanecem intactos.", "Settings texto recuperação")
    s = sub_once(s, r"\n        addSection\(\"Apps protegidos\"\).*?(?=\n        addSection\(\"Execução em segundo plano\"\))", "\n", "Settings seção apps protegidos")
    s = replace_once(s, "A limpeza não apaga fotos, vídeos, lixeira, álbuns, favoritos do navegador nem configurações da câmera. Miniaturas e detalhes necessários são recriados automaticamente.", "A limpeza não apaga fotos, vídeos, lixeira, álbuns nem configurações da câmera. Miniaturas e detalhes necessários são recriados automaticamente.", "Settings texto cache")
    s = sub_once(s, r"\n        addSection\(\"Navegador e downloads\"\).*?(?=\n        addSection\(\"Aparência\"\))", "\n", "Settings seção navegador")
    s = sub_once(s, r"\n    private fun chooseBrowserDownloadDestination\(\).*?(?=\n    private fun rebuildSettingsForm\(\))", "\n", "Settings métodos navegador")
    s = sub_once(s, r"\n    private fun manageAppsPin\(\).*?(?=\n    private fun verifyPin\()", "\n", "Settings métodos apps/captura")
    forbidden = ["PrivateBrowserStore", "ProtectedAppsActivity", "ProtectedAppsStore", "AppVaultLock", "VaultScreenCaptureService", "protectedCapture", "chooseBrowser", "Apps protegidos", "Navegador e downloads", "MediaProjectionManager"]
    leftovers = [token for token in forbidden if token in s]
    if leftovers: raise RuntimeError(f"Settings ainda contém referências removidas: {leftovers}")
    write(rel, s)

def patch_bottom_navigation():
    rel = "app/src/main/java/com/steadyvault/camera/ui/navigation/BottomNavigation.kt"
    s = read(rel)
    s = replace_once(s, "import com.steadyvault.camera.ui.apps.ProtectedAppsActivity\n", "", "BottomNavigation import apps")
    s = replace_once(s, "import com.steadyvault.camera.ui.browser.PrivateBrowserActivity\n", "", "BottomNavigation import browser")
    s = replace_once(s, "    const val TAB_BROWSER = 2\n    const val TAB_APPS = 3\n    const val TAB_SETTINGS = 4\n", "    const val TAB_SETTINGS = 2\n", "BottomNavigation tabs")
    s = replace_once(s, "        val browser = activity.findViewById<TextView>(R.id.navBrowser) ?: return\n", "", "BottomNavigation browser view")
    s = replace_once(s, "        val apps = activity.findViewById<TextView>(R.id.navApps) ?: return\n", "", "BottomNavigation apps view")
    s = replace_once(s, "            Triple(browser, R.drawable.ic_nav_browser, currentTab == TAB_BROWSER),\n", "", "BottomNavigation browser item")
    s = replace_once(s, "            Triple(apps, R.drawable.ic_nav_apps, currentTab == TAB_APPS),\n", "", "BottomNavigation apps item")
    s = sub_once(s, r"\n        browser\.setOnClickListener \{.*?\n        \}\n        apps\.setOnClickListener \{.*?\n        \}\n", "\n", "BottomNavigation listeners")
    write(rel, s)

def patch_manifest():
    rel = "app/src/main/AndroidManifest.xml"
    s = read(rel)
    s = sub_once(s, r"\n    <queries>.*?</queries>\n", "\n", "Manifest queries")
    for permission in [
        "    <uses-permission android:name=\"android.permission.INTERNET\" />\n",
        "    <uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />\n",
        "    <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION\" />\n",
        "    <uses-permission android:name=\"android.permission.SYSTEM_ALERT_WINDOW\" />\n",
    ]: s = replace_once(s, permission, "", f"Manifest {permission.strip()}")
    s = sub_once(s, r"\n        <activity\n            android:name=\"\.ui\.browser\.PrivateBrowserActivity\".*?</activity>\n", "\n", "Manifest browser activity")
    s = sub_once(s, r"\n        <activity android:name=\"\.ui\.apps\.ProtectedAppsActivity\"[^\n]*/>\n", "\n", "Manifest apps activity")
    s = sub_once(s, r"\n        <service\n            android:name=\"\.ui\.apps\.VaultScreenCaptureService\".*?/>\n", "\n", "Manifest projection service")
    s = sub_once(s, r"\n        <receiver android:name=\"\.ui\.apps\.ProtectedAppsPackageReceiver\".*?</receiver>\n", "\n", "Manifest apps receiver")
    write(rel, s)

def patch_nav_layout_and_strings():
    rel = "app/src/main/res/layout/view_bottom_navigation.xml"
    s = read(rel)
    for view_id in ("navBrowser", "navApps"):
        s = sub_once(s, rf"\n    <TextView\n        android:id=\"@\+id/{view_id}\".*? />\n", "\n", f"nav layout {view_id}")
    write(rel, s)
    rel = "app/src/main/res/values/strings.xml"
    s = read(rel)
    s = replace_once(s, "    <string name=\"share_link_browser_label\">SteadyVault — abrir link</string>\n", "", "browser string")
    write(rel, s)

def patch_application():
    rel = "app/src/main/java/com/steadyvault/camera/SteadyVaultApplication.kt"
    s = read(rel)
    for line in ["import android.os.Process\n", "import com.yausername.aria2c.Aria2c\n", "import com.yausername.ffmpeg.FFmpeg\n", "import com.yausername.youtubedl_android.YoutubeDL\n", "import java.util.concurrent.Executors\n", "import java.util.concurrent.Future\n"]:
        s = replace_once(s, line, "", f"Application import {line.strip()}")
    s = sub_once(s, r"\n    companion object \{.*?\n    \}\n\}\s*$", '\n    companion object {\n        private const val TAG = "SteadyVaultApplication"\n    }\n}\n', "Application media engine")
    write(rel, s)

def patch_gradle():
    rel = "app/build.gradle.kts"
    s = read(rel)
    for line in [
        '                "**/libaria2c.so",\n', '                "**/libaria2c.zip.so",\n',
        '                "**/libffmpeg.so",\n', '                "**/libffmpeg.zip.so",\n',
        '                "**/libffprobe.so",\n', '                "**/libpython.so",\n',
        '                "**/libpython.zip.so",\n', '                "**/libqjs.so"\n',
        '    implementation("androidx.webkit:webkit:1.16.0")\n',
        '    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")\n',
        '    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")\n',
        '    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")\n',
    ]: s = replace_once(s, line, "", f"Gradle {line.strip()}")
    write(rel, s)

def patch_cleanup_repository():
    rel = "app/src/main/java/com/steadyvault/camera/storage/vault/VaultCleanupRepository.kt"
    s = read(rel)
    s = replace_once(s, "import android.os.Environment\n", "", "VaultCleanup Environment")
    s = replace_once(s, "        cleanStaleTemporaryRoots(app, cutoff)\n", "", "VaultCleanup stale browser roots call")
    s = sub_once(s, r"    private fun runtimeRoots\(context: Context\): List<File> \{.*?\n    \}\n\n    private fun cleanStaleTemporaryRoots\(context: Context, cutoff: Long\): Int \{.*?\n    \}\n", "    private fun runtimeRoots(context: Context): List<File> =\n        listOfNotNull(context.cacheDir, context.externalCacheDir)\n            .distinctBy(::normalizedPath)\n\n", "VaultCleanup browser runtime roots")
    s = replace_once(s, '    private const val SOCIAL_DOWNLOADS_DIRECTORY = "SocialDownloads"\n', "", "VaultCleanup social dir")
    s = replace_once(s, '    private const val COOKIE_FILE_PREFIX = "sv_cookies_"\n', "", "VaultCleanup cookie prefix")
    s = replace_once(s, '    private val TEMPORARY_MEDIA_SUFFIXES = setOf(".download", ".part", ".ytdl")\n', '    private val TEMPORARY_MEDIA_SUFFIXES = setOf(".download", ".part")\n', "VaultCleanup ytdl suffix")
    write(rel, s)

def patch_storage_catalog():
    rel = "app/src/main/java/com/steadyvault/camera/storage/vault/AppStorageCatalog.kt"
    s = read(rel)
    s = replace_once(s, "import android.os.Environment\n", "", "AppStorage Environment")
    s = replace_once(s, "import com.steadyvault.camera.ui.browser.BrowserDownloadRegistry\n", "", "AppStorage browser import")
    for line in [
        '        val browserData = File(app.applicationInfo.dataDir, "app_webview")\n',
        '        val browserTemp = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir, "SocialDownloads")\n',
        '        val publicDownloads = BrowserDownloadRegistry.entries(app)\n',
    ]: s = replace_once(s, line, "", f"AppStorage snapshot {line.strip()}")
    s = replace_once(s, "        val otherExternal = externalRoot?.let { metricsExcluding(it, listOf(browserTemp)) } ?: (0 to 0L)\n", "        val otherExternal = externalRoot?.let(::metrics) ?: (0 to 0L)\n", "AppStorage external metrics")
    for line in [
        '                category(ID_BROWSER_DATA, "Dados privados do navegador", "Cookies, armazenamento de sites e dados do WebView; a limpeza usa a API oficial do WebView", browserData),\n',
        '                category(ID_BROWSER_TEMP, "Temporários de downloads", "Arquivos de trabalho do downloader avançado", browserTemp),\n',
        '                Category(ID_PUBLIC_DOWNLOADS, "Downloads públicos do navegador", "Downloads iniciados pelo SteadyVault e registrados no DownloadManager", publicDownloads.size, 0L),\n',
    ]: s = replace_once(s, line, "", f"AppStorage category {line.strip()}")
    s = replace_once(s, '            ID_BROWSER_TEMP -> clearDirectory(File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir, "SocialDownloads"))\n', "", "AppStorage clear browser temp")
    s = replace_once(s, '            ID_PUBLIC_DOWNLOADS -> { BrowserDownloadRegistry.removeAll(app); true }\n', "", "AppStorage clear public downloads")
    s = sub_once(s, r"    private fun clearOtherExternal\(context: Context\): Boolean \{\n        val root = context\.getExternalFilesDir\(null\) \?: return true\n        val browserTemp = .*?\n        return deleteExcluding\(root, setOf\(canonicalPath\(browserTemp\)\)\)\n    \}", "    private fun clearOtherExternal(context: Context): Boolean {\n        val root = context.getExternalFilesDir(null) ?: return true\n        return deleteExcluding(root, emptySet())\n    }", "AppStorage clear other external")
    for line in ['    const val ID_BROWSER_DATA = "browser_data"\n', '    const val ID_BROWSER_TEMP = "browser_temp"\n', '    const val ID_PUBLIC_DOWNLOADS = "public_downloads"\n']:
        s = replace_once(s, line, "", f"AppStorage const {line.strip()}")
    write(rel, s)

def patch_storage_management():
    rel = "app/src/main/java/com/steadyvault/camera/ui/settings/StorageManagementActivity.kt"
    s = read(rel)
    s = replace_once(s, "import android.webkit.WebView\n", "", "StorageManagement WebView")
    s = replace_once(s, "import com.steadyvault.camera.ui.browser.BrowserWebViewConfigurator\n", "", "StorageManagement browser configurator")
    s = sub_once(s, r"    private fun confirmClear\(category: AppStorageCatalog\.Category\) \{.*?\n    \}\n\n    private fun clearBrowserData\(\) \{.*?\n    \}\n", '''    private fun confirmClear(category: AppStorageCatalog.Category) {\n        OneUiDialog.confirm(this, "Apagar ${category.label}?", "Serão removidos os dados desta área. Esta ação não pode ser desfeita.", "Apagar", destructive = true) {\n            worker.execute {\n                val ok = AppStorageCatalog.clear(this, category.id)\n                runOnUiThread { Toast.makeText(this, if (ok) "Área limpa" else "Não foi possível limpar tudo", Toast.LENGTH_SHORT).show(); refresh() }\n            }\n        }\n    }\n''', "StorageManagement browser clear")
    write(rel, s)

def verify_source():
    forbidden = [
        "com.steadyvault.camera.ui.browser", "com.steadyvault.camera.ui.apps", "PrivateBrowser",
        "BrowserDownloadRegistry", "BrowserWebViewConfigurator", "ProtectedApps", "AppVaultLock",
        "VaultScreenCaptureService", "navBrowser", "navApps", "ic_nav_browser", "ic_nav_apps",
        "activity_private_browser", "activity_protected_apps", "item_protected_app",
        "share_link_browser_label", "SocialDownloads", "sv_cookies_", ".ytdl", "youtubedl-android",
        "androidx.webkit:webkit", "FOREGROUND_SERVICE_MEDIA_PROJECTION", "SYSTEM_ALERT_WINDOW",
    ]
    offenders = []
    for root in [path("app/src"), path("app/build.gradle.kts")]:
        files = [root] if root.is_file() else [p for p in root.rglob("*") if p.is_file()]
        for file in files:
            try: text = file.read_text(encoding="utf-8")
            except UnicodeDecodeError: continue
            hits = [token for token in forbidden if token in text]
            if hits: offenders.append((str(file.relative_to(ROOT)), hits))
    if offenders: raise RuntimeError("referências removidas ainda presentes: " + repr(offenders))

def main():
    patch_settings()
    patch_bottom_navigation()
    patch_manifest()
    patch_nav_layout_and_strings()
    patch_application()
    patch_gradle()
    patch_cleanup_repository()
    patch_storage_catalog()
    patch_storage_management()
    delete_files()
    verify_source()
    print("browser/apps cleanup applied and statically verified")

if __name__ == "__main__": main()
