package com.steadyvault.camera.ui.vault

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.storage.vault.VaultMediaCacheSettings
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

abstract class PrivateVaultGalleryActivity : ComponentActivity() {
    protected abstract val vaultArea: String
    protected abstract val vaultTitle: String
    protected abstract val vaultPrefsName: String
    protected abstract val mediaPlayerVaultExtra: String
    protected abstract val videoOptimizationVaultExtra: String
    protected abstract fun isVaultUnlocked(): Boolean
    protected abstract fun unlockVaultSession()
    protected abstract fun lockVault()
    protected abstract fun listVaultAll(): List<VaultRepository.MediaItem>
    protected abstract fun vaultUsedBytes(): Long
    protected abstract fun deletePermanently(item: VaultRepository.MediaItem): Boolean
    protected abstract fun exportToGallery(item: VaultRepository.MediaItem): android.net.Uri

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var busyOverlay: View
    private lateinit var importPanel: View
    private lateinit var importProgressText: TextView
    private lateinit var importCancelButton: TextView
    private lateinit var busyText: TextView
    private lateinit var adapter: GalleryAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-PrivateVaultIo")
    }
    private val detailsExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-PrivateVaultDetails")
    }
    private val metadataExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "SteadyVault-PrivateVaultMetadata")
    }
    private val visibleMetadataExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "SteadyVault-PrivateVisibleMetadata")
    }
    private val visibleMetadataRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val selectedPaths = linkedSetOf<String>()
    private val allItems = mutableListOf<VaultRepository.MediaItem>()
    @Volatile private var refreshGeneration = 0
    private var totalMediaCount = 0
    private var typeFilter = MediaTypeFilter.ALL
    private var periodFilter = PeriodFilter.ALL
    private var sortMode = SortMode.NEWEST
    private var gridColumns = VaultGridRules.DEFAULT_COLUMNS
    private val importSession = VaultImportSession()
    private var importWasRunning = false
    private val importMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val trustedReturn = importSession.consume()
        if (trustedReturn) unlockVaultSession()
        when {
            !trustedReturn -> finish()
            result.resultCode != android.app.Activity.RESULT_OK -> refresh()
            else -> {
                val uris = VaultImportUtils.selectedDocumentUris(result.data)
                VaultImportUtils.persistDocumentReadPermissions(this, result.data, uris)
                if (uris.isEmpty()) refresh() else importMediaBatch(uris, "arquivo(s)")
            }
        }
    }
    private val importFolderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val trustedReturn = importSession.consume()
        if (trustedReturn) unlockVaultSession()
        val treeUri = result.data?.data
        when {
            !trustedReturn -> finish()
            result.resultCode != android.app.Activity.RESULT_OK || treeUri == null -> refresh()
            else -> importFolder(treeUri, result.data)
        }
    }
    private val importStatusRefresh = object : Runnable {
        override fun run() {
            val state = VaultBulkImportRunner.snapshot(this@PrivateVaultGalleryActivity, vaultArea)
            val wasRunning = importWasRunning
            importWasRunning = state.running || VaultImportQueueStore.hasPending(this@PrivateVaultGalleryActivity, vaultArea)
            renderImportState(showPopup = isVaultUnlocked())
            if (importWasRunning) {
                mainHandler.postDelayed(this, IMPORT_STATUS_REFRESH_MS)
            } else if (wasRunning && isVaultUnlocked()) {
                mainHandler.postDelayed({ refresh() }, 120L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeGallery()
        })
        if (!isVaultUnlocked()) {
            finish()
            return
        }
        setContentView(R.layout.activity_private_vault)
        findViewById<TextView>(R.id.privateGalleryTitle).text = vaultTitle
        SystemBarInsets.applyTopAndBottom(findViewById(R.id.privateGalleryRoot))
        grid = findViewById(R.id.privateGalleryGrid)
        empty = findViewById(R.id.privateGalleryEmpty)
        summary = findViewById(R.id.privateGallerySummary)
        busyOverlay = findViewById(R.id.privateBusyOverlay)
        importPanel = findViewById(R.id.privateImportPanel)
        importProgressText = findViewById(R.id.privateImportProgressText)
        importCancelButton = findViewById(R.id.privateImportCancelButton)
        importCancelButton.setOnClickListener {
            VaultBulkImportRunner.cancel(this, vaultArea)
            importProgressText.text = "Cancelando com segurança após o arquivo atual…"
            importCancelButton.isEnabled = false
            importCancelButton.alpha = 0.5f
        }
        busyText = findViewById(R.id.privateBusyText)
        getSharedPreferences(vaultPrefsName, MODE_PRIVATE).also { prefs ->
            gridColumns = prefs.getInt(KEY_GRID_COLUMNS, VaultGridRules.DEFAULT_COLUMNS)
            typeFilter = MediaTypeFilter.from(prefs.getString(KEY_TYPE_FILTER, null))
            periodFilter = PeriodFilter.from(prefs.getString(KEY_PERIOD_FILTER, null))
            sortMode = SortMode.from(prefs.getString(KEY_SORT_MODE, null))
        }
        adapter = GalleryAdapter { item -> showActions(item) }
        grid.adapter = adapter
        setGridColumns(gridColumns, persist = false)
        grid.isSmoothScrollbarEnabled = true
        grid.setRecyclerListener { recycled -> recycled.findViewById<ImageView>(R.id.mediaThumbnail)?.setImageDrawable(null) }
        grid.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (selectedPaths.isNotEmpty()) toggleSelection(item) else open(item)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            toggleSelection(adapter.getItem(position), forceSelect = true)
            Haptics.tap(this)
            true
        }
        findViewById<View>(R.id.privateGalleryAdd).setOnClickListener { openImporter() }
        findViewById<View>(R.id.privateGalleryType).setOnClickListener { chooseTypeFilter() }
        findViewById<View>(R.id.privateGalleryFilter).setOnClickListener { showFilterAndSortChooser() }
        findViewById<View>(R.id.privateGalleryGridSize).setOnClickListener { showGridSizeChooser() }
        findViewById<View>(R.id.privateGalleryTrash).setOnClickListener { startActivity(Intent(this, VaultTrashActivity::class.java)) }
        findViewById<View>(R.id.privateGallerySelectAll).setOnClickListener { selectAll() }
        findViewById<View>(R.id.privateGalleryDeleteSelected).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.privateGalleryClearSelection).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.privateGalleryClose).setOnClickListener { closeGallery() }
        updateSelectionUi()
    }

    override fun onStart() {
        super.onStart()
        if (::adapter.isInitialized) adapter.resumeLoading()
        scheduleImportStatusRefresh()
    }

    override fun onResume() {
        super.onResume()
        VaultBulkImportRunner.resumeIfPending(this, vaultArea)
        applySecurityFlag()
        if (importSession.isTrusted()) unlockVaultSession()
        if (!isVaultUnlocked()) finish() else {
            refresh()
            scheduleImportStatusRefresh()
            renderImportState(showPopup = true)
        }
    }

    override fun onStop() {
        releaseGalleryMemoryForViewer()
        mainHandler.removeCallbacks(importStatusRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        refreshGeneration++
        importSession.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        if (VaultBulkImportRunner.snapshot(this, vaultArea).running) ioExecutor.shutdown() else ioExecutor.shutdownNow()
        detailsExecutor.shutdownNow()
        metadataExecutor.shutdownNow()
        visibleMetadataExecutor.shutdownNow()
        visibleMetadataRequests.clear()
        if (::adapter.isInitialized) adapter.release()
        super.onDestroy()
    }

    private fun applySecurityFlag() {
        if (CaptureSettings.snapshot(this).secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun showGridSizeChooser() {
        val choices = (VaultGridRules.MIN_COLUMNS..VaultGridRules.MAX_COLUMNS).map { columns ->
            OneUiDialog.Choice(
                title = "$columns por linha",
                subtitle = when (columns) {
                    2 -> "Miniaturas maiores."
                    3 -> "Equilíbrio recomendado."
                    4 -> "Mostra mais mídias por tela."
                    else -> "Grade compacta para muitas mídias."
                }
            )
        }
        OneUiDialog.choices(
            activity = this,
            title = "Tamanho da grade",
            choices = choices,
            selectedIndex = gridColumns - VaultGridRules.MIN_COLUMNS
        ) { index -> setGridColumns(index + VaultGridRules.MIN_COLUMNS) }
    }

    private fun setGridColumns(columns: Int, persist: Boolean = true) {
        gridColumns = VaultGridRules.clamp(columns)
        if (::grid.isInitialized) {
            grid.numColumns = gridColumns
            grid.requestLayout()
        }
        updateGalleryLabels()
        if (persist) getSharedPreferences(vaultPrefsName, MODE_PRIVATE).edit().putInt(KEY_GRID_COLUMNS, gridColumns).apply()
    }

    private fun showFilterAndSortChooser() {
        OneUiDialog.choices(
            activity = this,
            title = "Filtros, ordenação e ferramentas",
            message = gallerySummaryText(),
            choices = listOf(
                OneUiDialog.Choice("Tipo: ${typeFilter.label}", "Todas, somente fotos ou somente vídeos."),
                OneUiDialog.Choice("Período: ${periodFilter.label}", "Hoje, últimos 7 dias, últimos 30 dias ou tudo."),
                OneUiDialog.Choice("Ordenar: ${sortMode.label}", "Data, nome, tamanho, duração ou resolução."),
                OneUiDialog.Choice("Limpar filtros", "Volta para todas as mídias, mais recentes primeiro."),
                OneUiDialog.Choice("Remover mídias duplicadas", "Verifica todo este cofre pelo conteúdo real, mantém uma cópia e envia somente as cópias idênticas para a lixeira.")
            )
        ) { option ->
            when (option) {
                0 -> chooseTypeFilter()
                1 -> choosePeriodFilter()
                2 -> chooseSortMode()
                3 -> {
                    typeFilter = MediaTypeFilter.ALL
                    periodFilter = PeriodFilter.ALL
                    sortMode = SortMode.NEWEST
                    clearSelection()
                    persistGalleryPrefs()
                    applyGalleryView()
                }
                4 -> removeVaultDuplicates()
            }
        }
    }

    private fun removeVaultDuplicates() {
        if (VaultBulkImportRunner.snapshot(this, vaultArea).running || VaultImportQueueStore.hasPending(this, vaultArea)) {
            OneUiDialog.message(this, "Importação em andamento", "Aguarde a importação terminar antes de remover duplicados. Assim nenhum arquivo é movido enquanto ainda está entrando no cofre.")
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Remover duplicados de $vaultTitle?",
            message = "O SteadyVault compara o conteúdo completo dos arquivos com SHA-256. Mantém a cópia mais antiga de cada mídia idêntica e envia somente as cópias extras para a lixeira, onde ainda podem ser restauradas.",
            positiveLabel = "Procurar e remover",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Analisando duplicados do cofre…")
            ioExecutor.execute {
                val result = runCatching {
                    VaultDuplicateCleaner.cleanVault(applicationContext, vaultArea) { progress ->
                        runOnUiThread { if (!isFinishing && !isDestroyed) updateBusy(progress.label()) }
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    result.onSuccess { cleaned ->
                        when {
                            cleaned.duplicatesFound == 0 -> {
                                Haptics.tap(this)
                                OneUiDialog.message(this, "Nenhuma duplicata encontrada", "Foram verificadas ${cleaned.checkedItems} mídia(s). Não há arquivos com conteúdo idêntico neste cofre.")
                            }
                            cleaned.failedToMove == 0 -> {
                                Haptics.success(this)
                                OneUiDialog.message(this, "Duplicados removidos", "${cleaned.movedToTrash} cópia(s) duplicada(s) foram movidas para a lixeira. Uma cópia de cada mídia foi mantida.")
                            }
                            else -> {
                                Haptics.error(this)
                                OneUiDialog.message(this, "Limpeza concluída com avisos", "Encontradas ${cleaned.duplicatesFound} cópia(s) duplicada(s). ${cleaned.movedToTrash} foram movidas para a lixeira e ${cleaned.failedToMove} não puderam ser movidas agora.")
                            }
                        }
                        refresh()
                    }.onFailure { error ->
                        Haptics.error(this)
                        Toast.makeText(this, error.message ?: "Não foi possível remover os duplicados", Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            }
        }
    }

    private fun chooseTypeFilter() {
        val values = MediaTypeFilter.values().toList()
        OneUiDialog.choices(this, "Tipo de mídia", choices = values.map { OneUiDialog.Choice(it.label) }, selectedIndex = values.indexOf(typeFilter)) {
            typeFilter = values[it]
            clearSelection()
            persistGalleryPrefs()
            applyGalleryView()
        }
    }

    private fun choosePeriodFilter() {
        val values = PeriodFilter.values().toList()
        OneUiDialog.choices(this, "Período", choices = values.map { OneUiDialog.Choice(it.label) }, selectedIndex = values.indexOf(periodFilter)) {
            periodFilter = values[it]
            clearSelection()
            persistGalleryPrefs()
            applyGalleryView()
        }
    }

    private fun chooseSortMode() {
        val values = SortMode.values().toList()
        OneUiDialog.choices(this, "Ordenar mídias", choices = values.map { OneUiDialog.Choice(it.label) }, selectedIndex = values.indexOf(sortMode)) {
            sortMode = values[it]
            persistGalleryPrefs()
            if (sortMode.requiresDetailedMetadata()) prepareDetailedSort() else {
                setBusy(false)
                applyGalleryView()
                grid.setSelection(0)
            }
        }
    }

    private fun prepareDetailedSort() {
        val requestedMode = sortMode
        val generation = refreshGeneration
        val missing = allItems.filter(requestedMode::needsDetailedMetadata)
        if (missing.isEmpty()) {
            applyGalleryView()
            grid.setSelection(0)
            return
        }
        setBusy(true, if (requestedMode == SortMode.DURATION_LONGEST || requestedMode == SortMode.DURATION_SHORTEST) "Lendo duração das mídias…" else "Lendo resolução das mídias…")
        runCatching {
            metadataExecutor.execute {
                val updated = HashMap<String, VaultRepository.MediaItem>(missing.size)
                missing.chunked(SORT_METADATA_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                    if (generation != refreshGeneration || requestedMode != sortMode || !isVaultUnlocked()) return@execute
                    batch.forEach { item ->
                        updated[item.file.absolutePath] = runCatching { VaultRepository.loadMediaDetails(this, item) }.getOrDefault(item)
                    }
                    val done = ((batchIndex + 1) * SORT_METADATA_BATCH_SIZE).coerceAtMost(missing.size)
                    runOnUiThread {
                        if (generation == refreshGeneration && requestedMode == sortMode && isVaultUnlocked()) updateBusy("Preparando ${requestedMode.label.lowercase()}… $done/${missing.size}")
                    }
                }
                runOnUiThread {
                    if (generation != refreshGeneration || requestedMode != sortMode || !isVaultUnlocked()) return@runOnUiThread
                    allItems.indices.forEach { index -> updated[allItems[index].file.absolutePath]?.let { allItems[index] = it } }
                    setBusy(false)
                    applyGalleryView()
                    grid.setSelection(0)
                }
            }
        }.onFailure {
            setBusy(false)
            applyGalleryView()
        }
    }

    private fun applyGalleryView() {
        val cutoff = periodFilter.cutoffMillis(System.currentTimeMillis())
        val visible = allItems.asSequence()
            .filter { typeFilter.matches(it) }
            .filter { cutoff == null || it.modifiedAt >= cutoff }
            .toMutableList()
        sortMode.sort(visible)
        updateGalleryLabels(visible.size)
        adapter.submit(visible)
        selectedPaths.retainAll(visible.mapTo(hashSetOf()) { it.file.absolutePath })
        updateSelectionUi()
        val importRunning = VaultBulkImportRunner.snapshot(this, vaultArea).running
        empty.visibility = if (visible.isEmpty() && !importRunning) View.VISIBLE else View.GONE
        grid.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        if (importRunning) renderImportState(showPopup = false)
    }

    private fun persistGalleryPrefs() {
        getSharedPreferences(vaultPrefsName, MODE_PRIVATE).edit()
            .putString(KEY_TYPE_FILTER, typeFilter.name)
            .putString(KEY_PERIOD_FILTER, periodFilter.name)
            .putString(KEY_SORT_MODE, sortMode.name)
            .apply()
    }

    private fun updateGalleryLabels(visibleCount: Int = adapter.count) {
        if (!::grid.isInitialized) return
        findViewById<TextView>(R.id.privateGalleryTypeLabel).text = when (typeFilter) {
            MediaTypeFilter.ALL -> "Mídia"
            MediaTypeFilter.PHOTOS -> "Fotos"
            MediaTypeFilter.VIDEOS -> "Vídeos"
        }
        findViewById<TextView>(R.id.privateGalleryFilterLabel).text = sortShortLabel(sortMode)
        findViewById<TextView>(R.id.privateGalleryGridSizeLabel).text = "Grade $gridColumns"
        findViewById<TextView>(R.id.privateGalleryTrashLabel).text = "Lixeira"
        findViewById<TextView>(R.id.privateGalleryViewSummary).text = "${visibleCount} mídia(s) • ${typeFilter.label.lowercase()} • ${sortMode.label.lowercase()}"
    }

    private fun sortShortLabel(mode: SortMode): String = when (mode) {
        SortMode.NEWEST -> "Recentes"
        SortMode.OLDEST -> "Antigas"
        SortMode.NAME_AZ -> "A-Z"
        SortMode.NAME_ZA -> "Z-A"
        SortMode.SIZE_LARGEST -> "Maior"
        SortMode.SIZE_SMALLEST -> "Menor"
        SortMode.DURATION_LONGEST -> "Duração ↓"
        SortMode.DURATION_SHORTEST -> "Duração ↑"
        SortMode.RESOLUTION_HIGHEST -> "Resolução ↓"
        SortMode.RESOLUTION_LOWEST -> "Resolução ↑"
    }

    private fun gallerySummaryText(): String = "${totalMediaCount.coerceAtLeast(allItems.size)} mídia(s) • ${typeFilter.label.lowercase()} • ${periodFilter.label.lowercase()} • ${sortMode.label.lowercase()}"

    private fun toggleSelection(item: VaultRepository.MediaItem, forceSelect: Boolean = false) {
        val path = item.file.absolutePath
        if (forceSelect || path !in selectedPaths) selectedPaths.add(path) else selectedPaths.remove(path)
        updateSelectionUi()
    }

    private fun selectAll() {
        setBusy(true, "Selecionando todas as mídias…")
        val currentType = typeFilter
        val cutoff = periodFilter.cutoffMillis(System.currentTimeMillis())
        ioExecutor.execute {
            val result = runCatching {
                listVaultAll().asSequence()
                    .filter { currentType.matches(it) }
                    .filter { cutoff == null || it.modifiedAt >= cutoff }
                    .mapTo(linkedSetOf()) { it.file.absolutePath }
            }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { all ->
                    val alreadyAll = all.isNotEmpty() && selectedPaths.size == all.size && selectedPaths.containsAll(all)
                    selectedPaths.clear()
                    if (!alreadyAll) selectedPaths.addAll(all)
                    updateSelectionUi()
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Não foi possível selecionar todas as mídias", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearSelection() {
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun setBusy(active: Boolean, message: String = "Processando…") {
        if (!::busyOverlay.isInitialized) return
        busyText.text = message
        busyOverlay.visibility = if (active) View.VISIBLE else View.GONE
        if (active && ::empty.isInitialized) empty.visibility = View.GONE
    }

    private fun updateBusy(message: String) {
        if (::busyOverlay.isInitialized && busyOverlay.visibility == View.VISIBLE) busyText.text = message
    }

    private fun setImportProgress(active: Boolean, message: String = "Importando…", canCancel: Boolean = true) {
        if (!::importPanel.isInitialized) return
        importPanel.visibility = if (active) View.VISIBLE else View.GONE
        importProgressText.text = message
        importCancelButton.visibility = if (active) View.VISIBLE else View.GONE
        importCancelButton.isEnabled = active && canCancel
        importCancelButton.alpha = if (active && canCancel) 1f else 0.5f
    }


    private fun scheduleImportStatusRefresh() {
        mainHandler.removeCallbacks(importStatusRefresh)
        mainHandler.post(importStatusRefresh)
    }

    private fun renderImportState(showPopup: Boolean = true) {
        val state = VaultBulkImportRunner.snapshot(this, vaultArea)
        if (state.running) {
            summary.text = state.message
            setImportProgress(true, state.message, canCancel = !state.cancelRequested)
            if (::empty.isInitialized) empty.visibility = View.GONE
            return
        }
        if (VaultImportQueueStore.hasPending(this, vaultArea) && !state.cancelRequested) {
            VaultBulkImportRunner.resumeIfPending(this, vaultArea)
            val message = "Retomando fila de importação…"
            summary.text = message
            setImportProgress(true, message, canCancel = true)
            if (::empty.isInitialized) empty.visibility = View.GONE
            return
        }
        setImportProgress(false)
        if (showPopup && isVaultUnlocked()) {
            val message = VaultBulkImportRunner.consumeResult(this, vaultArea)
            if (message != null) {
                OneUiDialog.message(
                    activity = this,
                    title = if (state.failures > 0) "Importação concluída com falhas" else "Importação concluída",
                    message = message,
                    positiveLabel = "Ver cofre"
                )
            }
        }
    }

    private fun updateSelectionUi() {
        val active = selectedPaths.isNotEmpty()
        findViewById<View>(R.id.privateGridControls).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.privateSelectionActions).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.privateSelectionCount).text = when (selectedPaths.size) {
            1 -> "1 selecionada"
            else -> "${selectedPaths.size} selecionadas"
        }
        findViewById<TextView>(R.id.privateGallerySelectAllLabel).text = "Tudo"
        adapter.updateSelection(selectedPaths, active)
        summary.text = if (active) "${selectedPaths.size} selecionada(s)" else gallerySummaryText()
    }

    private fun deleteSelected() {
        resolveSelectedItems("Preparando ${selectedPaths.size} mídia(s)…") { items ->
            if (items.isEmpty()) return@resolveSelectedItems
            OneUiDialog.choices(
                activity = this,
                title = "Excluir ${items.size} mídia(s)",
                message = "Escolha se quer mandar para a lixeira ou apagar direto sem recuperação.",
                choices = listOf(
                    OneUiDialog.Choice("Mover para a lixeira", "Pode restaurar depois pela Lixeira dos cofres.", destructive = true),
                    OneUiDialog.Choice("Excluir direto", "Apaga permanentemente sem passar pela lixeira.", destructive = true)
                )
            ) { option -> if (option == 0) bulkMoveToTrash(items) else confirmDirectDelete(items) }
        }
    }

    private fun resolveSelectedItems(message: String, onReady: (List<VaultRepository.MediaItem>) -> Unit) {
        val paths = selectedPaths.toHashSet()
        if (paths.isEmpty()) return
        setBusy(true, message)
        ioExecutor.execute {
            val result = runCatching { listVaultAll().filter { it.file.absolutePath in paths } }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { items ->
                    if (items.isEmpty()) {
                        selectedPaths.clear()
                        updateSelectionUi()
                        Toast.makeText(this, "As mídias selecionadas não estão mais no cofre", Toast.LENGTH_LONG).show()
                    } else onReady(items)
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Não foi possível preparar a seleção", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bulkMoveToTrash(items: List<VaultRepository.MediaItem>) {
        clearSelection()
        setBusy(true, "Movendo 0/${items.size} para a lixeira…")
        summary.text = "Movendo para a lixeira…"
        ioExecutor.execute {
            var moved = 0
            items.forEachIndexed { index, item ->
                if (VaultTrashRepository.moveToTrash(this, item) != null) moved++
                val done = index + 1
                if (done == items.size || done % BULK_PROGRESS_STEP == 0) {
                    runOnUiThread { updateBusy("Movendo $done/${items.size} • OK $moved") }
                }
            }
            runOnUiThread {
                if (moved > 0) Haptics.stop(this) else Haptics.error(this)
                Toast.makeText(this, "$moved mídia(s) movida(s) para a lixeira", Toast.LENGTH_LONG).show()
                setBusy(false)
                refresh()
            }
        }
    }

    private fun confirmDirectDelete(items: List<VaultRepository.MediaItem>) {
        OneUiDialog.confirm(
            activity = this,
            title = "Excluir direto?",
            message = "${items.size} mídia(s) serão apagadas permanentemente, sem ir para a lixeira.",
            positiveLabel = "Excluir direto",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Excluindo 0/${items.size} definitivamente…")
            summary.text = "Excluindo direto…"
            ioExecutor.execute {
                var deleted = 0
                items.forEachIndexed { index, item ->
                    if (deletePermanently(item)) {
                        deleted++
                        adapter.removeThumbnail(item.file)
                    }
                    val done = index + 1
                    if (done == items.size || done % BULK_PROGRESS_STEP == 0) {
                        runOnUiThread { updateBusy("Excluindo $done/${items.size} • OK $deleted") }
                    }
                }
                runOnUiThread {
                    if (deleted > 0) Haptics.stop(this) else Haptics.error(this)
                    Toast.makeText(this, "$deleted mídia(s) excluída(s) definitivamente", Toast.LENGTH_LONG).show()
                    setBusy(false)
                    refresh()
                }
            }
        }
    }

    private fun openImporter() {
        OneUiDialog.choices(
            activity = this,
            title = "Adicionar ao $vaultTitle",
            message = "Para muitos arquivos, escolha uma pasta. A cópia é sequencial para não travar nem estourar memória.",
            choices = listOf(
                OneUiDialog.Choice("Selecionar arquivos", "Permite marcar vários vídeos e fotos de uma vez."),
                OneUiDialog.Choice("Selecionar uma pasta", "Importa fotos e vídeos da pasta escolhida, inclusive subpastas.")
            )
        ) { option -> if (option == 0) openFileImporter() else openFolderImporter() }
    }

    private fun openFileImporter() {
        importSession.begin()
        runCatching { importMediaLauncher.launch(VaultImportUtils.openFilesIntent()) }
            .onFailure {
                importSession.cancel()
                Toast.makeText(this, "Não foi possível abrir o seletor de mídia", Toast.LENGTH_LONG).show()
            }
    }

    private fun openFolderImporter() {
        importSession.begin()
        runCatching { importFolderLauncher.launch(VaultImportUtils.openFolderIntent()) }
            .onFailure {
                importSession.cancel()
                Toast.makeText(this, "Não foi possível abrir o seletor de pasta", Toast.LENGTH_LONG).show()
            }
    }

    private fun importFolder(treeUri: android.net.Uri, data: Intent?) {
        VaultImportUtils.persistTreeReadPermission(this, data, treeUri)
        setImportProgress(true, "Lendo pasta…")
        summary.text = "Lendo pasta…"
        scheduleImportStatusRefresh()
        VaultBulkImportRunner.startTree(this, vaultArea, treeUri, "arquivo(s) da pasta")
    }

    private fun importMediaBatch(uris: List<android.net.Uri>, label: String) {
        if (uris.isEmpty()) {
            refresh()
            return
        }
        setImportProgress(true, "Importando 0/${uris.size} $label…")
        summary.text = "Importando 0/${uris.size} $label…"
        scheduleImportStatusRefresh()
        VaultBulkImportRunner.start(this, vaultArea, uris, label)
    }


    private fun releaseGalleryMemoryForViewer() {
        if (!::adapter.isInitialized) return
        adapter.pauseLoading()
        adapter.clearMemoryCache()
        if (::grid.isInitialized) {
            for (index in 0 until grid.childCount) {
                grid.getChildAt(index)?.findViewById<ImageView>(R.id.mediaThumbnail)?.setImageDrawable(null)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun open(item: VaultRepository.MediaItem) {
        releaseGalleryMemoryForViewer()
        startActivity(
            Intent(this, MediaPlayerActivity::class.java)
                .putExtra(MediaPlayerActivity.EXTRA_PATH, item.file.absolutePath)
                .putExtra(mediaPlayerVaultExtra, true)
        )
    }

    private fun showActions(item: VaultRepository.MediaItem) {
        prefetchMediaDetails(item)
        val choices = if (item.video) listOf(
            OneUiDialog.Choice("Abrir", "Visualizar esta mídia."),
            OneUiDialog.Choice("Detalhes", "Ver resolução, duração, tamanho e data."),
            OneUiDialog.Choice("Cortar/editar", "Abrir o player já no modo de corte com prévia."),
            OneUiDialog.Choice("Melhorar/otimizar", "Reparar fluidez, converter ou reduzir tamanho."),
            OneUiDialog.Choice("Exportar", "Criar uma cópia na galeria do aparelho."),
            OneUiDialog.Choice("Mover para a lixeira", "Pode restaurar depois.", destructive = true),
            OneUiDialog.Choice("Excluir direto", "Apaga permanentemente, sem lixeira.", destructive = true)
        ) else listOf(
            OneUiDialog.Choice("Abrir", "Visualizar esta mídia."),
            OneUiDialog.Choice("Detalhes", "Ver resolução, tamanho e data."),
            OneUiDialog.Choice("Exportar", "Criar uma cópia na galeria do aparelho."),
            OneUiDialog.Choice("Mover para a lixeira", "Pode restaurar depois.", destructive = true),
            OneUiDialog.Choice("Excluir direto", "Apaga permanentemente, sem lixeira.", destructive = true)
        )
        OneUiDialog.choices(
            activity = this,
            title = item.name,
            choices = choices
        ) { option ->
            if (item.video) {
                when (option) {
                    0 -> open(item)
                    1 -> showMediaDetails(item)
                    2 -> openTrimEditor(item)
                    3 -> openOptimization(item)
                    4 -> export(item)
                    5 -> bulkMoveToTrash(listOf(item))
                    6 -> confirmDirectDelete(listOf(item))
                }
            } else {
                when (option) {
                    0 -> open(item)
                    1 -> showMediaDetails(item)
                    2 -> export(item)
                    3 -> bulkMoveToTrash(listOf(item))
                    4 -> confirmDirectDelete(listOf(item))
                }
            }
        }
    }

    private fun prefetchMediaDetails(item: VaultRepository.MediaItem) {
        if (!VaultMediaCacheSettings.shouldPrefetchDetailsOnMenuOpen(this)) return
        runCatching {
            detailsExecutor.execute { VaultRepository.loadMediaDetails(applicationContext, item) }
        }
    }

    private fun showMediaDetails(item: VaultRepository.MediaItem) {
        VaultRepository.cachedMediaDetails(applicationContext, item)?.let { detailed ->
            OneUiDialog.message(this, "Detalhes da mídia", VaultRepository.mediaDetails(detailed))
            return
        }
        val progress = OneUiDialog.progress(this, "Detalhes da mídia", "Lendo informações…", cancelable = true)
        detailsExecutor.execute {
            val detailed = VaultRepository.loadMediaDetails(applicationContext, item)
            runOnUiThread {
                val requested = progress.isShowing()
                progress.dismiss()
                if (requested && !isFinishing && !isDestroyed && isVaultUnlocked()) {
                    OneUiDialog.message(this, "Detalhes da mídia", VaultRepository.mediaDetails(detailed))
                }
            }
        }
    }

    private fun openTrimEditor(item: VaultRepository.MediaItem) {
        releaseGalleryMemoryForViewer()
        startActivity(
            Intent(this, MediaPlayerActivity::class.java)
                .putExtra(MediaPlayerActivity.EXTRA_PATH, item.file.absolutePath)
                .putExtra(mediaPlayerVaultExtra, true)
                .putExtra(MediaPlayerActivity.EXTRA_START_TRIM, true)
        )
    }

    private fun openOptimization(item: VaultRepository.MediaItem) {
        adapter.pauseLoading()
        startActivity(
            Intent(this, VideoOptimizationActivity::class.java)
                .putExtra(VideoOptimizationActivity.EXTRA_PATH, item.file.absolutePath)
                .putExtra(videoOptimizationVaultExtra, true)
        )
    }

    private fun export(item: VaultRepository.MediaItem) {
        summary.text = "Exportando…"
        ioExecutor.execute {
            val result = runCatching { exportToGallery(item) }
            runOnUiThread {
                result.onSuccess {
                    Haptics.success(this)
                    Toast.makeText(this, "Cópia exportada", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Falha ao exportar", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun closeGallery() {
        lockVault()
        finish()
    }

    private fun refresh() {
        val restorePosition = if (::grid.isInitialized) grid.firstVisiblePosition else -1
        val restoreTop = if (::grid.isInitialized) grid.getChildAt(0)?.top ?: 0 else 0
        val generation = ++refreshGeneration
        summary.text = "Carregando…"
        setBusy(true, "Carregando mídias…")
        runCatching {
            ioExecutor.execute {
                val result = runCatching {
                    val items = listVaultAll()
                    InitialPage(items, items.size, vaultUsedBytes(), VaultTrashRepository.count(this))
                }
                runOnUiThread {
                    if (generation != refreshGeneration || !isVaultUnlocked()) return@runOnUiThread
                    setBusy(false)
                    result.onSuccess { page ->
                        totalMediaCount = page.total
                        allItems.clear()
                        allItems.addAll(page.items)
                        applyGalleryView()
                        if (restorePosition >= 0 && page.items.isNotEmpty()) grid.setSelectionFromTop(restorePosition.coerceAtMost(page.items.lastIndex), restoreTop)
                        findViewById<View>(R.id.privateGalleryTrash).contentDescription = if (page.trashCount == 0) "Lixeira" else "Lixeira, ${page.trashCount} item(ns)"
                        updateVaultSummary(page.bytes)
                        renderImportState(showPopup = true)
                        if (sortMode.requiresDetailedMetadata()) prepareDetailedSort()
                        else scheduleMetadataEnrichment(generation, page.items)
                    }.onFailure {
                        totalMediaCount = 0
                        allItems.clear()
                        applyGalleryView()
                        summary.text = "Não foi possível carregar as mídias"
                        Haptics.error(this)
                        Toast.makeText(this, it.message ?: "Falha ao carregar o $vaultTitle", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure {
            if (generation == refreshGeneration) setBusy(false)
            summary.text = "Não foi possível iniciar o carregamento"
        }
    }

    private fun updateVaultSummary(bytes: Long) {
        summary.text = "$totalMediaCount mídia(s) • ${VaultRepository.formatBytes(bytes)} • ${typeFilter.label.lowercase()} • ${sortMode.label.lowercase()}"
    }

    private fun requestVisibleMetadata(item: VaultRepository.MediaItem) {
        if (!item.video || (item.durationMs > 0L && item.width > 0 && item.height > 0)) return
        val generation = refreshGeneration
        val key = "${item.file.absolutePath}|${item.file.length()}|${item.file.lastModified()}"
        if (!visibleMetadataRequests.add(key)) return
        runCatching {
            visibleMetadataExecutor.execute {
                try {
                    if (generation != refreshGeneration || !isVaultUnlocked()) return@execute
                    val detailed = runCatching { VaultRepository.loadMediaDetails(applicationContext, item) }.getOrDefault(item)
                    if (detailed.durationMs <= 0L && detailed.width <= 0 && detailed.height <= 0) return@execute
                    runOnUiThread {
                        if (generation != refreshGeneration || !isVaultUnlocked()) return@runOnUiThread
                        val path = detailed.file.absolutePath
                        allItems.indices.forEach { index -> if (allItems[index].file.absolutePath == path) allItems[index] = detailed }
                        adapter.updateMetadata(mapOf(path to detailed))
                    }
                } finally {
                    visibleMetadataRequests.remove(key)
                }
            }
        }.onFailure { visibleMetadataRequests.remove(key) }
    }

    private fun scheduleMetadataEnrichment(generation: Int, snapshot: List<VaultRepository.MediaItem>) {
        val missing = snapshot.filter { it.video && (it.durationMs <= 0L || it.width <= 0 || it.height <= 0) }
        if (missing.isEmpty()) return
        runCatching {
            metadataExecutor.execute {
                val updated = snapshot.associateByTo(linkedMapOf()) { it.file.absolutePath }
                for (batch in missing.chunked(METADATA_BATCH_SIZE)) {
                    if (generation != refreshGeneration) return@execute
                    VaultRepository.enrichVideoMetadata(this, batch).forEach { updated[it.file.absolutePath] = it }
                    val enriched = snapshot.map { updated[it.file.absolutePath] ?: it }.associateBy { it.file.absolutePath }
                    runOnUiThread {
                        if (generation != refreshGeneration || !isVaultUnlocked()) return@runOnUiThread
                        allItems.indices.forEach { index -> enriched[allItems[index].file.absolutePath]?.let { allItems[index] = it } }
                        adapter.updateMetadata(enriched)
                    }
                }
            }
        }
    }

    private data class InitialPage(val items: List<VaultRepository.MediaItem>, val total: Int, val bytes: Long, val trashCount: Int)

    private data class Holder(
        val image: ImageView,
        val duration: TextView,
        val more: TextView,
        val overlay: View,
        val check: TextView
    )

    private inner class GalleryAdapter(private val onMore: (VaultRepository.MediaItem) -> Unit) :
        BaseAdapter(), MediaThumbnailRepository.MemoryCacheHandle {
        private val items = mutableListOf<VaultRepository.MediaItem>()
        private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
        init {
            MediaThumbnailRepository.registerMemoryCache(this)
        }
        private var executor: ExecutorService = newThumbnailExecutor()
        private val loading = Collections.synchronizedSet(mutableSetOf<String>())
        private val selected = hashSetOf<String>()
        private var selectionMode = false
        @Volatile private var released = false
        @Volatile private var loadingEnabled = true
        @Volatile private var loadingGeneration = 0L


        fun submit(value: List<VaultRepository.MediaItem>) {
            items.clear()
            items.addAll(value)
            notifyDataSetChanged()
        }

        fun updateMetadata(updated: Map<String, VaultRepository.MediaItem>) {
            var changed = false
            items.indices.forEach { index ->
                updated[items[index].file.absolutePath]?.let { replacement ->
                    if (items[index] != replacement) {
                        items[index] = replacement
                        changed = true
                    }
                }
            }
            if (changed) notifyDataSetChanged()
        }

        fun updateSelection(paths: Set<String>, active: Boolean) {
            selected.clear()
            selected.addAll(paths)
            selectionMode = active
            notifyDataSetChanged()
        }

        override fun removeThumbnail(file: File) { cache.snapshot().keys.filter { it.startsWith(file.absolutePath) }.forEach(cache::remove) }

        fun pauseLoading() {
            if (released || !loadingEnabled) return
            loadingEnabled = false
            loadingGeneration++
            executor.shutdownNow()
            loading.clear()
        }

        fun resumeLoading() {
            if (released) return
            if (executor.isShutdown || executor.isTerminated) executor = newThumbnailExecutor()
            loadingEnabled = true
            notifyDataSetChanged()
        }

        fun release() {
            released = true
            loadingEnabled = false
            loadingGeneration++
            executor.shutdownNow()
            loading.clear()
            MediaThumbnailRepository.unregisterMemoryCache(this)
            cache.evictAll()
        }

        override fun memoryBytes(): Long = cache.size().toLong() * 1024L

        override fun clearMemoryCache() {
            cache.evictAll()
        }

        private fun newThumbnailExecutor(): ExecutorService = Executors.newFixedThreadPool(THUMBNAIL_THREADS) { task ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "SteadyVault-PrivateVaultThumb")
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): VaultRepository.MediaItem = items[position]
        override fun getItemId(position: Int): Long = getItem(position).file.absolutePath.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: Holder
            if (convertView == null) {
                view = View.inflate(this@PrivateVaultGalleryActivity, R.layout.item_media_grid, null)
                holder = Holder(
                    view.findViewById(R.id.mediaThumbnail),
                    view.findViewById(R.id.mediaDuration),
                    view.findViewById(R.id.mediaMore),
                    view.findViewById(R.id.mediaSelectionOverlay),
                    view.findViewById(R.id.mediaSelectionCheck)
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as Holder
            }
            val item = getItem(position)
            val path = item.file.absolutePath
            val selectedNow = path in selected
            val durationLabel = if (item.video) VaultRepository.thumbnailDurationLabel(item) else ""
            holder.duration.visibility = if (durationLabel.isNotBlank()) View.VISIBLE else View.GONE
            holder.duration.text = durationLabel
            if (item.video && (durationLabel.isBlank() || item.width <= 0 || item.height <= 0)) requestVisibleMetadata(item)
            holder.more.visibility = if (selectionMode) View.GONE else View.VISIBLE
            holder.more.setOnClickListener { onMore(item) }
            holder.overlay.visibility = if (selectedNow) View.VISIBLE else View.GONE
            holder.check.visibility = if (selectedNow) View.VISIBLE else View.GONE
            val key = "$path:${item.file.lastModified()}:${item.file.length()}"
            holder.image.tag = key
            cache.get(key)?.takeIf { !it.isRecycled }?.let(holder.image::setImageBitmap) ?: run {
                holder.image.setImageDrawable(null)
                if (!released && loadingEnabled && loading.add(key)) {
                    val generation = loadingGeneration
                    (executor as? ThreadPoolExecutor)?.let { pool ->
                        if (pool.queue.size >= THUMBNAIL_BACKLOG_LIMIT) {
                            pool.queue.clear()
                            loading.clear()
                        }
                    }
                    runCatching {
                        executor.execute {
                            try {
                                if (!released && loadingEnabled && generation == loadingGeneration) {
                                    val bitmap = MediaThumbnailRepository.load(this@PrivateVaultGalleryActivity, item.file, item.video, THUMB_SIZE)
                                    if (!released && loadingEnabled && generation == loadingGeneration) {
                                        cache.put(key, bitmap)
                                        holder.image.post {
                                            if (!released && loadingEnabled && generation == loadingGeneration && holder.image.tag == key && !bitmap.isRecycled) {
                                                holder.image.setImageBitmap(bitmap)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                loading.remove(key)
                            }
                        }
                    }.onFailure { loading.remove(key) }
                }
            }
            return view
        }
    }

    companion object {
        private const val THUMB_SIZE = 320
        private const val THUMBNAIL_THREADS = 6
        private const val THUMBNAIL_BACKLOG_LIMIT = 48
        private const val IMPORT_STATUS_REFRESH_MS = 500L
        private const val BULK_PROGRESS_STEP = 25
        private const val METADATA_BATCH_SIZE = 8
        private const val SORT_METADATA_BATCH_SIZE = 32
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_TYPE_FILTER = "type_filter"
        private const val KEY_PERIOD_FILTER = "period_filter"
        private const val KEY_SORT_MODE = "sort_mode"
    }
}
