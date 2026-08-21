package com.steadyvault.camera.ui.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.storage.security.AppVaultLock
import com.steadyvault.camera.storage.security.ProtectedAppsStore
import com.steadyvault.camera.storage.security.ProtectedAppsIndexStore
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.ui.navigation.BottomNavigation
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.security.PinPadDialog
import java.util.Locale
import java.util.concurrent.Executors

class ProtectedAppsActivity : FragmentActivity() {
    private data class LauncherApp(
        val packageName: String,
        val label: String
    )

    private data class AppViewHolder(
        val icon: ImageView,
        val name: TextView,
        val state: TextView
    )

    private lateinit var summary: TextView
    private lateinit var manageButton: TextView
    private lateinit var actions: View
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var lockedPanel: View
    private lateinit var lockedTitle: TextView
    private lateinit var busyOverlay: View
    private lateinit var busyText: TextView
    private val adapter = AppsAdapter()
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-LauncherApps")
    }
    private val iconLoader = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "SteadyVault-AppIcon")
    }
    private val iconCache = LruCache<String, Drawable>(48)
    private val iconRequests = mutableSetOf<String>()
    private var allApps = emptyList<LauncherApp>()
    private var allAppsComplete = false
    private var managing = false
    private var loadGeneration = 0
    private var loadInProgress = false
    private var authenticating = false
    private var biometricPrompt: BiometricPrompt? = null

    private companion object {
        private const val APPS_CACHE_TTL_MS = 10 * 60_000L
        @Volatile private var cachedLauncherApps: List<LauncherApp>? = null
        @Volatile private var launcherAppsCachedAt = 0L
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protected_apps)
        SystemBarInsets.applyTop(findViewById(R.id.protectedAppsRoot))
        BottomNavigation.bind(this, BottomNavigation.TAB_APPS) { AppVaultLock.lock() }

        summary = findViewById(R.id.protectedAppsSummary)
        manageButton = findViewById(R.id.protectedAppsManage)
        actions = findViewById(R.id.protectedAppsActions)
        grid = findViewById(R.id.protectedAppsGrid)
        empty = findViewById(R.id.protectedAppsEmpty)
        lockedPanel = findViewById(R.id.protectedAppsLockedPanel)
        lockedTitle = findViewById(R.id.protectedAppsLockedTitle)
        busyOverlay = findViewById(R.id.protectedAppsBusyOverlay)
        busyText = findViewById(R.id.protectedAppsBusyText)
        grid.adapter = adapter

        manageButton.setOnClickListener {
            Haptics.tap(this)
            managing = !managing
            if (managing) {
                if (loadInProgress) setBusy(true, "Preparando a lista completa…")
                loadLauncherApps(force = false)
            } else {
                setBusy(false)
                renderApps()
            }
        }
        findViewById<View>(R.id.protectedAppsLock).setOnClickListener {
            Haptics.tap(this)
            AppVaultLock.lock()
            managing = false
            renderSecurityState()
        }
        findViewById<View>(R.id.protectedAppsUnlock).setOnClickListener {
            requestUnlock(forcePin = false)
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.itemAt(position) ?: return@setOnItemClickListener
            if (managing) toggleProtectedApp(app) else launchApp(app)
        }

        renderSecurityState()
        if (!AppVaultLock.isUnlocked(this)) {
            grid.post { requestUnlock(forcePin = false) }
        }
    }

    override fun onResume() {
        super.onResume()
        renderSecurityState()
    }

    override fun onStop() {
        if (!authenticating && !isChangingConfigurations) AppVaultLock.lock()
        super.onStop()
    }

    override fun onDestroy() {
        loadGeneration++
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        loader.shutdownNow()
        iconLoader.shutdownNow()
        synchronized(iconRequests) { iconRequests.clear() }
        iconCache.evictAll()
        super.onDestroy()
    }

    private fun renderSecurityState() {
        val unlocked = AppVaultLock.isUnlocked(this)
        lockedPanel.visibility = if (unlocked) View.GONE else View.VISIBLE
        actions.visibility = if (unlocked) View.VISIBLE else View.GONE
        grid.visibility = if (unlocked) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
        if (!unlocked) {
            setBusy(false)
            adapter.submit(emptyList(), emptySet(), false)
            lockedTitle.text = if (AppVaultLock.isEnabled(this)) "Apps bloqueados" else "Crie um PIN para os apps"
            return
        }
        loadLauncherApps(force = false)
    }

    private fun requestUnlock(forcePin: Boolean) {
        if (authenticating || isFinishing || isDestroyed) return
        if (!AppVaultLock.isEnabled(this)) {
            createPin()
            return
        }
        val biometricReady = !forcePin &&
            ProtectedAppsStore.biometricEnabled(this) &&
            VaultSecuritySettings.canUseBiometrics(this)
        if (biometricReady) showBiometricPrompt() else showPinPrompt()
    }

    private fun createPin() {
        authenticating = true
        PinPadDialog.showCreate(
            activity = this,
            title = "Criar PIN dos apps",
            onCreated = { pin ->
                authenticating = false
                AppVaultLock.setPin(this, pin)
                Haptics.success(this)
                Toast.makeText(this, "PIN dos apps protegido e salvo", Toast.LENGTH_SHORT).show()
                renderSecurityState()
            },
            onCancel = {
                authenticating = false
                renderSecurityState()
            }
        )
    }

    private fun showPinPrompt() {
        authenticating = true
        PinPadDialog.showVerify(
            activity = this,
            title = "Desbloquear apps",
            subtitle = "Digite o PIN configurado para ver os atalhos protegidos.",
            verify = { AppVaultLock.verify(this, it) },
            onVerified = {
                authenticating = false
                renderSecurityState()
            },
            onCancel = {
                authenticating = false
                renderSecurityState()
            }
        )
    }

    private fun showBiometricPrompt() {
        authenticating = true
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricPrompt = null
                    authenticating = false
                    AppVaultLock.unlockSession()
                    Haptics.success(this@ProtectedAppsActivity)
                    renderSecurityState()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricPrompt = null
                    authenticating = false
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        grid.post { requestUnlock(forcePin = true) }
                    } else {
                        renderSecurityState()
                    }
                }

                override fun onAuthenticationFailed() {
                    Haptics.error(this@ProtectedAppsActivity)
                }
            }
        )
        biometricPrompt = prompt
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear apps")
                .setSubtitle("Use sua biometria para continuar")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Usar PIN")
                .build()
        )
    }

    private fun loadLauncherApps(force: Boolean) {
        if (!AppVaultLock.isUnlocked(this) || loader.isShutdown) return
        if (!force && allApps.isNotEmpty()) {
            renderApps()
            if (allAppsComplete || loadInProgress) return
        }
        if (!force) {
            val cached = cachedLauncherApps
            val cacheFresh = cached != null &&
                SystemClock.elapsedRealtime() - launcherAppsCachedAt <= APPS_CACHE_TTL_MS
            if (cacheFresh) {
                allApps = cached.orEmpty()
                allAppsComplete = true
                renderApps()
                return
            }
            val persisted = ProtectedAppsIndexStore.read(this)
            if (persisted.entries.isNotEmpty()) {
                allApps = persisted.entries.map { LauncherApp(it.packageName, it.label) }
                allAppsComplete = true
                renderApps()
                if (System.currentTimeMillis() - persisted.savedAtMs <= APPS_CACHE_TTL_MS) {
                    cachedLauncherApps = allApps
                    launcherAppsCachedAt = SystemClock.elapsedRealtime()
                    return
                }
            }
        }
        if (loadInProgress) return

        val generation = ++loadGeneration
        val selectedPackages = ProtectedAppsStore.selectedPackages(this)
        loadInProgress = true
        if (managing) {
            setBusy(true, "Carregando apps da tela inicial…")
        } else {
            setBusy(false)
            summary.text = "Preparando atalhos protegidos…"
        }
        runCatching {
            loader.execute {
                if (!managing && selectedPackages.isNotEmpty()) {
                    val selectedResult = runCatching { querySelectedLauncherApps(selectedPackages) }
                    runOnUiThread {
                        if (generation == loadGeneration &&
                            !isFinishing &&
                            !isDestroyed &&
                            AppVaultLock.isUnlocked(this)
                        ) {
                            selectedResult.onSuccess {
                                if (allApps.isEmpty()) {
                                    allApps = it
                                    renderApps()
                                }
                            }
                        }
                    }
                }
                val result = runCatching { queryLauncherApps() }
                result.getOrNull()?.let {
                    cachedLauncherApps = it
                    launcherAppsCachedAt = SystemClock.elapsedRealtime()
                    ProtectedAppsIndexStore.write(
                        this,
                        it.map { app -> ProtectedAppsIndexStore.Entry(app.packageName, app.label) }
                    )
                }
                runOnUiThread {
                    if (generation != loadGeneration || isFinishing || isDestroyed || !AppVaultLock.isUnlocked(this)) {
                        if (generation == loadGeneration) loadInProgress = false
                        return@runOnUiThread
                    }
                    loadInProgress = false
                    setBusy(false)
                    result.onSuccess {
                        allApps = it
                        allAppsComplete = true
                        renderApps()
                    }.onFailure {
                        if (allApps.isEmpty()) allApps = emptyList()
                        renderApps()
                        Toast.makeText(this, it.message ?: "Falha ao carregar os apps", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure {
            if (generation == loadGeneration) {
                loadInProgress = false
                setBusy(false)
            }
            Toast.makeText(this, it.message ?: "Falha ao iniciar a leitura dos apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryLauncherApps(): List<LauncherApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        return resolved.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == applicationContext.packageName) return@mapNotNull null
                packageName to info
            }
            .distinctBy { it.first }
            .map { (packageName, info) ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                    .ifBlank { packageName }
                LauncherApp(packageName, label)
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun querySelectedLauncherApps(packages: Set<String>): List<LauncherApp> =
        packages.asSequence()
            .mapNotNull { packageName ->
                if (packageName == applicationContext.packageName ||
                    packageManager.getLaunchIntentForPackage(packageName) == null
                ) return@mapNotNull null
                val info = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getApplicationInfo(
                            packageName,
                            PackageManager.ApplicationInfoFlags.of(0L)
                        )
                    } else {
                        packageManager.getApplicationInfo(packageName, 0)
                    }
                }.getOrNull() ?: return@mapNotNull null
                val label = packageManager.getApplicationLabel(info).toString().trim()
                    .ifBlank { packageName }
                LauncherApp(packageName, label)
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()

    private fun renderApps() {
        if (!AppVaultLock.isUnlocked(this)) return
        val selected = ProtectedAppsStore.selectedPackages(this)
        val visible = if (managing) allApps else allApps.filter { it.packageName in selected }
        adapter.submit(visible, selected, managing)
        manageButton.text = if (managing) "Concluir" else "Adicionar/remover"
        summary.text = if (managing) {
            "Toque em ADICIONAR ou REMOVER • ${selected.size} protegido(s)"
        } else {
            "${visible.size} app(s) protegido(s) • somente do perfil normal"
        }
        empty.text = if (managing) {
            "Nenhum aplicativo lançável foi encontrado."
        } else {
            "Nenhum app adicionado. Toque em Adicionar/remover."
        }
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleProtectedApp(app: LauncherApp) {
        val selected = app.packageName in ProtectedAppsStore.selectedPackages(this)
        ProtectedAppsStore.setSelected(this, app.packageName, !selected)
        Haptics.tap(this)
        renderApps()
    }

    private fun launchApp(app: LauncherApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            ProtectedAppsStore.setSelected(this, app.packageName, false)
            Toast.makeText(this, "Este app não está mais disponível.", Toast.LENGTH_LONG).show()
            loadLauncherApps(force = true)
            return
        }
        runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            AppVaultLock.lock()
        }.onFailure {
            Haptics.error(this)
            Toast.makeText(this, it.message ?: "Não foi possível abrir ${app.label}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(active: Boolean, message: String = "Carregando apps…") {
        busyText.text = message
        busyOverlay.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun bindAppIcon(image: ImageView, packageName: String) {
        image.tag = packageName
        iconCache.get(packageName)?.let {
            image.alpha = 1f
            image.setImageDrawable(it)
            return
        }
        image.alpha = 0.38f
        image.setImageResource(R.drawable.ic_nav_apps)
        val shouldLoad = synchronized(iconRequests) { iconRequests.add(packageName) }
        if (!shouldLoad || iconLoader.isShutdown) return
        runCatching {
            iconLoader.execute {
                val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                synchronized(iconRequests) { iconRequests.remove(packageName) }
                if (icon == null || isFinishing || isDestroyed) return@execute
                iconCache.put(packageName, icon)
                runOnUiThread {
                    if (image.tag == packageName) {
                        image.alpha = 1f
                        image.setImageDrawable(icon)
                    }
                }
            }
        }.onFailure {
            synchronized(iconRequests) { iconRequests.remove(packageName) }
        }
    }

    private inner class AppsAdapter : BaseAdapter() {
        private var items = emptyList<LauncherApp>()
        private var selected = emptySet<String>()
        private var showState = false

        fun submit(items: List<LauncherApp>, selected: Set<String>, showState: Boolean) {
            this.items = items
            this.selected = selected
            this.showState = showState
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): LauncherApp? = items.getOrNull(position)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): LauncherApp = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_protected_app, parent, false).also {
                it.tag = AppViewHolder(
                    icon = it.findViewById(R.id.protectedAppIcon),
                    name = it.findViewById(R.id.protectedAppName),
                    state = it.findViewById(R.id.protectedAppState)
                )
            }
            val holder = view.tag as AppViewHolder
            val app = getItem(position)
            bindAppIcon(holder.icon, app.packageName)
            holder.name.text = app.label
            holder.state.visibility = if (showState) View.VISIBLE else View.GONE
            if (showState) {
                val isSelected = app.packageName in selected
                holder.state.text = if (isSelected) "− REMOVER" else "+ ADICIONAR"
                holder.state.setTextColor(if (isSelected) getColor(R.color.record_red) else AppearanceStore.palette(this@ProtectedAppsActivity).accent)
            }
            view.contentDescription = when {
                showState && app.packageName in selected -> "${app.label}, remover dos apps protegidos"
                showState -> "${app.label}, adicionar aos apps protegidos"
                else -> "${app.label}, abrir"
            }
            return view
        }
    }
}
