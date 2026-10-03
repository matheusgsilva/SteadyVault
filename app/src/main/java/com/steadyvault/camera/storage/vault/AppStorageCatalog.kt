package com.steadyvault.camera.storage.vault

import android.content.Context
import java.io.File

object AppStorageCatalog {
    data class Category(val id: String, val label: String, val description: String, val files: Int, val bytes: Long, val clearable: Boolean = true)
    data class Snapshot(val categories: List<Category>) { val totalBytes: Long = categories.sumOf { it.bytes } }

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val primary = VaultRepository.primaryDirectory(app)
        val secondary = SecondaryVaultRepository.directory(app)
        val tertiary = TertiaryVaultRepository.directory(app)
        val trash = VaultTrashRepository.directory(app)
        val recovery = RecordingRecoveryRepository.directory(app)
        val thumbnailStats = MediaThumbnailRepository.cacheStats(app)
        val thumbnailDirectory = File(app.filesDir, THUMBNAIL_DIRECTORY)
        val importReports = File(app.filesDir, IMPORT_REPORTS_DIRECTORY)
        val databases = File(app.applicationInfo.dataDir, "databases")
        val preferences = File(app.applicationInfo.dataDir, "shared_prefs")
        val publicGallery = PublicMediaRegistry.metrics(app)
        val noBackup = app.noBackupFilesDir
        val internalExcluded = listOf(primary, secondary, tertiary, trash, recovery, thumbnailDirectory, importReports)
        val otherInternal = metricsExcluding(app.filesDir, internalExcluded)
        val externalRoot = app.getExternalFilesDir(null)
        val otherExternal = externalRoot?.let(::metrics) ?: (0 to 0L)

