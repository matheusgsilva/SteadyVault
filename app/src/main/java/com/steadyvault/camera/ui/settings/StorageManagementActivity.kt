package com.steadyvault.camera.ui.settings

import android.app.ActivityManager
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.steadyvault.camera.R
import com.steadyvault.camera.storage.vault.AppStorageCatalog
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.ui.browser.BrowserWebViewConfigurator
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import java.util.concurrent.Executors

class StorageManagementActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "SteadyVault-StorageManager") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Tudo que o app salva"
        val screen = buildScreen()
        setContentView(screen)
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
            text = "Tudo que o SteadyVault salva"
            setTextColor(getColor(R.color.text_primary)); textSize = 24f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(20), dp(24), dp(20), dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Veja cada área, quanto ela ocupa e apague somente o que escolher. Vídeos com erro ficam em armazenamento persistente, separado do cache."
            setTextColor(getColor(R.color.text_secondary)); textSize = 13f; setPadding(dp(20), 0, dp(20), dp(14))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(26)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refresh() {
        content.removeAllViews()
        content.addView(infoCard("Calculando…", "Lendo somente diretórios pertencentes ao aplicativo."))
        worker.execute {
            val snapshot = AppStorageCatalog.snapshot(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                content.removeAllViews()
                content.addView(infoCard("Total gerenciado", VaultRepository.formatBytes(snapshot.totalBytes)))
                snapshot.categories.forEach { category -> content.addView(categoryCard(category)) }
                content.addView(actionButton("Zerar todo o aplicativo", destructive = true) { confirmFullReset() })
                content.addView(TextView(this).apply {
                    text = "Zerar todo o aplicativo usa a limpeza oficial de dados do Android. Isso remove configurações, relatórios, cofres privados e outros dados do app e encerra o SteadyVault."
                    setTextColor(getColor(R.color.text_secondary)); textSize = 12f; setPadding(dp(8), dp(8), dp(8), 0)
                })
            }
        }
    }

    private fun categoryCard(category: AppStorageCatalog.Category): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); setBackgroundResource(R.drawable.bg_card)
        addView(TextView(this@StorageManagementActivity).apply {
            text = category.label; setTextColor(getColor(R.color.text_primary)); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@StorageManagementActivity).apply {
            val size = if (category.bytes > 0L) VaultRepository.formatBytes(category.bytes) else "sem tamanho local calculável"
            text = "${category.files} item(ns) • $size\n${category.description}"
            setTextColor(getColor(R.color.text_secondary)); textSize = 12.5f; setPadding(0, dp(4), 0, dp(8))
        })
        if (category.clearable) {
            addView(actionButton("Apagar esta área", destructive = true) { confirmClear(category) })
        } else {
            addView(TextView(this@StorageManagementActivity).apply {
                text = "Removido somente pelo botão ‘Zerar todo o aplicativo’."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f; setPadding(0, dp(4), 0, 0)
            })
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
    }

    private fun confirmClear(category: AppStorageCatalog.Category) {
        OneUiDialog.confirm(this, "Apagar ${category.label}?", "Serão removidos os dados desta área. Esta ação não pode ser desfeita.", "Apagar", destructive = true) {
            if (category.id == AppStorageCatalog.ID_BROWSER_DATA) {
                clearBrowserData()
            } else {
                worker.execute {
                    val ok = AppStorageCatalog.clear(this, category.id)
                    runOnUiThread { Toast.makeText(this, if (ok) "Área limpa" else "Não foi possível limpar tudo", Toast.LENGTH_SHORT).show(); refresh() }
                }
            }
        }
    }

    private fun clearBrowserData() {
        val webView = WebView(this)
        BrowserWebViewConfigurator.configure(webView, mixedContentCompatibility = false, thirdPartyCookies = false)
        BrowserWebViewConfigurator.clearPrivateData(webView) {
            webView.destroy()
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Dados privados do navegador apagados", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun confirmFullReset() {
        OneUiDialog.confirm(this, "Zerar todo o aplicativo?", "Isso apaga TODOS os dados privados do SteadyVault, inclusive vídeos e fotos dos cofres. O app será encerrado e voltará como recém-instalado.", "Zerar tudo", destructive = true) {
            val manager = getSystemService(ActivityManager::class.java)
            if (manager?.clearApplicationUserData() != true) Toast.makeText(this, "O Android não permitiu zerar os dados", Toast.LENGTH_LONG).show()
        }
    }

    private fun infoCard(title: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); setBackgroundResource(R.drawable.bg_card)
        addView(TextView(this@StorageManagementActivity).apply { text = title; setTextColor(getColor(R.color.text_primary)); textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        addView(TextView(this@StorageManagementActivity).apply { text = detail; setTextColor(getColor(R.color.text_secondary)); textSize = 13f; setPadding(0, dp(4), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
    }

    private fun actionButton(label: String, destructive: Boolean = false, action: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(getColor(if (destructive) R.color.record_red else R.color.text_primary)); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(R.drawable.bg_oneui_button_secondary); isClickable = true; isFocusable = true; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(5) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
