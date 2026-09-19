package com.steadyvault.camera.ui.settings

import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.storage.vault.VaultAreaId
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.steadyvault.camera.R
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.core.diagnostics.PhotoPerformanceTracker
import com.steadyvault.camera.core.state.CaptureStateStore
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.RecordingRecoveryRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultCleanupRepository
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.vault.VaultBulkImportRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class DiagnosticsActivity : FragmentActivity() {
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = buildScreen()
        setContentView(screen)
        AppearanceRuntime.apply(this)
        SystemBarInsets.applyTopAndBottom(screen)
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.background)) }
        root.addView(TextView(this).apply {
            text = "Diagnóstico e armazenamento"
            setTextColor(getColor(R.color.text_primary)); textSize = 25f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(20), dp(26), dp(20), dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Veja logs, caches, temporários e espaço ocupado antes de apagar qualquer coisa."
            setTextColor(getColor(R.color.text_secondary)); textSize = 14f; setPadding(dp(20), 0, dp(20), dp(14))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(30)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refresh() {
        content.removeAllViews()
        val loading = card("Calculando…", "Lendo apenas metadados e tamanhos; nenhuma mídia será modificada.")
        worker.execute {
            val cache = VaultCleanupRepository.cacheSnapshot(this)
            val logs = AppLogRepository.snapshot(this)
            val primary = sizeOf(VaultRepository.primaryDirectory(this))
            val secondary = sizeOf(SecondaryVaultRepository.directory(this))
            val tertiary = sizeOf(TertiaryVaultRepository.directory(this))
            val trash = sizeOf(VaultTrashRepository.directory(this))
            val recovery = RecordingRecoveryRepository.recoveryBytes(this)
            val photoPerformance = PhotoPerformanceTracker.lastReport(this)
            val importPartials = importPartialFiles().take(80)
            val importRunning = VaultAreaId.ALL.any { VaultBulkImportRunner.snapshot(this, it).running }
            val cacheFiles = cacheEntries().take(80)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                content.removeView(loading)
                addStorage(primary, secondary, tertiary, trash, cache.totalDiskBytes, recovery, logs.bytes)
                addPhotoPerformance(photoPerformance)
                addImportPartials(importPartials, importRunning)
                addLogs(logs.lines)
                addCacheFiles(cacheFiles)
            }
        }
    }

    private fun addStorage(primary: Long, secondary: Long, tertiary: Long, trash: Long, cache: Long, recovery: Long, logs: Long) {
        val text = buildString {
            append("Cofre principal: ${VaultRepository.formatBytes(primary)}\n")
            append("Cofre secundário: ${VaultRepository.formatBytes(secondary)}\n")
            append("Cofre terciário: ${VaultRepository.formatBytes(tertiary)}\n")
            append("Lixeira privada: ${VaultRepository.formatBytes(trash)}\n")
            append("Caches recriáveis: ${VaultRepository.formatBytes(cache)}\n")
            append("Gravações interrompidas protegidas: ${VaultRepository.formatBytes(recovery)}\n")
            append("Logs: ${VaultRepository.formatBytes(logs)}")
        }
        val block = card("Ocupação do SteadyVault", text)
        block.addView(action("Atualizar") { refresh() })
        block.addView(action("Limpar apenas caches seguros") { confirmCleanCaches() })
    }

    private fun addPhotoPerformance(report: PhotoPerformanceTracker.Report?) {
        val text = report?.displayText() ?: "Nenhuma foto individual foi cronometrada ainda. A medição é local e adiciona apenas marcações de tempo leves ao fluxo existente."
        card("Desempenho da última foto", text)
    }

    private fun addImportPartials(files: List<File>, importRunning: Boolean) {
        if (files.isEmpty()) return
        val block = card(
            "Importações interrompidas",
            if (importRunning) "Há uma fila ativa; temporários ficam protegidos até ela terminar." else "Arquivos .partial preservados após interrupção. A fila persistente pode refazer a mídia; apague somente depois de conferir."
        )
        files.forEach { file ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(TextView(this).apply {
                text = "${file.parentFile?.name ?: "cofre"}/${file.name} • ${VaultRepository.formatBytes(file.length())}"
                setTextColor(getColor(R.color.text_secondary)); textSize = 11.5f; maxLines = 3; setTextIsSelectable(true)
            })
            if (!importRunning) row.addView(action("Apagar temporário") {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                Toast.makeText(this, if (deleted) "Temporário removido" else "Não foi possível apagar", Toast.LENGTH_SHORT).show()
                refresh()
            })
            block.addView(row)
        }
    }

    private fun importPartialFiles(): List<File> = listOf(
        VaultRepository.primaryDirectory(this),
        SecondaryVaultRepository.directory(this),
        TertiaryVaultRepository.directory(this)
    ).flatMap { directory -> directory.listFiles { file -> file.isFile && file.name.endsWith(".svimport.partial") }.orEmpty().toList() }
        .sortedByDescending(File::lastModified)

    private fun addLogs(lines: List<String>) {
        val errors = lines.filter { it.contains("[ERROR]") || it.contains("[WARN]") }.takeLast(120)
        val text = if (errors.isEmpty()) "Nenhum erro/aviso registrado." else errors.joinToString("\n")
        val block = card("Logs de erros", text)
        block.addView(action("Apagar logs") {
            OneUiDialog.confirm(this, "Apagar logs?", "Apaga somente o histórico de diagnóstico; fotos e vídeos não são afetados.", "Apagar", destructive = true) {
                AppLogRepository.clear(this)
                refresh()
            }
        })
    }

    private fun addCacheFiles(files: List<File>) {
        val block = card("Conteúdo do cache", if (files.isEmpty()) "Cache vazio." else "Até 80 arquivos são mostrados por vez. Gravações recuperáveis ficam protegidas e não recebem botão de apagar.")
        files.forEach { file ->
            val protected = RecordingRecoveryRepository.shouldProtectFromCacheCleanup(file) || CaptureStateStore.isBusy(this)
            val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            row.addView(TextView(this).apply {
                text = "${relativeCacheName(file)} • ${VaultRepository.formatBytes(file.length())} • $date"
                setTextColor(getColor(R.color.text_secondary)); textSize = 11.5f; maxLines = 3
            })
            if (!protected) row.addView(action("Apagar este arquivo") {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                Toast.makeText(this, if (deleted) "Arquivo de cache apagado" else "Não foi possível apagar", Toast.LENGTH_SHORT).show()
                refresh()
            })
            block.addView(row)
        }
    }

    private fun confirmCleanCaches() {
        if (CaptureStateStore.isBusy(this)) {
            Toast.makeText(this, "Finalize a gravação antes da limpeza", Toast.LENGTH_LONG).show()
            return
        }
        OneUiDialog.confirm(this, "Limpar caches seguros?", "Miniaturas, metadados recriáveis e temporários comuns serão removidos. Gravações interrompidas ficam protegidas.", "Limpar", destructive = false) {
            val progress = OneUiDialog.progress(this, "Limpando", "Preservando mídias e arquivos recuperáveis…", false)
            worker.execute {
                val result = runCatching {
                    VaultCleanupRepository.cleanCacheAndDirtyData(this)
                    VaultBulkImportRunner.cleanupDirtyState(this)
                }
                runOnUiThread {
                    progress.dismiss()
                    result.onSuccess { Toast.makeText(this, "Caches seguros limpos", Toast.LENGTH_SHORT).show(); refresh() }
                        .onFailure { Toast.makeText(this, it.message ?: "Falha na limpeza", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun cacheEntries(): List<File> = listOfNotNull(cacheDir, externalCacheDir).flatMap { root ->
        root.walkTopDown().filter(File::isFile).toList()
    }.sortedByDescending(File::lastModified)

    private fun relativeCacheName(file: File): String {
        val roots = listOfNotNull(cacheDir, externalCacheDir)
        val root = roots.firstOrNull { runCatching { file.canonicalPath.startsWith(it.canonicalPath) }.getOrDefault(false) }
        return root?.let { runCatching { file.relativeTo(it).path }.getOrDefault(file.name) } ?: file.name
    }

    private fun sizeOf(root: File): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun card(title: String, detail: String): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundResource(R.drawable.bg_card) }
        card.addView(TextView(this).apply { text = title; setTextColor(getColor(R.color.text_primary)); textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = detail; setTextColor(getColor(R.color.text_secondary)); textSize = 12.5f; setPadding(0, dp(6), 0, dp(4)); setTextIsSelectable(true) })
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return card
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(getColor(R.color.text_primary)); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundResource(R.drawable.bg_oneui_button_secondary); isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(7) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