        return Snapshot(
            listOf(
                category(ID_PRIMARY, "Cofre principal", "Fotos e vídeos principais", primary),
                category(ID_SECONDARY, "Cofre secundário", "Mídias do segundo cofre", secondary),
                category(ID_TERTIARY, "Cofre terciário", "Mídias do terceiro cofre", tertiary),
                category(ID_TRASH, "Lixeira", "Mídias que ainda podem ser restauradas", trash),
                category(ID_RECOVERY, "Vídeos com erro / recuperados", "Gravações interrompidas preservadas fora do cache", recovery),
                Category(ID_THUMBNAILS, "Miniaturas", "Miniaturas persistentes da galeria; podem ser recriadas", thumbnailStats.diskFiles, thumbnailStats.diskBytes),
                category(ID_IMPORT_REPORTS, "Relatórios legados", "Relatórios antigos de importação, quando existirem", importReports),
                category(ID_DATABASES, "Índices e bancos locais", "Índice local da galeria; os arquivos dos cofres continuam sendo a fonte de verdade", databases),
                cacheCategory(app),
                Category(ID_OTHER_INTERNAL, "Outros dados internos", "Arquivos auxiliares criados por componentes e bibliotecas que não pertencem às áreas acima", otherInternal.first, otherInternal.second),
                Category(ID_OTHER_EXTERNAL, "Outros dados externos privados", "Arquivos auxiliares na área externa privada do aplicativo", otherExternal.first, otherExternal.second),
                Category(ID_PUBLIC_GALLERY, "Cópias públicas na Galeria", "Fotos e vídeos exportados pelo SteadyVault e registrados para gerenciamento", publicGallery.files, publicGallery.bytes),
                category(ID_NO_BACKUP, "Filas persistentes de importação", "Filas de trabalho em andamento que o Android exclui do backup automático; apague somente sem importações ativas", noBackup),
                category(ID_PREFERENCES, "Configurações internas", "Preferências, perfis e estados do app; são removidos com ‘Zerar todo o aplicativo’", preferences, clearable = false)
            )
        )
    }

    fun clear(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val result = when (id) {
            ID_PRIMARY -> clearDirectory(VaultRepository.primaryDirectory(app))
            ID_SECONDARY -> clearDirectory(SecondaryVaultRepository.directory(app))
            ID_TERTIARY -> clearDirectory(TertiaryVaultRepository.directory(app))
            ID_TRASH -> clearDirectory(VaultTrashRepository.directory(app))
            ID_RECOVERY -> clearDirectory(RecordingRecoveryRepository.directory(app))
            ID_THUMBNAILS -> { MediaThumbnailRepository.clearAll(app); true }
            ID_IMPORT_REPORTS -> clearDirectory(File(app.filesDir, IMPORT_REPORTS_DIRECTORY))
            ID_DATABASES -> { VaultMediaIndex.reset(app); clearDirectory(File(app.applicationInfo.dataDir, "databases")) }
            ID_CACHE -> clearCaches(app)
            ID_OTHER_INTERNAL -> clearOtherInternal(app)
            ID_OTHER_EXTERNAL -> clearOtherExternal(app)
            ID_PUBLIC_GALLERY -> PublicMediaRegistry.removeAll(app)
            ID_NO_BACKUP -> clearDirectory(app.noBackupFilesDir)
            else -> false
        }
        if (result && id in setOf(ID_PRIMARY, ID_SECONDARY, ID_TERTIARY, ID_TRASH, ID_RECOVERY, ID_DATABASES)) VaultMediaIndex.invalidate()
        return result
    }

    private fun category(id: String, label: String, description: String, directory: File, clearable: Boolean = true): Category {
        val metrics = metrics(directory)
        return Category(id, label, description, metrics.first, metrics.second, clearable)
    }


    private fun cacheCategory(context: Context): Category {
        val roots = listOfNotNull(context.cacheDir, context.externalCacheDir, File(context.applicationInfo.dataDir, "code_cache")).distinctBy(::canonicalPath)
        val all = roots.map(::metrics)
        return Category(ID_CACHE, "Caches", "Cache interno, externo, código compilado, frames e temporários recriáveis", all.sumOf { it.first }, all.sumOf { it.second })
    }

    private fun metrics(root: File): Pair<Int, Long> = metricsExcluding(root, emptyList())

    private fun metricsExcluding(root: File, excluded: List<File>): Pair<Int, Long> {
        if (!root.exists()) return 0 to 0L
        val excludedPaths = excluded.map(::canonicalPath).toHashSet()
        var files = 0
        var bytes = 0L
        runCatching {
            root.walkTopDown().onEnter { directory -> canonicalPath(directory) !in excludedPaths }.forEach { file ->
                if (file.isFile && canonicalPath(file) !in excludedPaths) { files++; bytes += file.length().coerceAtLeast(0L) }
            }
        }
        return files to bytes
    }

    private fun clearDirectory(directory: File): Boolean {
        if (!directory.exists()) return true
        return directory.listFiles().orEmpty().all { it.deleteRecursively() }
    }

    private fun clearCaches(context: Context): Boolean = listOfNotNull(context.cacheDir, context.externalCacheDir, File(context.applicationInfo.dataDir, "code_cache")).distinctBy(::canonicalPath).all { root ->
        root.listFiles().orEmpty().all { child -> if (RecordingRecoveryRepository.shouldProtectFromCacheCleanup(child)) true else child.deleteRecursively() }
    }

    private fun clearOtherInternal(context: Context): Boolean {
        val excluded = listOf(
            VaultRepository.primaryDirectory(context), SecondaryVaultRepository.directory(context), TertiaryVaultRepository.directory(context),
            VaultTrashRepository.directory(context), RecordingRecoveryRepository.directory(context),
            File(context.filesDir, THUMBNAIL_DIRECTORY), File(context.filesDir, IMPORT_REPORTS_DIRECTORY)
        ).map(::canonicalPath).toHashSet()
        return deleteExcluding(context.filesDir, excluded)
    }

    private fun clearOtherExternal(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return true
        return deleteExcluding(root, emptySet())
    }

    private fun deleteExcluding(root: File, excludedPaths: Set<String>): Boolean {
        if (!root.exists()) return true
        var success = true
        root.listFiles().orEmpty().forEach { child ->
            val childPath = canonicalPath(child)
            if (childPath in excludedPaths) return@forEach
            val containsExcluded = excludedPaths.any { it.startsWith(childPath + File.separator) }
            if (containsExcluded && child.isDirectory) {
                success = deleteExcluding(child, excludedPaths) && success
                if (child.listFiles().isNullOrEmpty()) child.delete()
            } else if (!child.deleteRecursively()) success = false
        }
        return success
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    const val ID_PRIMARY = "primary"
    const val ID_SECONDARY = "secondary"
    const val ID_TERTIARY = "tertiary"
    const val ID_TRASH = "trash"
    const val ID_RECOVERY = "recovery"
    const val ID_THUMBNAILS = "thumbnails"
    const val ID_IMPORT_REPORTS = "import_reports"
    const val ID_DATABASES = "databases"
    const val ID_CACHE = "cache"
    const val ID_OTHER_INTERNAL = "other_internal"
    const val ID_OTHER_EXTERNAL = "other_external"
    const val ID_PUBLIC_GALLERY = "public_gallery"
    const val ID_NO_BACKUP = "no_backup"
    const val ID_PREFERENCES = "preferences"
    private const val THUMBNAIL_DIRECTORY = "SteadyVaultThumbnails"
    private const val IMPORT_REPORTS_DIRECTORY = "ImportReports"
}
