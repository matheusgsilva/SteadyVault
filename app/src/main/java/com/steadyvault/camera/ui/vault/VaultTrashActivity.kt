package com.steadyvault.camera.ui.vault

import android.graphics.Bitmap
import android.os.Bundle
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
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.security.PrimaryVaultLock
import com.steadyvault.camera.storage.vault.MediaThumbnailRepository
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VaultTrashActivity : ComponentActivity() {
    private lateinit var grid: GridView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var busyOverlay: View
    private lateinit var busyText: TextView
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-TrashIo")
    }
    private lateinit var adapter: TrashAdapter
    private val selectedPaths = linkedSetOf<String>()
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!anyVaultUnlocked()) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        setContentView(R.layout.activity_vault_trash)
        SystemBarInsets.applyTopAndBottom(findViewById(R.id.trashRoot))
        grid = findViewById(R.id.trashGrid)
        summary = findViewById(R.id.trashSummary)
        empty = findViewById(R.id.trashEmptyText)
        busyOverlay = findViewById(R.id.trashBusyOverlay)
        busyText = findViewById(R.id.trashBusyText)
        adapter = TrashAdapter(this) { showActions(it) }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (selectedPaths.isNotEmpty()) toggleSelection(item) else showActions(item)
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            toggleSelection(adapter.getItem(position), forceSelect = true)
            Haptics.tap(this)
            true
        }
        findViewById<View>(R.id.trashBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.trashSelectAllButton).setOnClickListener { selectAll() }
        findViewById<View>(R.id.trashDeleteSelectedButton).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.trashEmptyButton).setOnClickListener { confirmEmpty() }
        updateSelectionUi()
    }

    override fun onResume() {
        super.onResume()
        if (CaptureSettings.snapshot(this).secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!anyVaultUnlocked()) {
            finish()
            return
        }
        refresh()
    }

    override fun onDestroy() {
        refreshGeneration++
        if (::adapter.isInitialized) adapter.release()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun anyVaultUnlocked(): Boolean = PrimaryVaultLock.isUnlocked(this) || SecondaryVaultLock.isUnlocked(this) || TertiaryVaultLock.isUnlocked(this)

    private fun refresh() {
        if (!::summary.isInitialized || io.isShutdown) return
        val generation = ++refreshGeneration
        summary.text = "Carregando lixeira…"
        setBusy(true, "Carregando lixeira…")
        runCatching {
            io.execute {
                val result = runCatching {
                    val items = VaultTrashRepository.list(this)
                    val bytes = VaultTrashRepository.usedBytes(this)
                    val retention = VaultTrashRepository.retentionDays(this)
                    Triple(items, bytes, retention)
                }
                runOnUiThread {
                    if (generation != refreshGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    setBusy(false)
                    result.onSuccess { (items, bytes, retention) ->
                        adapter.submit(items)
                        selectedPaths.retainAll(items.mapTo(hashSetOf()) { it.file.absolutePath })
                        updateSelectionUi()
                        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                        grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                        val retentionText = if (retention == VaultTrashRepository.RETENTION_NEVER) "sem exclusão automática" else "exclusão em $retention dias"
                        summary.text = if (selectedPaths.isEmpty()) {
                            "${items.size} item(ns) • ${VaultRepository.formatBytes(bytes)} • $retentionText"
                        } else {
                            "${selectedPaths.size} selecionado(s) • ${items.size} item(ns) na lixeira"
                        }
                    }.onFailure {
                        adapter.submit(emptyList())
                        grid.visibility = View.GONE
                        empty.visibility = View.VISIBLE
                        empty.text = "Não foi possível carregar a lixeira"
                        summary.text = "A lixeira continua protegida. Tente abrir novamente."
                        Haptics.error(this)
                        Toast.makeText(this, it.message ?: "Falha ao abrir a lixeira", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure {
            if (generation == refreshGeneration) setBusy(false)
            summary.text = "Não foi possível iniciar a lixeira"
            Toast.makeText(this, it.message ?: "Falha ao abrir a lixeira", Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(active: Boolean, message: String = "Processando…") {
        if (!::busyOverlay.isInitialized) return
        busyText.text = message
        busyOverlay.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun updateBusy(message: String) {
        if (::busyOverlay.isInitialized && busyOverlay.visibility == View.VISIBLE) busyText.text = message
    }

    private fun toggleSelection(item: VaultTrashRepository.TrashItem, forceSelect: Boolean = false) {
        val path = item.file.absolutePath
        if (forceSelect || path !in selectedPaths) selectedPaths.add(path) else selectedPaths.remove(path)
        updateSelectionUi()
    }

    private fun selectAll() {
        val all = adapter.allPaths()
        if (selectedPaths.size == all.size && all.isNotEmpty()) selectedPaths.clear()
        else {
            selectedPaths.clear()
            selectedPaths.addAll(all)
        }
        updateSelectionUi()
    }

    private fun clearSelection() {
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val active = selectedPaths.isNotEmpty()
        findViewById<TextView>(R.id.trashSelectAllLabel).text = if (active) "Tudo" else "Selecionar"
        findViewById<TextView>(R.id.trashDeleteSelectedLabel).text = "Excluir"
        findViewById<View>(R.id.trashDeleteSelectedButton).visibility = if (active) View.VISIBLE else View.GONE
        adapter.updateSelection(selectedPaths, active)
    }

    private fun deleteSelected() {
        val items = adapter.itemsForPaths(selectedPaths)
        if (items.isEmpty()) return
        OneUiDialog.confirm(
            activity = this,
            title = "Excluir selecionados?",
            message = "${items.size} item(ns) serão removidos permanentemente da lixeira.",
            positiveLabel = "Excluir",
            destructive = true
        ) {
            clearSelection()
            if (io.isShutdown) return@confirm
            setBusy(true, "Excluindo 0/${items.size} da lixeira…")
            runCatching {
                io.execute {
                    var deleted = 0
                    items.forEachIndexed { index, item ->
                        if (VaultTrashRepository.deletePermanently(this, item)) deleted++
                        val done = index + 1
                        if (done == items.size || done % BULK_PROGRESS_STEP == 0) {
                            runOnUiThread { updateBusy("Excluindo $done/${items.size} • OK $deleted") }
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (deleted > 0) Haptics.stop(this) else Haptics.error(this)
                        Toast.makeText(this, "$deleted item(ns) excluído(s)", Toast.LENGTH_SHORT).show()
                        setBusy(false)
                        refresh()
                    }
                }
            }
        }
    }

    private fun showActions(item: VaultTrashRepository.TrashItem) {
        val deleted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.deletedAt))
        OneUiDialog.choices(
            activity = this,
            title = item.originalName,
            message = "${item.origin.label} • Excluído em $deleted",
            choices = listOf(
                OneUiDialog.Choice("Restaurar", "Retorna para ${item.origin.label.lowercase()}."),
                OneUiDialog.Choice("Excluir permanentemente", "Não poderá ser recuperada.", destructive = true)
            )
        ) { option -> if (option == 0) restore(item) else deletePermanently(item) }
    }

    private fun restore(item: VaultTrashRepository.TrashItem) {
        if (io.isShutdown) return
        summary.text = "Restaurando mídia…"
        runCatching {
            io.execute {
                val restored = runCatching { VaultTrashRepository.restore(this, item) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (restored != null) {
                        Haptics.success(this)
                        Toast.makeText(this, "Restaurada em ${item.origin.label}", Toast.LENGTH_SHORT).show()
                    } else {
                        Haptics.error(this)
                        Toast.makeText(this, "Não foi possível restaurar", Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        }
    }

    private fun deletePermanently(item: VaultTrashRepository.TrashItem) {
        OneUiDialog.confirm(
            activity = this,
            title = "Excluir permanentemente?",
            message = "Esta ação não pode ser desfeita.",
            positiveLabel = "Excluir",
            destructive = true
        ) {
            if (io.isShutdown) return@confirm
            setBusy(true, "Excluindo permanentemente…")
            runCatching {
                io.execute {
                    val deleted = runCatching { VaultTrashRepository.deletePermanently(this, item) }.getOrDefault(false)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (deleted) Haptics.stop(this) else Haptics.error(this)
                        setBusy(false)
                        refresh()
                    }
                }
            }
        }
    }

    private fun confirmEmpty() {
        if (adapter.count == 0) return
        OneUiDialog.confirm(
            activity = this,
            title = "Esvaziar a lixeira?",
            message = "Todos os itens serão excluídos permanentemente.",
            positiveLabel = "Esvaziar",
            destructive = true
        ) {
            if (io.isShutdown) return@confirm
            setBusy(true, "Esvaziando lixeira…")
            runCatching {
                io.execute {
                    val deleted = runCatching { VaultTrashRepository.empty(this) }.getOrDefault(0)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (deleted > 0) Haptics.stop(this)
                        Toast.makeText(this, "$deleted item(ns) excluído(s)", Toast.LENGTH_SHORT).show()
                        setBusy(false)
                        refresh()
                    }
                }
            }
        }
    }

    companion object {
        private const val BULK_PROGRESS_STEP = 25
        private const val THUMB_SIZE = 512
    }

    private class TrashAdapter(context: android.content.Context, private val onMore: (VaultTrashRepository.TrashItem) -> Unit) :
        BaseAdapter(), MediaThumbnailRepository.MemoryCacheHandle {
        private val appContext = context.applicationContext
        private val items = mutableListOf<VaultTrashRepository.TrashItem>()
        private var executor: ExecutorService = newThumbnailExecutor()
        private val loading = Collections.synchronizedSet(mutableSetOf<String>())
        private val selected = hashSetOf<String>()
        private var selectionMode = false
        private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
        init {
            MediaThumbnailRepository.registerMemoryCache(this)
        }

        private data class Holder(
            val image: ImageView,
            val duration: TextView,
            val more: TextView,
            val processing: TextView,
            val overlay: View,
            val check: TextView
        )

        fun submit(values: List<VaultTrashRepository.TrashItem>) {
            items.clear()
            items.addAll(values)
            notifyDataSetChanged()
        }

        fun updateSelection(paths: Set<String>, active: Boolean) {
            selected.clear()
            selected.addAll(paths)
            selectionMode = active
            notifyDataSetChanged()
        }

        fun allPaths(): Set<String> = items.mapTo(linkedSetOf()) { it.file.absolutePath }
        fun itemsForPaths(paths: Set<String>): List<VaultTrashRepository.TrashItem> = items.filter { it.file.absolutePath in paths }

        fun release() {
            executor.shutdownNow()
            loading.clear()
            MediaThumbnailRepository.unregisterMemoryCache(this)
            cache.evictAll()
        }

        override fun memoryBytes(): Long = cache.size().toLong() * 1024L

        override fun clearMemoryCache() {
            cache.evictAll()
        }

        override fun removeThumbnail(file: File) {
            cache.snapshot().keys.filter { it.startsWith(file.absolutePath) }.forEach(cache::remove)
        }

        private fun newThumbnailExecutor(): ExecutorService = Executors.newFixedThreadPool(3) { task ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "SteadyVault-TrashThumb")
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = getItem(position).file.absolutePath.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: View.inflate(parent.context, R.layout.item_media_grid, null)
            val holder = (view.tag as? Holder) ?: Holder(
                view.findViewById(R.id.mediaThumbnail),
                view.findViewById(R.id.mediaDuration),
                view.findViewById(R.id.mediaMore),
                view.findViewById(R.id.mediaProcessing),
                view.findViewById(R.id.mediaSelectionOverlay),
                view.findViewById(R.id.mediaSelectionCheck)
            ).also { view.tag = it }
            val item = getItem(position)
            val selectedNow = item.file.absolutePath in selected
            val durationLabel = if (item.media.video) VaultRepository.thumbnailDurationLabel(item.media) else ""
            holder.duration.visibility = if (durationLabel.isNotBlank()) View.VISIBLE else View.GONE
            holder.duration.text = durationLabel
            holder.processing.visibility = View.VISIBLE
            holder.processing.text = item.origin.label
            holder.more.visibility = if (selectionMode) View.GONE else View.VISIBLE
            holder.more.setOnClickListener { onMore(item) }
            holder.overlay.visibility = if (selectedNow) View.VISIBLE else View.GONE
            holder.check.visibility = if (selectedNow) View.VISIBLE else View.GONE
            val key = "${item.file.absolutePath}:${item.file.lastModified()}:${item.file.length()}"
            holder.image.tag = key
            cache.get(key)?.takeIf { !it.isRecycled }?.let(holder.image::setImageBitmap) ?: run {
                holder.image.setImageDrawable(null)
                if (loading.add(key)) runCatching {
                    executor.execute {
                        try {
                            val bitmap = MediaThumbnailRepository.load(appContext, item.file, item.media.video, THUMB_SIZE)
                            if (!MediaThumbnailRepository.isPlaceholder(bitmap)) cache.put(key, bitmap)
                            holder.image.post {
                                if (holder.image.tag == key && !bitmap.isRecycled) holder.image.setImageBitmap(bitmap)
                            }
                        } finally {
                            loading.remove(key)
                        }
                    }
                }.onFailure { loading.remove(key) }
            }
            return view
        }
    }
}
