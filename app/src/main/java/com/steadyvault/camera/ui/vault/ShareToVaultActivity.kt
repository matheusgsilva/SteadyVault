package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.security.PinPadDialog

class ShareToVaultActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var openButton: TextView
    private lateinit var cancelButton: TextView
    private var uris: List<Uri> = emptyList()
    private var selectedArea: String? = null
    private var finalShown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val importRefresh = object : Runnable {
        override fun run() {
            val area = selectedArea ?: return
            renderImportState(area)
            if (VaultBulkImportRunner.snapshot(this@ShareToVaultActivity, area).running) mainHandler.postDelayed(this, 350L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CaptureSettings.snapshot(this).secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        uris = incomingMediaUris(intent)
        setContentView(createContent())
        SystemBarInsets.applyTopAndBottom(findViewById(R.id.shareVaultRoot))
        if (uris.isEmpty()) {
            status.text = "Nenhuma foto ou vídeo compatível foi compartilhado."
            progress.visibility = View.GONE
            openButton.text = "Fechar"
            openButton.visibility = View.VISIBLE
            openButton.setOnClickListener { finish() }
        } else {
            status.text = "${uris.size} mídia(s) recebida(s). Escolha o cofre de destino."
            chooseDestination()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uris = incomingMediaUris(intent)
        finalShown = false
        selectedArea = null
        chooseDestination()
    }

    private fun chooseDestination() {
        progress.visibility = View.GONE
        openButton.visibility = View.GONE
        OneUiDialog.choices(
            activity = this,
            title = "Importar para o cofre",
            message = "Escolha o cofre para importar ${uris.size} mídia(s) compartilhada(s).",
            choices = listOf(
                OneUiDialog.Choice("Cofre principal", "Importa para o cofre privado principal."),
                OneUiDialog.Choice(
                    "Cofre secundário",
                    if (SecondaryVaultLock.isEnabled(this)) "Importa para o cofre secundário." else "Configure o PIN desse cofre antes de usar.",
                    enabled = SecondaryVaultLock.isEnabled(this)
                ),
                OneUiDialog.Choice(
                    "Cofre terciário",
                    if (TertiaryVaultLock.isEnabled(this)) "Importa para o cofre terciário." else "Configure o PIN desse cofre antes de usar.",
                    enabled = TertiaryVaultLock.isEnabled(this)
                )
            ),
            cancelLabel = "Cancelar"
        ) { option ->
            val area = when (option) {
                1 -> VaultAreaId.SECONDARY
                2 -> VaultAreaId.TERTIARY
                else -> VaultAreaId.PRIMARY
            }
            requestUnlockThenImport(area)
        }.setOnCancelListener { finish() }
    }

    private fun requestUnlockThenImport(area: String) {
        fun begin() = startImport(area)
        when (area) {
            VaultAreaId.SECONDARY -> if (SecondaryVaultLock.isUnlocked(this)) begin() else PinPadDialog.showVerify(
                activity = this,
                title = "Desbloquear cofre secundário",
                subtitle = "Digite o PIN para importar as mídias compartilhadas.",
                verify = { SecondaryVaultLock.verify(this, it) },
                onVerified = { begin() },
                onCancel = { finish() }
            )
            VaultAreaId.TERTIARY -> if (TertiaryVaultLock.isUnlocked(this)) begin() else PinPadDialog.showVerify(
                activity = this,
                title = "Desbloquear cofre terciário",
                subtitle = "Digite o PIN para importar as mídias compartilhadas.",
                verify = { TertiaryVaultLock.verify(this, it) },
                onVerified = { begin() },
                onCancel = { finish() }
            )
            else -> if (PrimaryVaultLock.isUnlocked(this)) begin() else PinPadDialog.showVerify(
                activity = this,
                title = "Desbloquear cofre principal",
                subtitle = "Digite o PIN para importar as mídias compartilhadas.",
                verify = { PrimaryVaultLock.verify(this, it) },
                onVerified = { begin() },
                onCancel = { finish() }
            )
        }
    }

    private fun startImport(area: String) {
        selectedArea = area
        finalShown = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        openButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        cancelButton.isEnabled = true
        cancelButton.text = "Cancelar importação"
        status.text = "Preparando importação…"
        Haptics.tap(this)
        VaultImportUtils.persistDocumentReadPermissions(this, intent, uris)
        VaultBulkImportRunner.start(this, area, uris, "mídia(s) compartilhada(s)")
        mainHandler.removeCallbacks(importRefresh)
        mainHandler.post(importRefresh)
    }

    private fun renderImportState(area: String) {
        val snap = VaultBulkImportRunner.snapshot(this, area)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = snap.total <= 0 && snap.running
        progress.progress = if (snap.total > 0) ((snap.done * 100) / snap.total.coerceAtLeast(1)).coerceIn(0, 100) else 0
        status.text = snap.message.ifBlank { if (snap.running) "Importando…" else "Importação finalizada" }
        cancelButton.visibility = if (snap.running) View.VISIBLE else View.GONE
        cancelButton.isEnabled = snap.running && !snap.cancelRequested
        cancelButton.text = if (snap.cancelRequested) "Cancelando com segurança…" else "Cancelar importação"
        if (!snap.running && !finalShown) {
            finalShown = true
            progress.visibility = View.GONE
            val cancelled = snap.message.startsWith("Importação cancelada")
            val resultMessage = VaultBulkImportRunner.consumeResult(this, area)
            val message = resultMessage ?: buildString {
                if (cancelled) {
                    append(snap.message)
                } else {
                    append(snap.imported).append(" mídia(s) importada(s)")
                    if (snap.skipped > 0) append(" • ").append(snap.skipped).append(" repetida(s) ignorada(s)")
                }
            }
            if (!cancelled) Haptics.success(this)
            OneUiDialog.message(
                activity = this,
                title = if (cancelled) "Importação cancelada" else "Importação concluída",
                message = message,
                positiveLabel = if (snap.imported > 0) "Abrir cofre" else "Fechar"
            ) { if (snap.imported > 0) openTarget(area) else finish() }
            openButton.text = if (snap.imported > 0) "Abrir cofre" else "Fechar"
            openButton.visibility = View.VISIBLE
            openButton.setOnClickListener { if (snap.imported > 0) openTarget(area) else finish() }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(importRefresh)
        super.onDestroy()
    }

    private fun openTarget(area: String) {
        val target = when (area) {
            VaultAreaId.SECONDARY -> SecondaryVaultActivity::class.java
            VaultAreaId.TERTIARY -> TertiaryVaultActivity::class.java
            else -> PrimaryVaultActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun incomingMediaUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val values = linkedSetOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(values::add)
        }
        when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )?.let(values::addAll)
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )?.let(values::add)
        }
        return values.filter { uri ->
            val info = VaultImportUtils.sourceInfo(this, uri)
            VaultMediaFormats.isSupported(info.displayName, info.mime)
        }
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            id = R.id.shareVaultRoot
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(26), dp(20), dp(26))
            setBackgroundColor(getColor(R.color.background))
        }
        root.addView(TextView(this).apply {
            text = "Importar para o cofre"
            setTextColor(getColor(R.color.text_primary))
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Preparando…"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })
        cancelButton = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = "Cancelar importação"
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_oneui_button_secondary)
            setOnClickListener {
                val area = selectedArea ?: return@setOnClickListener
                isEnabled = false
                text = "Cancelando com segurança…"
                VaultBulkImportRunner.cancel(this@ShareToVaultActivity, area)
                renderImportState(area)
            }
        }
        root.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        openButton = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            text = "Abrir cofre"
            setTextColor(getColor(R.color.background))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_oneui_button_primary)
        }
        root.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(22) })
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
