from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)


auto_path = Path("app/src/main/java/com/steadyvault/camera/processing/auto/AutoGapRepairService.kt")
auto = auto_path.read_text()

auto = replace_once(
    auto,
    "import android.os.Process\n",
    "import android.os.Process\nimport android.os.SystemClock\n",
    "import SystemClock",
)

auto = replace_once(
    auto,
    '''    private val workerRunning = AtomicBoolean(false)\n    private val cancelCurrent = AtomicBoolean(false)\n    @Volatile private var currentJobId: String? = null\n    @Volatile private var currentSourcePath: String? = null\n    @Volatile private var currentProgress = 0\n    @Volatile private var currentMessage = \"\"\n    private var wakeLock: PowerManager.WakeLock? = null\n''',
    '''    private val workerRunning = AtomicBoolean(false)\n    private val cancelCurrent = AtomicBoolean(false)\n    private val progressPublishLock = Any()\n    @Volatile private var currentJobId: String? = null\n    @Volatile private var currentSourcePath: String? = null\n    @Volatile private var currentProgress = 0\n    @Volatile private var currentMessage = \"\"\n    @Volatile private var lastPublishedProgress = -1\n    @Volatile private var lastPublishedMessage = \"\"\n    @Volatile private var lastPublishedAtMs = 0L\n    @Volatile private var foregroundStarted = false\n    private var wakeLock: PowerManager.WakeLock? = null\n''',
    "campos de publicacao",
)

