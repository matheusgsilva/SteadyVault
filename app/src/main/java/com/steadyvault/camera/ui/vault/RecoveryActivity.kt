package com.steadyvault.camera.ui.vault

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.steadyvault.camera.R
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.storage.vault.RecordingRecoveryRepository
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.security.PinPadDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RecoveryActivity : FragmentActivity() {
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var prompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Central de recuperação"
        val screen = buildScreen()
        setContentView(screen)
        SystemBarInsets.applyTopAndBottom(screen)
    }

    override fun onResume() {
        super.onResume()
        if (ensureUnlocked()) refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
        }
        val header = TextView(this).apply {
            text = "Central de recuperação"
            setTextColor(getColor(R.color.text_primary))
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(26), dp(20), dp(10))
        }
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Trechos interrompidos e vídeos recuperados ficam preservados fora do cache. Nada desta tela é apagado automaticamente."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(dp(20), 0, dp(20), dp(16))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun ensureUnlocked(): Boolean {
        if (!PrimaryVaultLock.isEnabled(this) || PrimaryVaultLock.isUnlocked(this)) return true
        if (prompted) return false
        prompted = true
        val biometrics = VaultSecuritySettings.biometricEnabled(this) && VaultSecuritySettings.canUseBiometrics(this)
        if (biometrics) showBiometric() else showPin()
        return false
    }

    private fun showBiometric() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompted = false
                PrimaryVaultLock.unlockSession()
                refresh()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompted = false
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) showPin() else finish()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Central de recuperação")
                .setSubtitle("Confirme sua identidade para ver gravações recuperáveis")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Usar PIN")
                .build()
        )
    }

    private fun showPin() {
        if (!PrimaryVaultLock.isEnabled(this)) {
            refresh()
            return
        }
        prompted = true
        PinPadDialog.showVerify(
            activity = this,
            title = "Central de recuperação",
            subtitle = "Digite o PIN do cofre principal para acessar gravações recuperáveis.",
            verify = { PrimaryVaultLock.verify(this, it) },
            onVerified = { prompted = false; refresh() },
            onCancel = { prompted = false; finish() }
        )
    }

    private fun refresh() {
        content.removeAllViews()
        if (!PrimaryVaultLock.isEnabled(this)) {
            addInfo("Proteção opcional", "Crie um PIN do cofre principal nos Ajustes para exigir PIN ou biometria antes de abrir esta central.")
        }
        val loading = addInfo("Analisando arquivos…", "Verificando MP4 temporários e vídeos já recuperados.")
        worker.execute {
            RecordingRecoveryRepository.recoverStaleRecordings(this)
            val items = RecordingRecoveryRepository.listCandidates(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                content.removeView(loading)
                if (items.isEmpty()) addInfo("Tudo certo", "Nenhuma gravação interrompida ou recuperada aguardando ação.")
                items.forEach(::addCandidate)
            }
        }
    }

    private fun addCandidate(candidate: RecordingRecoveryRepository.RecoveryCandidate) {
        val title = when (candidate.state) {
            RecordingRecoveryRepository.CandidateState.RECOVERABLE -> "Trecho recuperável"
            RecordingRecoveryRepository.CandidateState.INCOMPLETE -> "Gravação incompleta preservada"
            RecordingRecoveryRepository.CandidateState.RECOVERED -> "Vídeo recuperado"
        }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(candidate.modifiedAt))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card)
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(if (candidate.state == RecordingRecoveryRepository.CandidateState.INCOMPLETE) R.color.widget_record_yellow else R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "${candidate.file.name}\n${VaultRepository.formatBytes(candidate.bytes)} • $date\n${candidate.description}"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12.5f
            setPadding(0, dp(5), 0, dp(10))
        })
        when (candidate.state) {
            RecordingRecoveryRepository.CandidateState.RECOVERED -> {
                card.addView(action("Abrir vídeo") {
                    startActivity(Intent(this, MediaPlayerActivity::class.java).putExtra(MediaPlayerActivity.EXTRA_PATH, candidate.file.absolutePath))
                })
                card.addView(action("Mover para outro cofre") { chooseMove(candidate) })
            }
            RecordingRecoveryRepository.CandidateState.RECOVERABLE, RecordingRecoveryRepository.CandidateState.INCOMPLETE -> {
                card.addView(action("Tentar recuperar agora") { recover(candidate) })
                card.addView(action("Excluir este arquivo", destructive = true) { confirmDelete(candidate) })
            }
        }
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun recover(candidate: RecordingRecoveryRepository.RecoveryCandidate) {
        val progress = OneUiDialog.progress(this, "Recuperando vídeo", "Validando o arquivo e preservando uma cópia na área de recuperação…", false)
        worker.execute {
            val result = RecordingRecoveryRepository.recoverOne(this, candidate.file)
            if (result != null) candidate.file.delete()
            runOnUiThread {
                progress.dismiss()
                if (result != null) Toast.makeText(this, "Vídeo recuperado e preservado na área de recuperação", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Ainda não foi possível formar um MP4 reproduzível; o arquivo foi mantido", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun chooseMove(candidate: RecordingRecoveryRepository.RecoveryCandidate) {
        val choices = mutableListOf(
            RecordingRecoveryRepository.AREA_PRIMARY to OneUiDialog.Choice("Cofre principal", "Mover o recuperado para o cofre principal.")
        )
        if (SecondaryVaultLock.isEnabled(this)) choices += RecordingRecoveryRepository.AREA_SECONDARY to OneUiDialog.Choice("Cofre secundário", "Mover para o cofre secundário configurado.")
        if (TertiaryVaultLock.isEnabled(this)) choices += RecordingRecoveryRepository.AREA_TERTIARY to OneUiDialog.Choice("Cofre terciário", "Mover para o cofre terciário configurado.")
        OneUiDialog.choices(this, "Destino do recuperado", "A movimentação é validada antes de remover o arquivo de origem.", choices.map { it.second }) { index ->
            val area = choices[index].first
            worker.execute {
                val result = runCatching { RecordingRecoveryRepository.moveRecovered(this, candidate.file, area) }
                runOnUiThread {
                    result.onSuccess { Toast.makeText(this, "Vídeo movido com segurança", Toast.LENGTH_SHORT).show(); refresh() }
                        .onFailure { Toast.makeText(this, it.message ?: "Falha ao mover", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun confirmDelete(candidate: RecordingRecoveryRepository.RecoveryCandidate) {
        OneUiDialog.confirm(this, "Excluir arquivo?", "Esta ação apaga definitivamente este arquivo de recuperação e não pode ser desfeita.", "Excluir", destructive = true) {
            val deleted = RecordingRecoveryRepository.deleteCandidate(this, candidate.file)
            Toast.makeText(this, if (deleted) "Arquivo excluído" else "Não foi possível excluir", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun addInfo(title: String, detail: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_card)
        }
        card.addView(TextView(this).apply { text = title; setTextColor(getColor(R.color.text_primary)); textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = detail; setTextColor(getColor(R.color.text_secondary)); textSize = 13f; setPadding(0, dp(5), 0, 0) })
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        return card
    }

    private fun action(label: String, destructive: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(getColor(if (destructive) R.color.record_red else R.color.text_primary))
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundResource(R.drawable.bg_oneui_button_secondary)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(7) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
