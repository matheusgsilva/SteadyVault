package com.steadyvault.camera.ui.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.steadyvault.camera.R
import java.util.concurrent.Executors

/** Keeps every launcher entry optional and separate from the app's internal navigation. */
object QuickCaptureLauncher {
    enum class Kind(
        val action: String,
        internal val shortcutId: String
    ) {
        PHOTO(
            CaptureActivity.ACTION_LAUNCHER_TAKE_PHOTO,
            "quick_take_photo"
        ),
        VIDEO(
            CaptureActivity.ACTION_LAUNCHER_RECORD_VIDEO,
            "quick_record_video"
        )
    }

    data class Profile(
        val id: String,
        val label: String,
        val longLabel: String,
        val description: String,
        val iconRes: Int,
        internal val aliasClassName: String
    )

    data class State(
        val longPressEnabled: Boolean,
        val photoDrawerEnabled: Boolean,
        val videoDrawerEnabled: Boolean,
        val photoProfile: Profile,
        val videoProfile: Profile
    ) {
        fun profile(kind: Kind): Profile = when (kind) {
            Kind.PHOTO -> photoProfile
            Kind.VIDEO -> videoProfile
        }

        fun drawerEnabled(kind: Kind): Boolean = when (kind) {
            Kind.PHOTO -> photoDrawerEnabled
            Kind.VIDEO -> videoDrawerEnabled
        }
    }

    enum class PinResult {
        REQUESTED,
        UNSUPPORTED,
        FAILED
    }

    private val photoProfiles = listOf(
        Profile(
            id = "camera",
            label = "Câmera",
            longLabel = "Abrir câmera",
            description = "Ícone de câmera azul e nome direto.",
            iconRes = R.mipmap.ic_quick_camera,
            aliasClassName = "com.steadyvault.camera.ui.capture.CameraPhotoAlias"
        ),
        Profile(
            id = "scanner",
            label = "Scanner",
            longLabel = "Digitalizar documento",
            description = "Disfarce de scanner azul.",
            iconRes = R.mipmap.ic_quick_scanner,
            aliasClassName = "com.steadyvault.camera.ui.capture.ScannerPhotoAlias"
        ),
        Profile(
            id = "documents",
            label = "Documentos",
            longLabel = "Abrir documentos",
            description = "Disfarce de pasta de documentos.",
            iconRes = R.mipmap.ic_quick_documents,
            aliasClassName = "com.steadyvault.camera.ui.capture.DocumentsPhotoAlias"
        ),
        Profile(
            id = "notes",
            label = "Notas",
            longLabel = "Criar nova nota",
            description = "Disfarce de bloco de notas.",
            iconRes = R.mipmap.ic_quick_notes,
            aliasClassName = "com.steadyvault.camera.ui.capture.NotesPhotoAlias"
        )
    )

    private val videoProfiles = listOf(
        Profile(
            id = "video",
            label = "Vídeo",
            longLabel = "Abrir vídeo",
            description = "Ícone de vídeo vermelho e nome direto.",
            iconRes = R.mipmap.ic_quick_video,
            aliasClassName = "com.steadyvault.camera.ui.capture.VideoCaptureAlias"
        ),
        Profile(
            id = "recorder",
            label = "Gravador",
            longLabel = "Abrir gravador",
            description = "Disfarce de gravador de áudio.",
            iconRes = R.mipmap.ic_quick_recorder,
            aliasClassName = "com.steadyvault.camera.ui.capture.RecorderVideoAlias"
        ),
        Profile(
            id = "monitor",
            label = "Monitor",
            longLabel = "Abrir monitor",
            description = "Disfarce de monitor de atividade.",
            iconRes = R.mipmap.ic_quick_monitor,
            aliasClassName = "com.steadyvault.camera.ui.capture.MonitorVideoAlias"
        ),
        Profile(
            id = "calendar",
            label = "Agenda",
            longLabel = "Abrir agenda",
            description = "Disfarce de agenda verde.",
            iconRes = R.mipmap.ic_quick_calendar,
            aliasClassName = "com.steadyvault.camera.ui.capture.CalendarVideoAlias"
        )
    )

    fun profiles(kind: Kind): List<Profile> = when (kind) {
        Kind.PHOTO -> photoProfiles
        Kind.VIDEO -> videoProfiles
    }

    fun snapshot(context: Context): State {
        val preferences = preferences(context)
        return State(
            longPressEnabled = preferences.getBoolean(KEY_LONG_PRESS_ENABLED, true),
            photoDrawerEnabled = preferences.getBoolean(KEY_PHOTO_DRAWER_ENABLED, false),
            videoDrawerEnabled = preferences.getBoolean(KEY_VIDEO_DRAWER_ENABLED, false),
            photoProfile = findProfile(Kind.PHOTO, preferences.getString(KEY_PHOTO_PROFILE, null)),
            videoProfile = findProfile(Kind.VIDEO, preferences.getString(KEY_VIDEO_PROFILE, null))
        )
    }

    fun setLongPressEnabled(context: Context, enabled: Boolean): Boolean {
        preferences(context).edit().putBoolean(KEY_LONG_PRESS_ENABLED, enabled).apply()
        return applySavedConfiguration(context)
    }

    fun setDrawerEnabled(context: Context, kind: Kind, enabled: Boolean): Boolean {
        val key = if (kind == Kind.PHOTO) KEY_PHOTO_DRAWER_ENABLED else KEY_VIDEO_DRAWER_ENABLED
        preferences(context).edit().putBoolean(key, enabled).apply()
        return applySavedConfiguration(context)
    }

