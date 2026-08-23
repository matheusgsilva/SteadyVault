package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.storage.vault.VaultAreaId
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.settings.VisualIdentityStore
import com.steadyvault.camera.ui.vault.VaultBulkImportRunner.ProcessingOutcome
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VaultImportService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SteadyVault-ImportService") }
    private val busy = AtomicBoolean(false)
    private val serviceLifecycleLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeArea: String? = null
    @Volatile private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val area = intent?.getStringExtra(EXTRA_AREA)?.takeIf(VaultAreaId::isValid)
        if (action == ACTION_CANCEL && area != null) {
            VaultBulkImportRunner.requestCancellation(this, area)
            updateNotification(area)
            return START_STICKY
        }

        synchronized(serviceLifecycleLock) {
            latestStartId = maxOf(latestStartId, startId)
            val resolvedArea = area ?: VaultImportQueueStore.pendingAreas(this).firstOrNull()
            if (resolvedArea == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (busy.compareAndSet(false, true)) {
                activeArea = resolvedArea
                promote(resolvedArea)
                acquireWakeLock()
                executor.execute { drainPendingQueues(resolvedArea) }
            } else {
                promote(activeArea ?: resolvedArea)
            }
        }
        return START_STICKY
    }

    private fun drainPendingQueues(initialArea: String) {
        val blocked = mutableSetOf<String>()
        var preferredArea: String? = initialArea
        var pausedBySystem = false

        while (true) {
            val area = preferredArea?.takeIf { VaultImportQueueStore.hasPending(this, it) && it !in blocked }
                ?: VaultImportQueueStore.pendingAreas(this).firstOrNull { it !in blocked }

            if (area == null) {
                busy.set(false)
                val latePending = VaultImportQueueStore.pendingAreas(this).firstOrNull { it !in blocked }
                if (latePending != null && busy.compareAndSet(false, true)) {
                    preferredArea = latePending
                    continue
                }
                break
            }

            preferredArea = null
            activeArea = area
            updateNotification(area)
            val outcome = runCatching {
                VaultBulkImportRunner.processPending(this, area) { updateNotification(area) }
            }.onFailure { error ->
                AppLogRepository.error(this, "import", "Falha fatal no serviço de importação", error)
                VaultBulkImportRunner.failPending(this, area, error)
            }.getOrElse { ProcessingOutcome.FAILED }

            when (outcome) {
                ProcessingOutcome.PAUSED_BY_SYSTEM -> {
                    pausedBySystem = true
                    break
                }
                ProcessingOutcome.FAILED -> blocked += area
                else -> blocked.remove(area)
            }
        }

        synchronized(serviceLifecycleLock) {
            if (!pausedBySystem && !busy.get() && VaultImportQueueStore.pendingAreas(this).none { it !in blocked }) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
            } else if (pausedBySystem) {
                releaseWakeLock()
                activeArea?.let(::updateNotification)
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeArea?.let { area ->
            VaultBulkImportRunner.pauseForSystemLimit(this, area)
            AppLogRepository.warn(this, "import", "Importação pausada pelo limite do Android para foreground service dataSync")
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildPausedNotification(activeArea))
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(area: String) {
        val notification = buildNotification(area)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(area: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(area))
    }

    private fun buildNotification(area: String): Notification {
        val snapshot = VaultBulkImportRunner.snapshot(this, area)
        val identity = VisualIdentityStore.notificationIdentity(this)
        val cancelIntent = PendingIntent.getService(
            this,
            9400 + area.hashCode().and(0x3ff),
            Intent(this, VaultImportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_AREA, area),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = VisualIdentityStore.notificationTitle(this, "Importação")
        val originalText = snapshot.message.ifBlank { "Importando mídias para o cofre" }
        val text = VisualIdentityStore.notificationText(this, originalText, "Processando arquivos")
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentIntent(openAreaIntent(area))
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.running)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(false)
        if (snapshot.total > 0) builder.setProgress(snapshot.total, snapshot.done.coerceAtMost(snapshot.total), false) else builder.setProgress(0, 0, snapshot.running)
        if (snapshot.running && !snapshot.cancelRequested) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, identity.cancelIcon),
                    VisualIdentityStore.actionLabel(this, "Cancelar importação", "Interromper"),
                    cancelIntent
                ).build()
            )
        }
        return builder.build()
    }

    private fun buildPausedNotification(area: String?): Notification {
        val resolvedArea = area ?: VaultImportQueueStore.pendingAreas(this).firstOrNull() ?: VaultAreaId.PRIMARY
        val identity = VisualIdentityStore.notificationIdentity(this)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(identity.smallIcon)
            .setColor(AppearanceStore.palette(this).accent)
            .setContentTitle(VisualIdentityStore.notificationTitle(this, "Importação pausada"))
            .setContentText(VisualIdentityStore.notificationText(this, "Fila preservada. Toque para retomar quando o Android permitir.", "Processamento pausado"))
            .setContentIntent(openAreaIntent(resolvedArea))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(false)
            .build()
    }

    private fun openAreaIntent(area: String): PendingIntent {
        val target = when (area) {
            VaultAreaId.SECONDARY -> SecondaryVaultActivity::class.java
            VaultAreaId.TERTIARY -> TertiaryVaultActivity::class.java
            else -> PrimaryVaultActivity::class.java
        }
        return PendingIntent.getActivity(
            this,
            9500 + area.hashCode().and(0x3ff),
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Importações em andamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progresso e cancelamento das importações para cofres"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteadyVault:VaultImport")?.apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "com.steadyvault.camera.IMPORT_START"
        private const val ACTION_CANCEL = "com.steadyvault.camera.IMPORT_CANCEL"
        private const val EXTRA_AREA = "area"
        private const val CHANNEL_ID = "steadyvault_import_v1"
        private const val NOTIFICATION_ID = 41
        private const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L

        fun start(context: Context, area: String) {
            require(VaultAreaId.isValid(area)) { "Cofre inválido" }
            val intent = Intent(context, VaultImportService::class.java).setAction(ACTION_START).putExtra(EXTRA_AREA, area)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, area: String) {
            require(VaultAreaId.isValid(area)) { "Cofre inválido" }
            val intent = Intent(context, VaultImportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_AREA, area)
            context.startService(intent)
        }

        fun resumePending(context: Context, area: String) {
            require(VaultAreaId.isValid(area)) { "Cofre inválido" }
            if (VaultImportQueueStore.hasPending(context, area)) start(context, area)
        }
    }
}
