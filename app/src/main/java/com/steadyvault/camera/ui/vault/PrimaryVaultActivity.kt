package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.core.state.VideoProcessingStateStore
import com.steadyvault.camera.processing.service.VideoProcessingService
import com.steadyvault.camera.processing.auto.AutoGapRepairService
import com.steadyvault.camera.processing.auto.AutoGapRepairQueueStore
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.security.VaultSecuritySettings
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.storage.vault.VaultAlbumStore
import com.steadyvault.camera.storage.vault.VaultMediaCacheSettings
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.BottomNavigation
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import com.steadyvault.camera.ui.security.PinPadDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

class PrimaryVaultActivity : FragmentActivity() {
    private data class InitialVaultPage(
        val items: List<VaultRepository.MediaItem>,
        val total: Int,
        val usedBytes: Long,
        val trashCount: Int,
        val optimization: VideoProcessingStateStore.Snapshot
    )
    private lateinit var mediaGrid: GridView
    private lateinit var emptyText: TextView
    private lateinit var storageText: TextView
    private lateinit var lockButton: TextView
    private lateinit var gridControls: View
    private lateinit var selectionBar: View
    private lateinit var gridSizeButton: View
    private lateinit var gridSizeLabel: TextView
    private lateinit var galleryViewSummary: TextView
    private lateinit var albumFilterButton: View
    private lateinit var albumFilterLabel: TextView
    private lateinit var filterSortButton: View
    private lateinit var filterSortLabel: TextView
    private lateinit var trashButton: View
    private lateinit var trashLabel: TextView
    private lateinit var selectionCount: TextView
    private lateinit var lockedPanel: View
    private lateinit var biometricUnlockButton: View
    private lateinit var pinUnlockButton: View
    private lateinit var busyOverlay: View
    private lateinit var importPanel: View
    private lateinit var importProgressText: TextView
    private lateinit var importCancelButton: TextView
    private lateinit var busyText: TextView
    private lateinit var adapter: MediaAdapter
    private lateinit var scaleDetector: ScaleGestureDetector

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-VaultIo")
    }
    private val detailsExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-Details")
    }
    private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "SteadyVault-Metadata")
    }
    private val visibleMetadataExecutor: ExecutorService = Executors.newFixedThreadPool(2) { task ->
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }; task.run() }, "SteadyVault-VisibleMetadata")
    }
    private val visibleMetadataRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private val selectedPaths = linkedSetOf<String>()
    private val allItems = mutableListOf<VaultRepository.MediaItem>()
    private var unlocking = false
    private var switchingBiometricToPin = false
    private var pinUnlockDialogActive = false
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricVaultChooserDialog: Dialog? = null
    private var biometricAssociationDialog: Dialog? = null
    private var lastBiometricSuccessElapsedMs = 0L
    private var unlockTarget = UnlockTarget.PRIMARY
    private var skipAutomaticUnlockOnce = false
    @Volatile private var refreshGeneration = 0
    private var optimizationReceiverRegistered = false
    private var gridColumns = VaultGridRules.DEFAULT_COLUMNS
    private var accumulatedScale = 1f
    private var activeAlbumId: String? = null
    private var typeFilter = MediaTypeFilter.ALL
    private var periodFilter = PeriodFilter.ALL
    private var sortMode = SortMode.NEWEST
    private val importSession = VaultImportSession()
    private var importWasRunning = false
    private var pendingImportKeepDuplicates = false

    private val importMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val trustedReturn = importSession.consume()
        if (trustedReturn) PrimaryVaultLock.unlockSession()
        when {
            !trustedReturn -> showLockedState(requestUnlock = false)
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
        if (trustedReturn) PrimaryVaultLock.unlockSession()
        val treeUri = result.data?.data
        when {
            !trustedReturn -> showLockedState(requestUnlock = false)
            result.resultCode != android.app.Activity.RESULT_OK || treeUri == null -> refresh()
            else -> importFolder(treeUri, result.data)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val optimizationRefresh = object : Runnable {
        override fun run() {
            if (!PrimaryVaultLock.isUnlocked(this@PrimaryVaultActivity)) return
            val snapshot = VideoProcessingStateStore.snapshot(this@PrimaryVaultActivity)
            val autoJob = AutoGapRepairQueueStore.runningJob(this@PrimaryVaultActivity)
            when {
                snapshot.running -> updateOptimizationUi(snapshot)
                autoJob != null -> updateProcessingUi(autoJob.sourcePath, autoJob.progress, autoJob.message, "Reparo")
                else -> { refresh(); return }
            }
            mainHandler.postDelayed(this, OPTIMIZATION_REFRESH_MS)
        }
    }
    private val importStatusRefresh = object : Runnable {
        override fun run() {
            val state = VaultBulkImportRunner.snapshot(this@PrimaryVaultActivity, VaultAreaId.PRIMARY)
            val wasRunning = importWasRunning
            importWasRunning = state.running || VaultImportQueueStore.hasPending(this@PrimaryVaultActivity, VaultAreaId.PRIMARY)
            renderImportState(showPopup = PrimaryVaultLock.isUnlocked(this@PrimaryVaultActivity))
            if (importWasRunning) {
                mainHandler.postDelayed(this, IMPORT_STATUS_REFRESH_MS)
            } else if (wasRunning && PrimaryVaultLock.isUnlocked(this@PrimaryVaultActivity)) {
                mainHandler.postDelayed({ refresh() }, 120L)
            }
        }
    }
    private val optimizationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VideoProcessingService.ACTION_STATE -> {
                    val state = intent.getStringExtra(VideoProcessingService.EXTRA_STATE).orEmpty()
                    if (state == VideoProcessingService.STATE_PROGRESS) {
                        updateProcessingUi(
                            intent.getStringExtra(VideoProcessingService.EXTRA_SOURCE_PATH),
                            intent.getIntExtra(VideoProcessingService.EXTRA_PROGRESS, 0),
                            intent.getStringExtra(VideoProcessingService.EXTRA_MESSAGE).orEmpty(),
                            "Processamento"
                        )
                        scheduleOptimizationRefresh()
                    } else mainHandler.postDelayed({ refresh() }, 100L)
                }
                AutoGapRepairService.ACTION_STATE -> {
                    val running = intent.getBooleanExtra(AutoGapRepairService.EXTRA_RUNNING, false)
                    if (running) {
                        updateProcessingUi(
                            intent.getStringExtra(AutoGapRepairService.EXTRA_SOURCE_PATH),
                            intent.getIntExtra(AutoGapRepairService.EXTRA_PROGRESS, 0),
                            intent.getStringExtra(AutoGapRepairService.EXTRA_MESSAGE).orEmpty(),
                            "Reparo"
                        )
                        scheduleOptimizationRefresh()
                    } else mainHandler.postDelayed({ refresh() }, 100L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedPaths.isNotEmpty()) {
                    clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setContentView(R.layout.activity_primary_vault)
        SystemBarInsets.applyTop(findViewById<View>(R.id.libraryRoot))
        BottomNavigation.bind(
            activity = this,
            currentTab = BottomNavigation.TAB_LIBRARY,
            onBeforeNavigate = ::lockVaultForBottomNavigation
        )

        mediaGrid = findViewById(R.id.mediaGrid)
        emptyText = findViewById(R.id.emptyLibraryText)
        storageText = findViewById(R.id.storageSummaryText)
        lockButton = findViewById(R.id.lockVaultButton)
        gridControls = findViewById(R.id.gridControls)
        selectionBar = findViewById(R.id.selectionBar)
        gridSizeButton = findViewById(R.id.gridSizeButton)
        gridSizeLabel = findViewById(R.id.gridSizeLabel)
        galleryViewSummary = findViewById(R.id.galleryViewSummary)
        albumFilterButton = findViewById(R.id.albumFilterButton)
        albumFilterLabel = findViewById(R.id.albumFilterLabel)
        filterSortButton = findViewById(R.id.filterSortButton)
        filterSortLabel = findViewById(R.id.filterSortLabel)
        trashButton = findViewById(R.id.trashButton)
        trashLabel = findViewById(R.id.trashLabel)
        selectionCount = findViewById(R.id.selectionCount)
        lockedPanel = findViewById(R.id.lockedVaultPanel)
        biometricUnlockButton = findViewById(R.id.biometricUnlockButton)
        pinUnlockButton = findViewById(R.id.pinUnlockButton)
        busyOverlay = findViewById(R.id.vaultBusyOverlay)
        importPanel = findViewById(R.id.vaultImportPanel)
        importProgressText = findViewById(R.id.vaultImportProgressText)
        importCancelButton = findViewById(R.id.vaultImportCancelButton)
        importCancelButton.setOnClickListener {
            VaultBulkImportRunner.cancel(this, VaultAreaId.PRIMARY)
            importProgressText.text = "Cancelando com segurança após o arquivo atual…"
            importCancelButton.isEnabled = false
            importCancelButton.alpha = 0.5f
        }
        busyText = findViewById(R.id.vaultBusyText)

        adapter = MediaAdapter(this, { item -> showActions(item) }) { item -> requestVisibleMetadata(item) }
        mediaGrid.adapter = adapter
        mediaGrid.isSmoothScrollbarEnabled = true
        mediaGrid.setRecyclerListener { recycled ->
            recycled.findViewById<ImageView>(R.id.mediaThumbnail)?.setImageDrawable(null)
        }
        mediaGrid.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (selectedPaths.isNotEmpty()) toggleSelection(item) else openItem(item)
        }
        mediaGrid.setOnItemLongClickListener { _, _, position, _ ->
            toggleSelection(adapter.getItem(position), forceSelect = true)
            Haptics.tap(this)
            true
        }

        val galleryPrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val storedColumns = galleryPrefs.getInt(KEY_GRID_COLUMNS, VaultGridRules.DEFAULT_COLUMNS)
        val largerMigrationApplied = galleryPrefs.getBoolean(KEY_LARGER_THUMBNAILS_V144, false)
        gridColumns = if (!largerMigrationApplied && storedColumns >= 4) 3 else storedColumns
        if (!largerMigrationApplied) {
            galleryPrefs.edit()
                .putBoolean(KEY_LARGER_THUMBNAILS_V144, true)
                .putInt(KEY_GRID_COLUMNS, gridColumns)
                .apply()
        }
        activeAlbumId = galleryPrefs.getString(KEY_ACTIVE_ALBUM, null)
        typeFilter = MediaTypeFilter.from(galleryPrefs.getString(KEY_TYPE_FILTER, null))
        periodFilter = PeriodFilter.from(galleryPrefs.getString(KEY_PERIOD_FILTER, null))
        sortMode = SortMode.from(galleryPrefs.getString(KEY_SORT_MODE, null))
        setGridColumns(gridColumns, persist = false)
        configurePinchToResize()

        gridSizeButton.setOnClickListener { showGridSizeChooser() }
        albumFilterButton.setOnClickListener { showAlbumChooser() }
        filterSortButton.setOnClickListener { showFilterAndSortChooser() }
        trashButton.setOnClickListener { if (ensureUnlocked()) startActivity(Intent(this, VaultTrashActivity::class.java)) }
        findViewById<View>(R.id.selectAllButton).setOnClickListener { selectAll() }
        findViewById<View>(R.id.albumSelectedButton).setOnClickListener { assignSelectedToAlbum() }
        findViewById<View>(R.id.exportSelectedButton).setOnClickListener { exportSelected() }
        findViewById<View>(R.id.deleteSelectedButton).setOnClickListener { deleteSelected() }
        findViewById<TextView>(R.id.cancelSelectionButton).setOnClickListener { clearSelection() }
        findViewById<TextView>(R.id.importMediaButton).setOnClickListener {
            if (ensureUnlocked()) openImporter()
        }
        lockButton.setOnClickListener { handleLockAction() }
        biometricUnlockButton.setOnClickListener { requestUnlock() }
        pinUnlockButton.setOnClickListener { requestPinUnlock() }
        updateSelectionUi()
        updateLockUi()
    }

    override fun onStart() {
        super.onStart()
        adapter.resumeLoading()
        registerOptimizationReceiver()
        scheduleOptimizationRefresh()
        scheduleImportStatusRefresh()
    }

    override fun onStop() {
        releaseGalleryMemoryForViewer()
        mainHandler.removeCallbacks(optimizationRefresh)
        mainHandler.removeCallbacks(importStatusRefresh)
        unregisterOptimizationReceiver()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        VaultBulkImportRunner.resumeIfPending(this, VaultAreaId.PRIMARY)
        if (importSession.isTrusted()) PrimaryVaultLock.unlockSession()
        applySecurityFlag()
        updateLockUi()
        if (PrimaryVaultLock.isUnlocked(this)) {
            refresh()
        } else {
            val requestAutomatically = !skipAutomaticUnlockOnce
            skipAutomaticUnlockOnce = false
            showLockedState(requestUnlock = requestAutomatically)
        }
        scheduleImportStatusRefresh()
    }

    override fun onDestroy() {
        switchingBiometricToPin = false
        pinUnlockDialogActive = false
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        biometricVaultChooserDialog?.dismiss()
        biometricVaultChooserDialog = null
        biometricAssociationDialog?.dismiss()
        biometricAssociationDialog = null
        mainHandler.removeCallbacksAndMessages(null)
        refreshGeneration++
        importSession.cancel()
        if (VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY).running) ioExecutor.shutdown() else ioExecutor.shutdownNow()
        detailsExecutor.shutdownNow()
        metadataExecutor.shutdownNow()
        visibleMetadataExecutor.shutdownNow()
        visibleMetadataRequests.clear()
        adapter.release()
        super.onDestroy()
    }

    private fun configurePinchToResize() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulatedScale *= detector.scaleFactor
                val next = VaultGridRules.afterScale(gridColumns, accumulatedScale)
                if (next != gridColumns) {
                    setGridColumns(next)
                    accumulatedScale = 1f
                    Haptics.tap(this@PrimaryVaultActivity)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                accumulatedScale = 1f
            }
        })
        mediaGrid.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            scaleDetector.isInProgress || event.pointerCount > 1
        }
    }

    private fun showGridSizeChooser() {
        val choices = (VaultGridRules.MIN_COLUMNS..VaultGridRules.MAX_COLUMNS).map { columns ->
            OneUiDialog.Choice(
                title = "$columns por linha",
                subtitle = when (columns) {
                    2 -> "Miniaturas maiores e mais detalhes visuais."
                    3 -> "Equilíbrio recomendado entre tamanho e quantidade."
                    4 -> "Mostra mais mídias por tela."
                    else -> "Grade compacta para coleções grandes."
                }
            )
        }
        OneUiDialog.choices(
            activity = this,
            title = "Tamanho da grade",
            message = "Você também pode abrir ou fechar dois dedos diretamente sobre as mídias.",
            choices = choices,
            selectedIndex = gridColumns - VaultGridRules.MIN_COLUMNS
        ) { index -> setGridColumns(index + VaultGridRules.MIN_COLUMNS) }
    }

    private fun setGridColumns(columns: Int, persist: Boolean = true) {
        gridColumns = VaultGridRules.clamp(columns)
        mediaGrid.numColumns = gridColumns
        gridSizeLabel.text = "Grade $gridColumns"
        mediaGrid.requestLayout()
        adapter.notifyDataSetChanged()
        if (persist) getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putInt(KEY_GRID_COLUMNS, gridColumns).apply()
    }

    private fun showAlbumChooser() {
        val albums = VaultAlbumStore.albums(this)
        val choices = mutableListOf(
            OneUiDialog.Choice("Todas as mídias", "Mostra o cofre inteiro, inclusive itens sem álbum."),
            OneUiDialog.Choice("Sem álbum", "Mostra somente mídias que ainda não foram organizadas.")
        )
        choices += albums.map { album ->
            OneUiDialog.Choice(album.name, "Filtrar somente as mídias deste álbum.")
        }
        choices += OneUiDialog.Choice("Criar novo álbum", "Opcional: cria apenas uma organização, sem mover os arquivos.")
        if (activeAlbumId != null && activeAlbumId != ALBUM_UNASSIGNED) {
            choices += OneUiDialog.Choice("Gerenciar álbum atual", "Renomear ou excluir somente a organização.")
        }

        val selectedIndex = when (val id = activeAlbumId) {
            null -> 0
            ALBUM_UNASSIGNED -> 1
            else -> albums.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(2) ?: 0
        }
        OneUiDialog.choices(
            activity = this,
            title = "Álbuns",
            message = "Os álbuns são opcionais e existem somente no cofre principal.",
            choices = choices,
            selectedIndex = selectedIndex
        ) { index ->
            when {
                index == 0 -> setActiveAlbum(null)
                index == 1 -> setActiveAlbum(ALBUM_UNASSIGNED)
                index in 2 until albums.size + 2 -> setActiveAlbum(albums[index - 2].id)
                index == albums.size + 2 -> promptCreateAlbum { album -> setActiveAlbum(album.id) }
                else -> manageCurrentAlbum()
            }
        }
    }

    private fun setActiveAlbum(albumId: String?) {
        activeAlbumId = albumId
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            if (albumId == null) remove(KEY_ACTIVE_ALBUM) else putString(KEY_ACTIVE_ALBUM, albumId)
        }.apply()
        clearSelection()
        applyGalleryView()
    }

    private fun promptCreateAlbum(onCreated: (VaultAlbumStore.Album) -> Unit) {
        OneUiDialog.textInput(
            activity = this,
            title = "Criar álbum",
            message = "O álbum apenas organiza as mídias; os arquivos continuam no mesmo cofre privado.",
            hint = "Nome do álbum",
            positiveLabel = "Criar",
            validate = { value ->
                when {
                    value.isBlank() -> "Informe um nome para o álbum."
                    VaultAlbumStore.albums(this).any { it.name.equals(value, ignoreCase = true) } -> "Já existe um álbum com esse nome."
                    else -> null
                }
            }
        ) { value ->
            runCatching { VaultAlbumStore.create(this, value) }
                .onSuccess { album -> Haptics.success(this); onCreated(album) }
                .onFailure { Toast.makeText(this, it.message ?: "Não foi possível criar o álbum", Toast.LENGTH_LONG).show() }
        }
    }

    private fun manageCurrentAlbum() {
        val album = VaultAlbumStore.albums(this).firstOrNull { it.id == activeAlbumId } ?: return
        OneUiDialog.choices(
            activity = this,
            title = album.name,
            choices = listOf(
                OneUiDialog.Choice("Renomear álbum", "As mídias permanecem associadas ao mesmo álbum."),
                OneUiDialog.Choice("Remover mídias duplicadas", "Compara o conteúdo real, mantém uma cópia e envia somente as cópias idênticas para a lixeira."),
                OneUiDialog.Choice("Excluir álbum", "As mídias continuam no cofre e voltam para Todas as mídias.", destructive = true)
            )
        ) { option ->
            when (option) {
                0 -> promptRenameAlbum(album)
                1 -> removeCurrentAlbumDuplicates(album)
                else -> OneUiDialog.confirm(
                    activity = this,
                    title = "Excluir o álbum ${album.name}?",
                    message = "Nenhuma foto ou vídeo será apagado.",
                    positiveLabel = "Excluir álbum",
                    destructive = true
                ) {
                    VaultAlbumStore.delete(this, album.id)
                    setActiveAlbum(null)
                }
            }
        }
    }

    private fun removeVaultDuplicates() {
        if (VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY).running || VaultImportQueueStore.hasPending(this, VaultAreaId.PRIMARY)) {
            OneUiDialog.message(this, "Importação em andamento", "Aguarde a importação terminar antes de remover duplicados. Assim nenhum arquivo é movido enquanto ainda está entrando no cofre.")
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Remover duplicados do cofre principal?",
            message = "O SteadyVault compara o conteúdo completo dos arquivos com SHA-256. Mantém a cópia mais antiga de cada mídia idêntica e envia somente as cópias extras para a lixeira, onde ainda podem ser restauradas.",
            positiveLabel = "Procurar e remover",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Analisando duplicados do cofre…")
            ioExecutor.execute {
                val result = runCatching {
                    VaultDuplicateCleaner.cleanVault(applicationContext, VaultAreaId.PRIMARY) { progress ->
                        runOnUiThread { if (!isFinishing && !isDestroyed) updateBusy(progress.label()) }
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    result.onSuccess { cleaned ->
                        showVaultDuplicateResult(cleaned)
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

    private fun showVaultDuplicateResult(cleaned: VaultDuplicateCleaner.Result) {
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
    }

    private fun removeCurrentAlbumDuplicates(album: VaultAlbumStore.Album) {
        if (VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY).running || VaultImportQueueStore.hasPending(this, VaultAreaId.PRIMARY)) {
            OneUiDialog.message(this, "Importação em andamento", "Aguarde a importação terminar antes de remover duplicados deste álbum.")
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Remover duplicados de ${album.name}?",
            message = "O SteadyVault compara o conteúdo completo dos arquivos. Para cada mídia idêntica, mantém a cópia mais antiga e envia somente as cópias extras para a lixeira, onde ainda podem ser restauradas.",
            positiveLabel = "Procurar e remover",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Analisando duplicados do álbum…")
            ioExecutor.execute {
                val result = runCatching {
                    VaultDuplicateCleaner.cleanPrimaryAlbum(this, album.id) { progress ->
                        runOnUiThread { if (!isFinishing && !isDestroyed) updateBusy(progress.label()) }
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    result.onSuccess { cleaned ->
                        when {
                            cleaned.duplicatesFound == 0 -> {
                                Haptics.tap(this)
                                OneUiDialog.message(
                                    this,
                                    "Nenhuma duplicata encontrada",
                                    "Foram verificadas ${cleaned.checkedItems} mídia(s) do álbum. Não há arquivos com conteúdo idêntico para remover."
                                )
                            }
                            cleaned.failedToMove == 0 -> {
                                Haptics.success(this)
                                OneUiDialog.message(
                                    this,
                                    "Duplicados removidos",
                                    "${cleaned.movedToTrash} cópia(s) duplicada(s) foram movidas para a lixeira. Uma cópia de cada mídia foi mantida no álbum."
                                )
                            }
                            else -> {
                                Haptics.error(this)
                                OneUiDialog.message(
                                    this,
                                    "Limpeza concluída com avisos",
                                    "Encontradas ${cleaned.duplicatesFound} cópia(s) duplicada(s). ${cleaned.movedToTrash} foram movidas para a lixeira e ${cleaned.failedToMove} não puderam ser movidas agora."
                                )
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

    private fun promptRenameAlbum(album: VaultAlbumStore.Album) {
        OneUiDialog.textInput(
            activity = this,
            title = "Renomear álbum",
            initialValue = album.name,
            positiveLabel = "Salvar",
            validate = { value ->
                when {
                    value.isBlank() -> "Informe um nome para o álbum."
                    VaultAlbumStore.albums(this).any { it.id != album.id && it.name.equals(value, ignoreCase = true) } -> "Já existe um álbum com esse nome."
                    else -> null
                }
            }
        ) { value ->
            runCatching { VaultAlbumStore.rename(this, album.id, value) }
                .onSuccess { Haptics.success(this); applyGalleryView() }
                .onFailure { Toast.makeText(this, it.message ?: "Não foi possível renomear", Toast.LENGTH_LONG).show() }
        }
    }

    private fun assignSelectedToAlbum() {
        resolveSelectedItems("Preparando ${selectedPaths.size} mídia(s)…") { items -> showAssignAlbum(items) }
    }

    private fun showAssignAlbum(items: List<VaultRepository.MediaItem>) {
        val albums = VaultAlbumStore.albums(this)
        val choices = mutableListOf(OneUiDialog.Choice("Sem álbum", "Remove a organização sem apagar a mídia."))
        choices += albums.map { OneUiDialog.Choice(it.name, "Adicionar ou mover para este álbum.") }
        choices += OneUiDialog.Choice("Criar novo álbum", "Cria o álbum e adiciona as mídias selecionadas.")
        OneUiDialog.choices(
            activity = this,
            title = if (items.size == 1) "Organizar em álbum" else "Organizar ${items.size} mídias",
            choices = choices
        ) { index ->
            when {
                index == 0 -> assignToAlbum(items, null)
                index in 1..albums.size -> assignToAlbum(items, albums[index - 1].id)
                else -> promptCreateAlbum { album -> assignToAlbum(items, album.id) }
            }
        }
    }

    private fun assignToAlbum(items: List<VaultRepository.MediaItem>, albumId: String?) {
        VaultAlbumStore.assign(this, items.map { it.file }, albumId)
        clearSelection()
        Haptics.success(this)
        applyGalleryView()
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
                OneUiDialog.Choice("Remover mídias duplicadas", "Verifica todo o cofre pelo conteúdo real, mantém uma cópia e envia somente as cópias idênticas para a lixeira.")
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
                    persistViewPreferences()
                    applyGalleryView()
                }
                4 -> removeVaultDuplicates()
            }
        }
    }

    private fun chooseTypeFilter() {
        val values = MediaTypeFilter.values().toList()
        OneUiDialog.choices(
            activity = this,
            title = "Tipo de mídia",
            choices = values.map { OneUiDialog.Choice(it.label) },
            selectedIndex = values.indexOf(typeFilter)
        ) { index ->
            typeFilter = values[index]
            clearSelection()
            persistViewPreferences()
            applyGalleryView()
        }
    }

    private fun choosePeriodFilter() {
        val values = PeriodFilter.values().toList()
        OneUiDialog.choices(
            activity = this,
            title = "Período",
            choices = values.map { OneUiDialog.Choice(it.label) },
            selectedIndex = values.indexOf(periodFilter)
        ) { index ->
            periodFilter = values[index]
            clearSelection()
            persistViewPreferences()
            applyGalleryView()
        }
    }

    private fun chooseSortMode() {
        val values = SortMode.values().toList()
        OneUiDialog.choices(
            activity = this,
            title = "Ordenar mídias",
            choices = values.map { OneUiDialog.Choice(it.label) },
            selectedIndex = values.indexOf(sortMode)
        ) { index ->
            sortMode = values[index]
            persistViewPreferences()
            if (sortMode.requiresDetailedMetadata()) prepareDetailedSort() else {
                setBusy(false)
                applyGalleryView()
                mediaGrid.setSelection(0)
            }
        }
    }

    private fun prepareDetailedSort() {
        val requestedMode = sortMode
        val generation = refreshGeneration
        val missing = allItems.filter(requestedMode::needsDetailedMetadata)
        if (missing.isEmpty()) {
            applyGalleryView()
            mediaGrid.setSelection(0)
            return
        }
        setBusy(true, if (requestedMode == SortMode.DURATION_LONGEST || requestedMode == SortMode.DURATION_SHORTEST) "Lendo duração das mídias…" else "Lendo resolução das mídias…")
        runCatching {
            metadataExecutor.execute {
                val updated = HashMap<String, VaultRepository.MediaItem>(missing.size)
                missing.chunked(SORT_METADATA_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                    if (generation != refreshGeneration || requestedMode != sortMode || !PrimaryVaultLock.isUnlocked(this)) return@execute
                    batch.forEach { item ->
                        updated[item.file.absolutePath] = runCatching { VaultRepository.loadMediaDetails(this, item) }.getOrDefault(item)
                    }
                    val done = ((batchIndex + 1) * SORT_METADATA_BATCH_SIZE).coerceAtMost(missing.size)
                    runOnUiThread {
                        if (generation == refreshGeneration && requestedMode == sortMode && PrimaryVaultLock.isUnlocked(this)) updateBusy("Preparando ${requestedMode.label.lowercase()}… $done/${missing.size}")
                    }
                }
                runOnUiThread {
                    if (generation != refreshGeneration || requestedMode != sortMode || !PrimaryVaultLock.isUnlocked(this)) return@runOnUiThread
                    allItems.indices.forEach { index -> updated[allItems[index].file.absolutePath]?.let { allItems[index] = it } }
                    setBusy(false)
                    applyGalleryView()
                    mediaGrid.setSelection(0)
                }
            }
        }.onFailure {
            setBusy(false)
            applyGalleryView()
        }
    }

    private fun persistViewPreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_TYPE_FILTER, typeFilter.name)
            .putString(KEY_PERIOD_FILTER, periodFilter.name)
            .putString(KEY_SORT_MODE, sortMode.name)
            .apply()
    }

    private fun applyGalleryView(optimization: VideoProcessingStateStore.Snapshot = VideoProcessingStateStore.snapshot(this)) {
        if (!PrimaryVaultLock.isUnlocked(this)) return
        val albums = VaultAlbumStore.albums(this)
        if (activeAlbumId != null && activeAlbumId != ALBUM_UNASSIGNED && albums.none { it.id == activeAlbumId }) activeAlbumId = null
        val cutoff = periodFilter.cutoffMillis(System.currentTimeMillis())
        val albumAssignments = VaultAlbumStore.assignmentSnapshot(this, allItems.map { it.file })
        val visible = allItems.asSequence()
            .filter { item ->
                val itemAlbumId = albumAssignments[item.file.absolutePath]
                when (activeAlbumId) {
                    null -> true
                    ALBUM_UNASSIGNED -> itemAlbumId == null
                    else -> itemAlbumId == activeAlbumId
                }
            }
            .filter { item -> typeFilter.matches(item) }
            .filter { item -> cutoff == null || item.modifiedAt >= cutoff }
            .toMutableList()
        sortMode.sort(visible)
        adapter.submit(visible, optimization.sourcePath.takeIf { optimization.running }, optimization.progress)
        selectedPaths.retainAll(visible.mapTo(hashSetOf()) { it.file.absolutePath })
        updateSelectionUi()
        val albumName = when (activeAlbumId) {
            null -> "Todas as mídias"
            ALBUM_UNASSIGNED -> "Sem álbum"
            else -> albums.firstOrNull { it.id == activeAlbumId }?.name ?: "Todas as mídias"
        }
        val compactAlbum = when (activeAlbumId) {
            null -> "Todas"
            ALBUM_UNASSIGNED -> "Sem álbum"
            else -> albumName.take(11)
        }
        albumFilterLabel.text = compactAlbum
        val filtersAreDefault = typeFilter == MediaTypeFilter.ALL &&
            periodFilter == PeriodFilter.ALL && sortMode == SortMode.NEWEST
        filterSortLabel.text = if (filtersAreDefault) "Recentes" else "Filtros"
        val trashCount = VaultTrashRepository.count(this)
        trashLabel.text = "Lixeira"
                trashLabel.contentDescription = if (trashCount == 0) "Lixeira" else "Lixeira, $trashCount item(ns)"
        galleryViewSummary.text = gallerySummaryText(albumName)
        emptyText.text = if (allItems.isEmpty()) {
            "Nenhuma mídia no cofre.\nAs novas gravações ficarão aqui até você exportá-las."
        } else {
            "Nenhuma mídia corresponde aos filtros atuais."
        }
        val importRunning = VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY).running
        emptyText.visibility = if (visible.isEmpty() && !importRunning) View.VISIBLE else View.GONE
        mediaGrid.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        if (importRunning) renderImportState(showPopup = false)
    }

    private fun gallerySummaryText(albumName: String? = null): String {
        val album = albumName ?: when (activeAlbumId) {
            null -> "Todas as mídias"
            ALBUM_UNASSIGNED -> "Sem álbum"
            else -> VaultAlbumStore.albums(this).firstOrNull { it.id == activeAlbumId }?.name ?: "Todas as mídias"
        }
        return "$album • ${typeFilter.label.lowercase()} • ${periodFilter.label.lowercase()} • ${sortMode.label.lowercase()}"
    }

    private fun toggleSelection(item: VaultRepository.MediaItem, forceSelect: Boolean = false) {
        val path = item.file.absolutePath
        if (forceSelect || path !in selectedPaths) selectedPaths.add(path) else selectedPaths.remove(path)
        updateSelectionUi()
    }

    private fun selectAll() {
        setBusy(true, "Selecionando todas as mídias…")
        val albumId = activeAlbumId
        val currentType = typeFilter
        val cutoff = periodFilter.cutoffMillis(System.currentTimeMillis())
        ioExecutor.execute {
            val result = runCatching {
                val items = VaultRepository.list(this)
                val assignments = VaultAlbumStore.assignmentSnapshot(this, items.map { it.file })
                items.asSequence()
                    .filter { item ->
                        val itemAlbumId = assignments[item.file.absolutePath]
                        when (albumId) {
                            null -> true
                            ALBUM_UNASSIGNED -> itemAlbumId == null
                            else -> itemAlbumId == albumId
                        }
                    }
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
        if (active) emptyText.visibility = View.GONE
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
        val state = VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY)
        if (state.running) {
            storageText.text = state.message
            setImportProgress(true, state.message, canCancel = !state.cancelRequested)
            return
        }
        if (VaultImportQueueStore.hasPending(this, VaultAreaId.PRIMARY) && !state.cancelRequested) {
            VaultBulkImportRunner.resumeIfPending(this, VaultAreaId.PRIMARY)
            setImportProgress(true, "Retomando fila de importação…", canCancel = true)
            return
        }
        setImportProgress(false)
        if (showPopup && PrimaryVaultLock.isUnlocked(this)) {
            val message = VaultBulkImportRunner.consumeResult(this, VaultAreaId.PRIMARY)
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
        gridControls.visibility = if (active) View.GONE else View.VISIBLE
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        selectionCount.text = when (selectedPaths.size) {
            1 -> "1 selecionado"
            else -> "${selectedPaths.size} selecionados"
        }
        adapter.updateSelection(selectedPaths, active)
    }

    private fun exportSelected() {
        resolveSelectedItems("Preparando ${selectedPaths.size} mídia(s) para exportar…") { items ->
            clearSelection()
            setBusy(true, "Exportando 0/${items.size} mídia(s)…")
            storageText.text = "Exportando ${items.size} mídia(s)…"
            ioExecutor.execute {
                var exported = 0
                var failed = 0
                items.forEachIndexed { index, item ->
                    runCatching { VaultRepository.exportToGallery(this, item) }
                        .onSuccess { exported++ }
                        .onFailure { failed++ }
                    val done = index + 1
                    if (done == items.size || done % BULK_PROGRESS_STEP == 0) runOnUiThread { updateBusy("Exportando $done/${items.size} • OK $exported • falhas $failed") }
                }
                runOnUiThread {
                    if (exported > 0) Haptics.success(this) else Haptics.error(this)
                    val message = when {
                        failed == 0 -> "$exported mídia(s) exportada(s)"
                        exported == 0 -> "Não foi possível exportar as mídias"
                        else -> "$exported exportada(s) • $failed falha(s)"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    setBusy(false)
                    refresh()
                }
            }
        }
    }

    private fun deleteSelected() {
        resolveSelectedItems("Preparando ${selectedPaths.size} mídia(s)…") { items ->
            OneUiDialog.choices(
                activity = this,
                title = "Excluir ${items.size} mídia(s)",
                message = "Escolha se quer mandar para a lixeira ou apagar direto sem recuperação.",
                choices = listOf(
                    OneUiDialog.Choice("Mover para a lixeira", "Pode restaurar depois pela Lixeira dos cofres.", destructive = true),
                    OneUiDialog.Choice("Excluir direto", "Apaga permanentemente sem passar pela lixeira.", destructive = true)
                )
            ) { option ->
                if (option == 0) moveSelectedToTrash(items) else confirmDirectDeleteSelected(items)
            }
        }
    }

    private fun resolveSelectedItems(message: String, onReady: (List<VaultRepository.MediaItem>) -> Unit) {
        val paths = selectedPaths.toHashSet()
        if (paths.isEmpty()) return
        setBusy(true, message)
        ioExecutor.execute {
            val result = runCatching { VaultRepository.list(this).filter { it.file.absolutePath in paths } }
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

    private fun moveSelectedToTrash(items: List<VaultRepository.MediaItem>) {
        val processingCount = items.count {
            VideoProcessingStateStore.snapshotFor(this, it.file)?.running == true || VaultRepository.isBeingProcessed(it.file)
        }
        val safeItems = items.filterNot {
            VideoProcessingStateStore.snapshotFor(this, it.file)?.running == true || VaultRepository.isBeingProcessed(it.file)
        }
        if (safeItems.isEmpty()) {
            clearSelection()
            Toast.makeText(this, "Aguarde o processamento terminar", Toast.LENGTH_LONG).show()
            return
        }
        val extra = if (processingCount > 0) "\n\n$processingCount item(ns) em processamento serão preservados." else ""
        OneUiDialog.confirm(
            activity = this,
            title = "Mover ${safeItems.size} mídia(s) para a lixeira?",
            message = "As mídias continuarão privadas e poderão ser restauradas até a limpeza automática.$extra",
            positiveLabel = "Mover",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Movendo 0/${safeItems.size} para a lixeira…")
            storageText.text = "Movendo mídias para a lixeira…"
            ioExecutor.execute {
                var moved = 0
                safeItems.forEachIndexed { index, item ->
                    if (VaultTrashRepository.moveToTrash(this, item) != null) {
                        moved++
                        adapter.removeThumbnail(item.file)
                    }
                    val done = index + 1
                    if (done == safeItems.size || done % BULK_PROGRESS_STEP == 0) {
                        runOnUiThread { updateBusy("Movendo $done/${safeItems.size} • OK $moved") }
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
    }

    private fun confirmDirectDeleteSelected(items: List<VaultRepository.MediaItem>) {
        val safeItems = items.filterNot {
            VideoProcessingStateStore.snapshotFor(this, it.file)?.running == true || VaultRepository.isBeingProcessed(it.file)
        }
        if (safeItems.isEmpty()) {
            clearSelection()
            Toast.makeText(this, "Aguarde o processamento terminar", Toast.LENGTH_LONG).show()
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Excluir direto?",
            message = "${safeItems.size} mídia(s) serão apagadas permanentemente, sem ir para a lixeira.",
            positiveLabel = "Excluir direto",
            destructive = true
        ) {
            clearSelection()
            setBusy(true, "Excluindo 0/${safeItems.size} definitivamente…")
            storageText.text = "Excluindo direto…"
            ioExecutor.execute {
                var deleted = 0
                safeItems.forEachIndexed { index, item ->
                    if (VaultRepository.delete(this, item)) {
                        deleted++
                        adapter.removeThumbnail(item.file)
                    }
                    val done = index + 1
                    if (done == safeItems.size || done % BULK_PROGRESS_STEP == 0) {
                        runOnUiThread { updateBusy("Excluindo $done/${safeItems.size} • OK $deleted") }
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

    private fun lockVaultForBottomNavigation() {
        if (!PrimaryVaultLock.isEnabled(this)) return
        PrimaryVaultLock.lock()
        showLockedState(requestUnlock = false)
    }

    private fun handleLockAction() {
        Haptics.tap(this)
        when {
            !PrimaryVaultLock.isEnabled(this) -> PinPadDialog.showCreate(
                activity = this,
                onCreated = { pin ->
                    PrimaryVaultLock.setPin(this, pin)
                    applySecurityFlag()
                    updateLockUi()
                    Haptics.success(this)
                    Toast.makeText(this, "PIN criado. O cofre continua desbloqueado nesta sessão.", Toast.LENGTH_LONG).show()
                }
            )
            PrimaryVaultLock.isUnlocked(this) -> {
                PrimaryVaultLock.lock()
                clearSelection()
                showLockedState(requestUnlock = false)
                Toast.makeText(this, "Cofre bloqueado", Toast.LENGTH_SHORT).show()
            }
            else -> requestUnlock()
        }
    }

    private fun ensureUnlocked(): Boolean {
        if (PrimaryVaultLock.isUnlocked(this)) return true
        requestUnlock()
        return false
    }

    private fun requestUnlock(forcePin: Boolean = false) {
        if (!PrimaryVaultLock.isEnabled(this)) return
        if (forcePin) {
            requestPinUnlock()
            return
        }
        if (unlocking || biometricPrompt != null || biometricVaultChooserDialog?.isShowing == true || biometricAssociationDialog?.isShowing == true) return
        unlocking = true
        unlockTarget = UnlockTarget.PRIMARY
        val useBiometric = VaultSecuritySettings.biometricEnabled(this) &&
            VaultSecuritySettings.canUseBiometrics(this)
        if (useBiometric) showBiometricUnlock() else showPinUnlockOnce()
    }

    private fun requestPinUnlock() {
        if (!PrimaryVaultLock.isEnabled(this) || pinUnlockDialogActive) return
        if (biometricPrompt != null) {
            switchingBiometricToPin = true
            biometricPrompt?.cancelAuthentication()
            mainHandler.postDelayed({
                if (switchingBiometricToPin && !isFinishing && !isDestroyed) {
                    switchingBiometricToPin = false
                    biometricPrompt = null
                    showPinUnlockOnce()
                }
            }, BIOMETRIC_CANCEL_FALLBACK_MS)
            return
        }
        if (!unlocking) {
            unlocking = true
            unlockTarget = UnlockTarget.PRIMARY
        }
        showPinUnlockOnce()
    }

    private fun showBiometricUnlock() {
        if (biometricPrompt != null || biometricVaultChooserDialog?.isShowing == true || biometricAssociationDialog?.isShowing == true) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val now = SystemClock.elapsedRealtime()
                    if (biometricVaultChooserDialog?.isShowing == true || biometricAssociationDialog?.isShowing == true ||
                        now - lastBiometricSuccessElapsedMs < BIOMETRIC_SUCCESS_DEBOUNCE_MS
                    ) return
                    lastBiometricSuccessElapsedMs = now
                    biometricPrompt = null
                    switchingBiometricToPin = false
                    // Mostra o destino imediatamente; a vibração não fica no caminho crítico visual.
                    showBiometricVaultChooser()
                    mainHandler.post { if (!isFinishing && !isDestroyed) Haptics.success(this@PrimaryVaultActivity) }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricPrompt = null
                    val openPin = switchingBiometricToPin ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    switchingBiometricToPin = false
                    if (openPin) {
                        mainHandler.post { showPinUnlockOnce() }
                    } else {
                        unlocking = false
                        showLockedState(requestUnlock = false)
                    }
                }

                override fun onAuthenticationFailed() {
                    Haptics.error(this@PrimaryVaultActivity)
                }
            }
        )
        biometricPrompt = prompt
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirmar identidade")
            .setSubtitle("Use a biometria para continuar")
            .setDescription("O PIN está disponível como alternativa.")
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText("Usar PIN")
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun showBiometricVaultChooser() {
        if (isFinishing || isDestroyed) { unlocking = false; return }
        if (biometricVaultChooserDialog?.isShowing == true || biometricAssociationDialog?.isShowing == true) return
        unlocking = false
        val targets = availableBiometricTargets()
        val preferred = biometricTargetFromPreference(VaultSecuritySettings.biometricVaultTarget(this))
        if (preferred != null && preferred in targets) {
            openVaultAfterBiometric(preferred)
            return
        }
        if (targets.size == 1) {
            openVaultAfterBiometric(targets.first())
            return
        }
        val choices = targets.map(::biometricChoice) + OneUiDialog.Choice(
            "Lembrar um cofre",
            "Associar a biometria para abrir automaticamente nas próximas vezes."
        )
        val dialog = OneUiDialog.choices(
            activity = this,
            title = "Acesso confirmado",
            message = "Escolha onde entrar agora. Esta tela só aparece depois da biometria.",
            choices = choices
        ) { position ->
            biometricVaultChooserDialog = null
            if (position < targets.size) openVaultAfterBiometric(targets[position])
            else showBiometricAssociationChooser(targets)
        }
        biometricVaultChooserDialog = dialog
        dialog.setOnDismissListener {
            if (biometricVaultChooserDialog === dialog) biometricVaultChooserDialog = null
        }
    }

    private fun showBiometricAssociationChooser(targets: List<UnlockTarget>) {
        val choices = listOf(
            OneUiDialog.Choice("Perguntar sempre", "Mantém a escolha após cada confirmação biométrica.")
        ) + targets.map { target ->
            val choice = biometricChoice(target)
            OneUiDialog.Choice(choice.title, "Abrir automaticamente este cofre após a biometria.")
        }
        if (biometricAssociationDialog?.isShowing == true) return
        val dialog = OneUiDialog.choices(
            activity = this,
            title = "Associar biometria",
            message = "A preferência pode ser alterada depois nos ajustes.",
            choices = choices
        ) { position ->
            biometricAssociationDialog = null
            if (position == 0) {
                VaultSecuritySettings.setBiometricVaultTarget(this, VaultSecuritySettings.BIOMETRIC_TARGET_ASK)
                showBiometricVaultChooser()
            } else {
                val target = targets[position - 1]
                VaultSecuritySettings.setBiometricVaultTarget(this, biometricPreferenceFor(target))
                Toast.makeText(this, "Biometria associada a ${biometricChoice(target).title.lowercase()}.", Toast.LENGTH_SHORT).show()
                openVaultAfterBiometric(target)
            }
        }
        biometricAssociationDialog = dialog
        dialog.setOnDismissListener {
            if (biometricAssociationDialog === dialog) biometricAssociationDialog = null
        }
    }

    private fun availableBiometricTargets(): List<UnlockTarget> = buildList {
        add(UnlockTarget.PRIMARY)
        if (SecondaryVaultLock.isEnabled(this@PrimaryVaultActivity)) add(UnlockTarget.SECONDARY)
        if (TertiaryVaultLock.isEnabled(this@PrimaryVaultActivity)) add(UnlockTarget.TERTIARY)
    }

    private fun biometricChoice(target: UnlockTarget): OneUiDialog.Choice = when (target) {
        UnlockTarget.PRIMARY -> OneUiDialog.Choice("Cofre principal", "Fotos e vídeos privados principais.")
        UnlockTarget.SECONDARY -> OneUiDialog.Choice("Cofre secundário", "Cofre secundário configurado.")
        UnlockTarget.TERTIARY -> OneUiDialog.Choice("Cofre terciário", "Cofre terciário configurado.")
    }

    private fun biometricTargetFromPreference(value: String): UnlockTarget? = when (value) {
        VaultSecuritySettings.BIOMETRIC_TARGET_PRIMARY -> UnlockTarget.PRIMARY
        VaultSecuritySettings.BIOMETRIC_TARGET_SECONDARY -> UnlockTarget.SECONDARY
        VaultSecuritySettings.BIOMETRIC_TARGET_TERTIARY -> UnlockTarget.TERTIARY
        else -> null
    }

    private fun biometricPreferenceFor(target: UnlockTarget): String = when (target) {
        UnlockTarget.PRIMARY -> VaultSecuritySettings.BIOMETRIC_TARGET_PRIMARY
        UnlockTarget.SECONDARY -> VaultSecuritySettings.BIOMETRIC_TARGET_SECONDARY
        UnlockTarget.TERTIARY -> VaultSecuritySettings.BIOMETRIC_TARGET_TERTIARY
    }

    private fun openVaultAfterBiometric(target: UnlockTarget) {
        unlocking = false
        biometricVaultChooserDialog = null
        biometricAssociationDialog = null
        when (target) {
            UnlockTarget.PRIMARY -> {
                PrimaryVaultLock.unlockSession()
                applySecurityFlag()
                updateLockUi()
                refresh()
            }
            UnlockTarget.SECONDARY -> {
                SecondaryVaultLock.unlockSession()
                skipAutomaticUnlockOnce = true
                startActivity(Intent(this, SecondaryVaultActivity::class.java))
                showLockedState(requestUnlock = false)
            }
            UnlockTarget.TERTIARY -> {
                TertiaryVaultLock.unlockSession()
                skipAutomaticUnlockOnce = true
                startActivity(Intent(this, TertiaryVaultActivity::class.java))
                showLockedState(requestUnlock = false)
            }
        }
    }

    private fun showPinUnlockOnce() {
        if (pinUnlockDialogActive || isFinishing || isDestroyed) return
        pinUnlockDialogActive = true
        PinPadDialog.showVerify(
            activity = this,
            title = "Desbloquear cofre",
            subtitle = "Digite o PIN do cofre principal, secundário ou terciário.",
            verify = { pin ->
                when {
                    PrimaryVaultLock.verify(this, pin) -> { unlockTarget = UnlockTarget.PRIMARY; true }
                    SecondaryVaultLock.isEnabled(this) && SecondaryVaultLock.verify(this, pin) -> {
                        unlockTarget = UnlockTarget.SECONDARY
                        true
                    }
                    TertiaryVaultLock.isEnabled(this) && TertiaryVaultLock.verify(this, pin) -> {
                        unlockTarget = UnlockTarget.TERTIARY
                        true
                    }
                    else -> false
                }
            },
            onVerified = {
                pinUnlockDialogActive = false
                unlocking = false
                if (unlockTarget == UnlockTarget.SECONDARY) {
                    skipAutomaticUnlockOnce = true
                    startActivity(Intent(this, SecondaryVaultActivity::class.java))
                    showLockedState(requestUnlock = false)
                } else if (unlockTarget == UnlockTarget.TERTIARY) {
                    skipAutomaticUnlockOnce = true
                    startActivity(Intent(this, TertiaryVaultActivity::class.java))
                    showLockedState(requestUnlock = false)
                } else {
                    applySecurityFlag()
                    updateLockUi()
                    refresh()
                }
            },
            onCancel = {
                pinUnlockDialogActive = false
                unlocking = false
                showLockedState(requestUnlock = false)
            }
        )
    }

    private fun showLockedState(requestUnlock: Boolean) {
        refreshGeneration++
        clearSelection()
        adapter.submit(emptyList(), null, 0)
        if (::busyOverlay.isInitialized) busyOverlay.visibility = View.GONE
        mediaGrid.visibility = View.GONE
        emptyText.visibility = View.GONE
        lockedPanel.visibility = View.VISIBLE
        storageText.text = "Conteúdo privado e oculto"
        updateLockUi()
        renderImportState(showPopup = false)
        if (requestUnlock) mainHandler.post { requestUnlock() }
    }

    private fun updateLockUi() {
        lockButton.text = when {
            !PrimaryVaultLock.isEnabled(this) -> "Criar PIN"
            PrimaryVaultLock.isUnlocked(this) -> "Bloquear agora"
            else -> "Desbloquear"
        }
        if (::biometricUnlockButton.isInitialized) {
            val available = VaultSecuritySettings.biometricEnabled(this) &&
                VaultSecuritySettings.canUseBiometrics(this)
            biometricUnlockButton.visibility = if (available) View.VISIBLE else View.GONE
        }
    }

    private fun refresh() {
        val restorePosition = if (::mediaGrid.isInitialized) mediaGrid.firstVisiblePosition else -1
        val restoreTop = if (::mediaGrid.isInitialized) mediaGrid.getChildAt(0)?.top ?: 0 else 0
        VideoProcessingStateStore.recoverStale(this)
        VaultRepository.releaseStaleProcessingLocks()
        if (!PrimaryVaultLock.isUnlocked(this)) {
            showLockedState(requestUnlock = false)
            return
        }
        lockedPanel.visibility = View.GONE
        val generation = ++refreshGeneration
        storageText.text = "Carregando cofre…"
        setBusy(true, "Carregando mídias do cofre…")
        runCatching {
            ioExecutor.execute {
                val initial = runCatching {
                    // Primeiro entrega a grade/index ao usuário. Manutenções que varrem arquivos
                    // rodam depois e não ficam mais no caminho crítico do desbloqueio biométrico.
                    val items = VaultRepository.list(this)
                    val total = items.size
                    val used = VaultRepository.usedBytes(this)
                    val trashCount = VaultTrashRepository.count(this)
                    val optimization = VideoProcessingStateStore.snapshot(this)
                    InitialVaultPage(items, total, used, trashCount, optimization)
                }
                runOnUiThread {
                    if (generation != refreshGeneration || !PrimaryVaultLock.isUnlocked(this)) return@runOnUiThread
                    initial.onSuccess { page ->
                        renderVaultSnapshot(generation, page, restorePosition, restoreTop)
                    }.onFailure {
                        setBusy(false)
                        allItems.clear()
                        adapter.submit(emptyList(), null, 0)
                        mediaGrid.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                        emptyText.text = "Não foi possível carregar o cofre"
                        storageText.text = "O conteúdo continua protegido. Tente novamente."
                        Haptics.error(this)
                        Toast.makeText(this, it.message ?: "Falha ao carregar o cofre", Toast.LENGTH_LONG).show()
                    }
                }
                if (initial.isSuccess && generation == refreshGeneration && PrimaryVaultLock.isUnlocked(this)) {
                    runCatching { VaultRepository.runStartupMaintenance(this) }
                    runCatching { VaultTrashRepository.purgeExpired(this) }
                }
            }
        }.onFailure {
            if (generation == refreshGeneration) setBusy(false)
            storageText.text = "Não foi possível iniciar o carregamento"
            Toast.makeText(this, it.message ?: "Falha ao carregar o cofre", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderVaultSnapshot(
        generation: Int,
        page: InitialVaultPage,
        restorePosition: Int,
        restoreTop: Int
    ) {
        setBusy(false)
        allItems.clear()
        allItems.addAll(page.items)
        trashLabel.text = "Lixeira"
        trashLabel.contentDescription = if (page.trashCount == 0) "Lixeira" else "Lixeira, ${page.trashCount} item(ns)"
        applyGalleryView(page.optimization)
        val autoJob = if (!page.optimization.running) AutoGapRepairQueueStore.runningJob(this) else null
        if (autoJob != null) adapter.updateProcessing(autoJob.sourcePath, autoJob.progress)
        if (restorePosition >= 0 && mediaGrid.visibility == View.VISIBLE) {
            mediaGrid.setSelectionFromTop(restorePosition, restoreTop)
        }
        storageText.text = when {
            page.optimization.running -> "Processamento ${page.optimization.progress}% • ${page.optimization.message}"
            autoJob != null -> "Reparo ${autoJob.progress}% • ${autoJob.message}"
            else -> "${page.total} mídia(s) • ${VaultRepository.formatBytes(page.usedBytes)} • privado até exportar"
        }
        updateLockUi()
        renderImportState(showPopup = true)
        VaultAlbumStore.cleanMissing(this, allItems.map { it.file })
        if (sortMode.requiresDetailedMetadata()) prepareDetailedSort()
        else scheduleMetadataEnrichment(generation, page.items)
    }

    private fun requestVisibleMetadata(item: VaultRepository.MediaItem) {
        if (!item.video || (item.durationMs > 0L && item.width > 0 && item.height > 0)) return
        val generation = refreshGeneration
        val key = "${item.file.absolutePath}|${item.file.length()}|${item.file.lastModified()}"
        if (!visibleMetadataRequests.add(key)) return
        runCatching {
            visibleMetadataExecutor.execute {
                try {
                    if (generation != refreshGeneration || !PrimaryVaultLock.isUnlocked(this)) return@execute
                    val detailed = runCatching { VaultRepository.loadMediaDetails(applicationContext, item) }.getOrDefault(item)
                    if (detailed.durationMs <= 0L && detailed.width <= 0 && detailed.height <= 0) return@execute
                    runOnUiThread {
                        if (generation != refreshGeneration || !PrimaryVaultLock.isUnlocked(this)) return@runOnUiThread
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
                        if (generation != refreshGeneration || !PrimaryVaultLock.isUnlocked(this)) return@runOnUiThread
                        allItems.indices.forEach { index -> enriched[allItems[index].file.absolutePath]?.let { allItems[index] = it } }
                        adapter.updateMetadata(enriched)
                    }
                }
            }
        }
    }

    private fun updateOptimizationUi(snapshot: VideoProcessingStateStore.Snapshot) {
        updateProcessingUi(snapshot.sourcePath.takeIf { snapshot.running }, snapshot.progress, snapshot.message, "Processamento")
    }

    private fun updateProcessingUi(sourcePath: String?, progress: Int, message: String, label: String) {
        if (!::adapter.isInitialized || !::mediaGrid.isInitialized) return
        val firstPosition = mediaGrid.firstVisiblePosition
        val firstTop = mediaGrid.getChildAt(0)?.top ?: 0
        adapter.updateProcessing(sourcePath?.takeIf { it.isNotBlank() }, progress)
        if (firstPosition >= 0) mediaGrid.setSelectionFromTop(firstPosition, firstTop)
        if (!sourcePath.isNullOrBlank()) storageText.text = "$label ${progress.coerceIn(0, 100)}% • ${message.ifBlank { "Processando vídeo" }}"
    }

    private fun openImporter() {
        if (VaultImportQueueStore.hasPending(this, VaultAreaId.PRIMARY) || VaultBulkImportRunner.snapshot(this, VaultAreaId.PRIMARY).running) {
            OneUiDialog.message(this, "Importação em andamento", "Aguarde a fila atual terminar antes de iniciar outra importação. Assim a opção de arquivos repetidos não é misturada entre lotes.")
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Importar para o cofre",
            message = "Para muitos arquivos, escolha uma pasta. A cópia é sequencial para não travar nem estourar memória.",
            choices = listOf(
                OneUiDialog.Choice("Selecionar arquivos", "Permite marcar vários vídeos e fotos de uma vez."),
                OneUiDialog.Choice("Selecionar uma pasta", "Importa fotos e vídeos da pasta escolhida, inclusive subpastas.")
            )
        ) { option -> chooseImportDuplicatePolicy { keep ->
            pendingImportKeepDuplicates = keep
            if (option == 0) openFileImporter() else openFolderImporter()
        } }
    }

    private fun chooseImportDuplicatePolicy(onChosen: (Boolean) -> Unit) {
        OneUiDialog.choices(
            activity = this,
            title = "Arquivos repetidos",
            message = "Escolha como esta importação deve tratar fotos e vídeos que já existem no cofre.",
            choices = listOf(
                OneUiDialog.Choice("Ignorar repetidos", "Compara o conteúdo e não salva uma segunda cópia idêntica. Recomendado para importações grandes."),
                OneUiDialog.Choice("Manter repetidos", "Importa também cópias idênticas. Os arquivos recebem nomes únicos para não substituir os existentes.")
            )
        ) { option -> onChosen(option == 1) }
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
        storageText.text = "Lendo pasta…"
        scheduleImportStatusRefresh()
        VaultBulkImportRunner.startTree(this, VaultAreaId.PRIMARY, treeUri, "arquivo(s) da pasta", pendingImportKeepDuplicates)
        pendingImportKeepDuplicates = false
    }

    private fun importMediaBatch(uris: List<android.net.Uri>, label: String) {
        if (uris.isEmpty()) {
            refresh()
            return
        }
        setImportProgress(true, "Importando 0/${uris.size} $label…")
        storageText.text = "Importando 0/${uris.size} $label…"
        scheduleImportStatusRefresh()
        VaultBulkImportRunner.start(this, VaultAreaId.PRIMARY, uris, label, pendingImportKeepDuplicates)
        pendingImportKeepDuplicates = false
    }


    private fun releaseGalleryMemoryForViewer() {
        if (!::adapter.isInitialized) return
        adapter.pauseLoading()
        adapter.clearMemoryCache()
        if (::mediaGrid.isInitialized) {
            for (index in 0 until mediaGrid.childCount) {
                mediaGrid.getChildAt(index)?.findViewById<ImageView>(R.id.mediaThumbnail)?.setImageDrawable(null)
            }
        }
    }

    private fun openItem(item: VaultRepository.MediaItem) {
        if (!ensureUnlocked()) return
        releaseGalleryMemoryForViewer()
        startActivity(Intent(this, MediaPlayerActivity::class.java).putExtra(MediaPlayerActivity.EXTRA_PATH, item.file.absolutePath))
    }

    private fun showActions(item: VaultRepository.MediaItem) {
        if (!ensureUnlocked()) return
        prefetchMediaDetails(item)
        val optimization = VideoProcessingStateStore.snapshotFor(this, item.file)
        val processing = optimization?.running == true || VaultRepository.isBeingProcessed(item.file)
        val actions = when {
            item.video && processing -> arrayOf(
                "Abrir original", "Detalhes",
                "Cancelar processamento", "Cancelar e mover para a lixeira", "Cancelar e excluir direto"
            )
            item.video -> arrayOf("Abrir", "Detalhes", "Cortar/editar", "Exportar", "Mover para a lixeira", "Excluir direto")
            else -> arrayOf("Abrir", "Detalhes", "Exportar", "Mover para a lixeira", "Excluir direto")
        }
        OneUiDialog.choices(
            activity = this,
            title = item.name,
            message = optimization?.takeIf { processing }?.let { "Processamento ${it.progress}%\n${it.message}" },
            choices = actions.map { action ->
                OneUiDialog.Choice(
                    title = action,
                    destructive = action.contains("lixeira", ignoreCase = true) || action.contains("Excluir direto", ignoreCase = true) || action.contains("Cancelar processamento")
                )
            }
        ) { which ->
            when {
                item.video && processing -> when (which) {
                    0 -> openItem(item)
                    1 -> showMediaDetails(item)
                    2 -> {
                        VideoProcessingService.cancel(this)
                        AutoGapRepairService.cancelAndForget(this, item.file)
                        Toast.makeText(this, "Cancelando processamento…", Toast.LENGTH_SHORT).show()
                        scheduleOptimizationRefresh()
                    }
                    3 -> cancelProcessingAndDelete(item, permanently = false)
                    4 -> confirmCancelProcessingAndDelete(item)
                }
                item.video -> when (which) {
                    0 -> openItem(item)
                    1 -> showMediaDetails(item)
                    2 -> openTrimEditor(item)
                    3 -> exportItem(item)
                    4 -> confirmDelete(item)
                    5 -> confirmDirectDeleteSelected(listOf(item))
                }
                else -> when (which) {
                    0 -> openItem(item)
                    1 -> showMediaDetails(item)
                    2 -> exportItem(item)
                    3 -> confirmDelete(item)
                    4 -> confirmDirectDeleteSelected(listOf(item))
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
                if (requested && !isFinishing && !isDestroyed && PrimaryVaultLock.isUnlocked(this)) {
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
                .putExtra(MediaPlayerActivity.EXTRA_START_TRIM, true)
        )
    }

    private fun exportItem(item: VaultRepository.MediaItem) {
        storageText.text = "Exportando para a galeria…"
        ioExecutor.execute {
            val result = runCatching { VaultRepository.exportToGallery(this, item) }
            runOnUiThread {
                result.onSuccess {
                    Haptics.success(this)
                    Toast.makeText(this, "Cópia exportada para a galeria", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Haptics.error(this)
                    Toast.makeText(this, it.message ?: "Falha ao exportar", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun confirmDelete(item: VaultRepository.MediaItem) {
        val optimization = VideoProcessingStateStore.snapshotFor(this, item.file)
        if (optimization?.running == true || VaultRepository.isBeingProcessed(item.file)) {
            OneUiDialog.choices(
                activity = this,
                title = "Processamento em andamento",
                message = "Progresso: ${optimization?.progress ?: 0}%\n${optimization?.message ?: "Processando vídeo"}",
                choices = listOf(
                    OneUiDialog.Choice(
                        "Cancelar e mover para a lixeira",
                        "O arquivo será movido somente depois que o processamento for encerrado com segurança.",
                        destructive = true
                    ),
                    OneUiDialog.Choice(
                        "Cancelar e excluir direto",
                        "O arquivo será apagado permanentemente depois que o processamento for encerrado.",
                        destructive = true
                    )
                )
            ) { option ->
                when (option) {
                    0 -> cancelProcessingAndDelete(item, permanently = false)
                    1 -> confirmCancelProcessingAndDelete(item)
                }
            }
            return
        }
        OneUiDialog.confirm(
            activity = this,
            title = "Mover para a lixeira?",
            message = "A mídia continuará privada e poderá ser restaurada. Uma cópia exportada para a galeria não será afetada.",
            positiveLabel = "Mover",
            destructive = true
        ) {
            if (VaultTrashRepository.moveToTrash(this, item) != null) {
                Haptics.stop(this)
                adapter.removeThumbnail(item.file)
                refresh()
            } else Toast.makeText(this, "Não foi possível mover para a lixeira", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmCancelProcessingAndDelete(item: VaultRepository.MediaItem) {
        OneUiDialog.confirm(
            activity = this,
            title = "Cancelar e excluir direto?",
            message = "A processamento será encerrada e a mídia será apagada permanentemente, sem passar pela lixeira.",
            positiveLabel = "Excluir direto",
            destructive = true
        ) { cancelProcessingAndDelete(item, permanently = true) }
    }

    private fun cancelProcessingAndDelete(item: VaultRepository.MediaItem, permanently: Boolean) {
        val progress = OneUiDialog.progress(
            activity = this,
            title = "Encerrando processamento",
            message = "Aguardando o arquivo ser liberado com segurança…"
        )
        VideoProcessingService.cancel(this)
        AutoGapRepairService.cancelAndForget(this, item.file)
        val deadline = SystemClock.uptimeMillis() + PROCESSING_RELEASE_TIMEOUT_MS
        fun check() {
            if (isFinishing || isDestroyed) {
                progress.dismiss()
                return
            }
            VideoProcessingStateStore.recoverStale(this)
            VaultRepository.releaseStaleProcessingLocks()
            val state = VideoProcessingStateStore.snapshotFor(this, item.file)
            val processing = state?.running == true || VaultRepository.isBeingProcessed(item.file)
            if (!processing) {
                progress.dismiss()
                val deleted = if (permanently) {
                    VaultRepository.delete(this, item)
                } else {
                    VaultTrashRepository.moveToTrash(this, item) != null
                }
                if (deleted) {
                    Haptics.stop(this)
                    adapter.removeThumbnail(item.file)
                    refresh()
                } else {
                    val message = if (permanently) {
                        "Não foi possível excluir direto. O arquivo ainda pode estar em uso."
                    } else {
                        "Não foi possível mover para a lixeira. O arquivo ainda pode estar em uso."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                return
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                progress.dismiss()
                Toast.makeText(this, "O processamento ainda não liberou o arquivo", Toast.LENGTH_LONG).show()
                return
            }
            progress.update(state?.progress ?: -1, state?.message ?: "Encerrando processamento…")
            mainHandler.postDelayed({ check() }, PROCESSING_POLL_MS)
        }
        check()
    }

    private fun registerOptimizationReceiver() {
        if (optimizationReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(VideoProcessingService.ACTION_STATE)
            addAction(AutoGapRepairService.ACTION_STATE)
        }
        optimizationReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                this,
                optimizationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        }.getOrDefault(false)
    }

    private fun unregisterOptimizationReceiver() {
        if (!optimizationReceiverRegistered) return
        runCatching { unregisterReceiver(optimizationReceiver) }
        optimizationReceiverRegistered = false
    }

    private fun scheduleOptimizationRefresh() {
        mainHandler.removeCallbacks(optimizationRefresh)
        if (VideoProcessingStateStore.snapshot(this).running || AutoGapRepairQueueStore.runningJob(this) != null) {
            mainHandler.postDelayed(optimizationRefresh, OPTIMIZATION_REFRESH_MS)
        }
    }

    private fun applySecurityFlag() {
        if (PrimaryVaultLock.isEnabled(this) && CaptureSettings.snapshot(this).secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private class MediaAdapter(
        private val context: Context,
        private val onMore: (VaultRepository.MediaItem) -> Unit,
        private val onMetadataNeeded: (VaultRepository.MediaItem) -> Unit
    ) : BaseAdapter(), MediaThumbnailRepository.MemoryCacheHandle {
        private val items = mutableListOf<VaultRepository.MediaItem>()
        private val selectedPaths = hashSetOf<String>()
        private var selectionMode = false
        private val cacheKilobytes = (Runtime.getRuntime().maxMemory() / 1024L / 14L)
            .coerceIn(10L * 1024L, 56L * 1024L).toInt()
        private val thumbnails = object : LruCache<String, Bitmap>(cacheKilobytes) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
        init {
            MediaThumbnailRepository.registerMemoryCache(this)
        }
        private var executor = newThumbnailExecutor()
        private val loadingKeys = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile private var released = false
        @Volatile private var loadingEnabled = true
        @Volatile private var loadingGeneration = 0L
        @Volatile private var processingPath: String? = null
        @Volatile private var processingProgress = 0

        private data class Holder(
            val image: ImageView,
            val duration: TextView,
            val processing: TextView,
            val more: TextView,
            val overlay: View,
            val check: TextView
        )

        fun submit(newItems: List<VaultRepository.MediaItem>, activePath: String?, progress: Int) {
            processingPath = activePath
            processingProgress = progress.coerceIn(0, 100)
            items.clear()
            items.addAll(newItems)
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

        fun updateProcessing(activePath: String?, progress: Int) {
            processingPath = activePath
            processingProgress = progress.coerceIn(0, 100)
            notifyDataSetChanged()
        }

        fun updateSelection(paths: Set<String>, active: Boolean) {
            selectedPaths.clear()
            selectedPaths.addAll(paths)
            selectionMode = active
            notifyDataSetChanged()
        }


        override fun removeThumbnail(file: File) {
            val prefix = file.absolutePath
            thumbnails.snapshot().keys.filter { it.startsWith(prefix) }.forEach(thumbnails::remove)
        }

        fun pauseLoading() {
            if (released || !loadingEnabled) return
            loadingEnabled = false
            loadingGeneration++
            executor.shutdownNow()
            loadingKeys.clear()
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
            loadingKeys.clear()
            MediaThumbnailRepository.unregisterMemoryCache(this)
            thumbnails.evictAll()
        }

        override fun memoryBytes(): Long = thumbnails.size().toLong() * 1024L

        override fun clearMemoryCache() {
            thumbnails.evictAll()
        }

        private fun newThumbnailExecutor(): ExecutorService =
            Executors.newFixedThreadPool(THUMBNAIL_THREADS) { task ->
                Thread({
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    task.run()
                }, "SteadyVault-GalleryThumb")
            }

        override fun hasStableIds(): Boolean = true
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): VaultRepository.MediaItem = items[position]
        override fun getItemId(position: Int): Long = getItem(position).file.absolutePath.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: Holder
            if (convertView == null) {
                view = View.inflate(context, R.layout.item_media_grid, null)
                holder = Holder(
                    image = view.findViewById(R.id.mediaThumbnail),
                    duration = view.findViewById(R.id.mediaDuration),
                    processing = view.findViewById(R.id.mediaProcessing),
                    more = view.findViewById(R.id.mediaMore),
                    overlay = view.findViewById(R.id.mediaSelectionOverlay),
                    check = view.findViewById(R.id.mediaSelectionCheck)
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as Holder
            }

            val item = getItem(position)
            val path = item.file.absolutePath
            val selected = path in selectedPaths
            val processing = processingPath == path || VaultRepository.isBeingProcessed(item.file)
            val cacheKey = "$path:${item.file.lastModified()}:${item.file.length()}"

            view.contentDescription = item.name
            val durationLabel = if (item.video) VaultRepository.thumbnailDurationLabel(item) else ""
            holder.duration.visibility = if (durationLabel.isNotBlank()) View.VISIBLE else View.GONE
            holder.duration.text = durationLabel
            if (item.video && (durationLabel.isBlank() || item.width <= 0 || item.height <= 0)) onMetadataNeeded(item)
            holder.duration.bringToFront()
            holder.processing.visibility = if (processing) View.VISIBLE else View.GONE
            holder.processing.text = if (processing) {
                if (processingPath == path) "Original • $processingProgress%" else "Original • processando"
            } else ""
            holder.more.visibility = if (selectionMode) View.GONE else View.VISIBLE
            holder.more.setOnClickListener { onMore(item) }
            holder.overlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE

            holder.image.tag = cacheKey
            val cached = thumbnails.get(cacheKey)
            when {
                cached != null && !cached.isRecycled -> holder.image.setImageBitmap(cached)
                processing -> holder.image.setImageDrawable(null)
                else -> {
                    holder.image.setImageDrawable(null)
                    if (!released && loadingEnabled && loadingKeys.add(cacheKey)) {
                        val generation = loadingGeneration
                        (executor as? ThreadPoolExecutor)?.let { pool ->
                            if (pool.queue.size >= THUMBNAIL_BACKLOG_LIMIT) {
                                pool.queue.clear()
                                loadingKeys.clear()
                                loadingKeys.add(cacheKey)
                            }
                        }
                        runCatching {
                            executor.execute {
                                try {
                                    if (!released && loadingEnabled && generation == loadingGeneration) {
                                        val bitmap = loadThumbnail(item)
                                        if (!released && loadingEnabled && generation == loadingGeneration) {
                                            thumbnails.put(cacheKey, bitmap)
                                            holder.image.post {
                                                if (!released && loadingEnabled && generation == loadingGeneration &&
                                                    holder.image.tag == cacheKey && !bitmap.isRecycled
                                                ) holder.image.setImageBitmap(bitmap)
                                            }
                                        }
                                    }
                                } finally {
                                    loadingKeys.remove(cacheKey)
                                }
                            }
                        }.onFailure { loadingKeys.remove(cacheKey) }
                    }
                }
            }
            return view
        }

        private fun loadThumbnail(item: VaultRepository.MediaItem): Bitmap =
            MediaThumbnailRepository.load(context, item.file, item.video, THUMB_MAX_SIDE)

        companion object {
            private const val THUMB_MAX_SIDE = 512
            private const val THUMBNAIL_THREADS = 6
            private const val THUMBNAIL_BACKLOG_LIMIT = 48
        }
    }

    private enum class UnlockTarget { PRIMARY, SECONDARY, TERTIARY }

    companion object {
        private const val BIOMETRIC_CANCEL_FALLBACK_MS = 350L
        private const val BIOMETRIC_SUCCESS_DEBOUNCE_MS = 900L
        private const val OPTIMIZATION_REFRESH_MS = 350L
        private const val PROCESSING_POLL_MS = 250L
        private const val IMPORT_STATUS_REFRESH_MS = 500L
        private const val PROCESSING_RELEASE_TIMEOUT_MS = 20_000L
        private const val BULK_PROGRESS_STEP = 25
        private const val METADATA_BATCH_SIZE = 8
        private const val SORT_METADATA_BATCH_SIZE = 32
        private const val PREFS = "vault_gallery_ui"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_LARGER_THUMBNAILS_V144 = "larger_thumbnails_v144"
        private const val KEY_ACTIVE_ALBUM = "active_album"
        private const val KEY_TYPE_FILTER = "type_filter"
        private const val KEY_PERIOD_FILTER = "period_filter"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val ALBUM_UNASSIGNED = "__unassigned__"
    }
}