    fun setProfile(context: Context, kind: Kind, profileId: String): Boolean {
        if (profiles(kind).none { it.id == profileId }) return false
        val key = if (kind == Kind.PHOTO) KEY_PHOTO_PROFILE else KEY_VIDEO_PROFILE
        preferences(context).edit().putString(key, profileId).apply()
        return applySavedConfiguration(context)
    }

    fun applySavedConfiguration(context: Context): Boolean {
        val appContext = context.applicationContext
        val state = snapshot(appContext)
        val signature = configurationSignature(state)
        val settings = preferences(appContext)
        val aliasesApplied = runCatching {
            applyDrawerAliases(appContext, state)
        }.onFailure { error ->
            Log.w(TAG, "Não foi possível atualizar os ícones da gaveta", error)
        }.isSuccess
        val shortcutsQueued = runCatching {
            shortcutExecutor.execute {
                if (settings.getString(KEY_APPLIED_SIGNATURE, null) == signature) return@execute
                runCatching {
                    applyShortcuts(appContext, state)
                }.onSuccess {
                    settings.edit().putString(KEY_APPLIED_SIGNATURE, signature).apply()
                }.onFailure { error ->
                    Log.w(TAG, "Não foi possível atualizar os atalhos do launcher", error)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Não foi possível agendar a atualização dos atalhos", error)
        }.isSuccess
        return shortcutsQueued && aliasesApplied
    }

    fun requestPinnedShortcut(context: Context, kind: Kind, onResult: (PinResult) -> Unit) {
        val appContext = context.applicationContext
        val queued = runCatching {
            pinExecutor.execute {
                val result = runCatching {
                    val manager = appContext.getSystemService(ShortcutManager::class.java)
                        ?: return@runCatching PinResult.UNSUPPORTED
                    if (!manager.isRequestPinShortcutSupported) return@runCatching PinResult.UNSUPPORTED
                    val state = snapshot(appContext)
                    val shortcut = shortcutInfo(appContext, kind, kind.shortcutId, state.profile(kind))
                    if (manager.requestPinShortcut(shortcut, null)) PinResult.REQUESTED else PinResult.FAILED
                }.getOrElse { error ->
                    Log.w(TAG, "Não foi possível solicitar o atalho fixo", error)
                    PinResult.FAILED
                }
                mainHandler.post { onResult(result) }
            }
        }.isSuccess
        if (!queued) {
            mainHandler.post { onResult(PinResult.FAILED) }
        }
    }

    private fun applyShortcuts(context: Context, state: State) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val dynamicShortcuts = Kind.entries.map { kind ->
            shortcutInfo(context, kind, kind.shortcutId, state.profile(kind))
        }
        if (state.longPressEnabled) {
            check(manager.setDynamicShortcuts(dynamicShortcuts)) {
                "O launcher recusou a atualização dos atalhos dinâmicos"
            }
        } else {
            manager.removeDynamicShortcuts(Kind.entries.map { it.shortcutId })
            check(manager.updateShortcuts(dynamicShortcuts)) {
                "O launcher recusou a atualização dos atalhos fixados"
            }
        }
    }

    private fun applyDrawerAliases(context: Context, state: State) {
        val packageManager = context.packageManager
        Kind.entries.forEach { kind ->
            val selectedProfile = state.profile(kind)
            profiles(kind).forEach { profile ->
                val shouldEnable = state.drawerEnabled(kind) && profile.id == selectedProfile.id
                setAliasEnabled(packageManager, context.packageName, profile.aliasClassName, shouldEnable)
            }
        }
    }

    private fun setAliasEnabled(
        packageManager: PackageManager,
        packageName: String,
        aliasClassName: String,
        enabled: Boolean
    ) {
        val component = ComponentName(packageName, aliasClassName)
        val desiredState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (packageManager.getComponentEnabledSetting(component) == desiredState) return
        packageManager.setComponentEnabledSetting(
            component,
            desiredState,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun shortcutInfo(
        context: Context,
        kind: Kind,
        shortcutId: String,
        profile: Profile
    ): ShortcutInfo {
        val intent = Intent(context, CaptureActivity::class.java).apply {
            action = kind.action
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(profile.label)
            .setLongLabel(profile.longLabel)
            .setIcon(Icon.createWithResource(context, profile.iconRes))
            .setIntent(intent)
            .setRank(if (kind == Kind.PHOTO) 0 else 1)
            .build()
    }

    private fun findProfile(kind: Kind, id: String?): Profile =
        profiles(kind).firstOrNull { it.id == id } ?: profiles(kind).first()

    private fun configurationSignature(state: State): String = listOf(
        SHORTCUT_SCHEMA_VERSION,
        state.longPressEnabled,
        state.photoProfile.id,
        state.videoProfile.id
    ).joinToString(":")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val TAG = "QuickCaptureLauncher"
    private const val SHORTCUT_SCHEMA_VERSION = 1
    private const val PREFERENCES = "steadyvault_quick_capture_launcher"
    private const val KEY_LONG_PRESS_ENABLED = "long_press_enabled"
    private const val KEY_PHOTO_DRAWER_ENABLED = "photo_drawer_enabled"
    private const val KEY_VIDEO_DRAWER_ENABLED = "video_drawer_enabled"
    private const val KEY_PHOTO_PROFILE = "photo_profile"
    private const val KEY_VIDEO_PROFILE = "video_profile"
    private const val KEY_APPLIED_SIGNATURE = "applied_signature"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val shortcutExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-LauncherShortcuts")
    }
    private val pinExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SteadyVault-PinnedShortcut")
    }
}