old_on_start = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        when (intent?.action) {\n            ACTION_ENQUEUE -> {\n                val path = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()\n                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)\n                File(path).takeIf { it.isFile }?.let {\n                    AutoGapRepairQueueStore.enqueue(this, it, fps)\n                }\n                kickWorker()\n            }\n            ACTION_RESUME -> kickWorker()\n            ACTION_RETRY_FAILED -> {\n                AutoGapRepairQueueStore.retryFailed(this)\n                kickWorker()\n            }\n            ACTION_PAUSE_CAPTURE, ACTION_PAUSE_USER -> {\n                cancelCurrent.set(true)\n                if (!workerRunning.get()) stopSelf(startId)\n                else publish(currentProgress, \"Pausando reparo para priorizar a gravação…\")\n            }\n        }\n        return START_NOT_STICKY\n    }\n'''
new_on_start = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        val action = intent?.action\n        when (action) {\n            ACTION_ENQUEUE -> {\n                // startForegroundService() exige promoção imediata, mesmo se a fila mudar\n                // entre o envio do Intent e a execução deste callback.\n                ensureForegroundStarted(\"Verificando fila de reparo…\")\n                val path = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()\n                val fps = intent.getIntExtra(EXTRA_TARGET_FPS, 60)\n                File(path).takeIf { it.isFile }?.let {\n                    AutoGapRepairQueueStore.enqueue(this, it, fps)\n                }\n                kickWorker()\n            }\n            ACTION_RESUME -> {\n                ensureForegroundStarted(\"Verificando fila de reparo…\")\n                kickWorker()\n            }\n            ACTION_RETRY_FAILED -> {\n                ensureForegroundStarted(\"Verificando fila de reparo…\")\n                AutoGapRepairQueueStore.retryFailed(this)\n                kickWorker()\n            }\n            ACTION_PAUSE_CAPTURE, ACTION_PAUSE_USER -> {\n                cancelCurrent.set(true)\n                if (!workerRunning.get()) stopSelf(startId)\n                else publish(currentProgress, \"Pausando reparo para priorizar a gravação…\", force = true)\n            }\n        }\n        return if (action == ACTION_ENQUEUE || action == ACTION_RESUME || action == ACTION_RETRY_FAILED) {\n            START_REDELIVER_INTENT\n        } else {\n            START_NOT_STICKY\n        }\n    }\n'''
auto = replace_once(auto, old_on_start, new_on_start, "onStartCommand")

auto = replace_once(
    auto,
    '''                currentProgress = 0\n                currentMessage = \"Preparando reparo automático\"\n                cancelCurrent.set(false)\n''',
    '''                currentProgress = 0\n                currentMessage = \"Preparando reparo automático\"\n                synchronized(progressPublishLock) {\n                    lastPublishedProgress = -1\n                    lastPublishedMessage = \"\"\n                    lastPublishedAtMs = 0L\n                }\n                cancelCurrent.set(false)\n''',
    "reset da publicacao por job",
)

auto = replace_once(
    auto,
    '        publish(1, "Analisando ${source.name}…")\n',
    '        publish(1, "Analisando ${source.name}…", force = true)\n',
    "publicacao inicial",
)

auto = replace_once(
    auto,
    '            publish(0, "Falha no reparo de ${source.name}; disponível para tentar novamente")\n',
    '            publish(0, "Falha no reparo de ${source.name}; disponível para tentar novamente", force = true)\n',
    "publicacao de erro",
)

old_foreground = '''    private fun startForegroundProcessing(message: String) {\n        val notification = buildNotification(0, message)\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {\n            startForeground(\n                NOTIFICATION_ID,\n                notification,\n                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING\n            )\n        } else {\n            startForeground(NOTIFICATION_ID, notification)\n        }\n    }\n\n    private fun publish(progress: Int, message: String) {\n        currentProgress = progress.coerceIn(0, 100)\n        currentMessage = message\n        currentJobId?.let { AutoGapRepairQueueStore.updateProgress(this, it, currentProgress, message) }\n        broadcastProgress(running = currentProgress < 100)\n        getSystemService(NotificationManager::class.java)\n            .notify(NOTIFICATION_ID, buildNotification(currentProgress, message))\n    }\n'''
new_foreground = '''    private fun startForegroundProcessing(message: String) {\n        currentMessage = message\n        ensureForegroundStarted(message)\n        getSystemService(NotificationManager::class.java)\n            .notify(NOTIFICATION_ID, buildNotification(currentProgress, message))\n    }\n\n    private fun ensureForegroundStarted(message: String) {\n        if (foregroundStarted) return\n        val notification = buildNotification(currentProgress, message)\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {\n            startForeground(\n                NOTIFICATION_ID,\n                notification,\n                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING\n            )\n        } else {\n            startForeground(NOTIFICATION_ID, notification)\n        }\n        foregroundStarted = true\n    }\n\n    private fun publish(progress: Int, message: String, force: Boolean = false) {\n        val safeProgress = progress.coerceIn(0, 100)\n        currentProgress = safeProgress\n        currentMessage = message\n\n        val now = SystemClock.elapsedRealtime()\n        val shouldPublish = synchronized(progressPublishLock) {\n            val changed = safeProgress != lastPublishedProgress || message != lastPublishedMessage\n            val intervalElapsed = lastPublishedAtMs == 0L || now - lastPublishedAtMs >= PROGRESS_PUBLISH_INTERVAL_MS\n            val publishNow = force || safeProgress >= 100 || changed && intervalElapsed\n            if (publishNow) {\n                lastPublishedProgress = safeProgress\n                lastPublishedMessage = message\n                lastPublishedAtMs = now\n            }\n            publishNow\n        }\n        if (!shouldPublish) return\n\n        currentJobId?.let { AutoGapRepairQueueStore.updateProgress(this, it, safeProgress, message) }\n        broadcastProgress(running = safeProgress < 100)\n        getSystemService(NotificationManager::class.java)\n            .notify(NOTIFICATION_ID, buildNotification(safeProgress, message))\n    }\n'''
auto = replace_once(auto, old_foreground, new_foreground, "foreground e throttle")

auto = replace_once(
    auto,
    '''    private fun clearNotification() {\n        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }\n        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)\n    }\n''',
    '''    private fun clearNotification() {\n        if (foregroundStarted) {\n            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }\n            foregroundStarted = false\n        }\n        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)\n    }\n''',
    "limpeza de foreground",
)

auto = replace_once(
    auto,
    '''        private const val NOTIFICATION_ID = 4011\n        private const val MAX_WAKE_LOCK_MS = 5L * 60L * 60L * 1_000L\n''',
    '''        private const val NOTIFICATION_ID = 4011\n        private const val MAX_WAKE_LOCK_MS = 5L * 60L * 60L * 1_000L\n        private const val PROGRESS_PUBLISH_INTERVAL_MS = 300L\n''',
    "intervalo de publicacao",
)

auto_path.write_text(auto)

manual_path = Path("app/src/main/java/com/steadyvault/camera/processing/service/VideoOptimizationService.kt")
manual = manual_path.read_text()
manual = replace_once(
    manual,
    '''        return START_NOT_STICKY\n    }\n\n    private fun startOptimization(intent: Intent) {\n''',
    '''        return if (intent?.action == ACTION_START) START_REDELIVER_INTENT else START_NOT_STICKY\n    }\n\n    private fun startOptimization(intent: Intent) {\n''',
    "restart da otimizacao manual",
)
manual_path.write_text(manual)

manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text()
manifest = replace_once(
    manifest,
    '''        <service\n            android:name=".processing.service.VideoOptimizationService"\n            android:exported="false"\n            android:foregroundServiceType="mediaProcessing" />\n''',
    '''        <service\n            android:name=".processing.service.VideoOptimizationService"\n            android:exported="false"\n            android:stopWithTask="false"\n            android:foregroundServiceType="mediaProcessing" />\n''',
    "manifest da otimizacao manual",
)
manifest_path.write_text(manifest)

build_path = Path("app/build.gradle.kts")
build = build_path.read_text()
build = replace_once(build, "versionCode = 1000148", "versionCode = 1000149", "versionCode")
build_path.write_text(build)

print("Optimization lifecycle fix applied")
