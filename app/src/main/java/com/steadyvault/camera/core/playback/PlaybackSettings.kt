package com.steadyvault.camera.core.playback

import android.content.Context

object PlaybackSettings {
    data class Snapshot(
        val intelligentPlayback: Boolean,
        val dropLateFrames: Boolean,
        val prebuffer4k60: Boolean,
        val fileCacheMs: Int,
        val autoRecoverStalls: Boolean,
        val openVideosExternally: Boolean,
        val openPhotosExternally: Boolean
    )

    private const val PREFS = "steadyvault_playback_settings"

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            intelligentPlayback = prefs.getBoolean("intelligent_playback", true),
            dropLateFrames = prefs.getBoolean("drop_late_frames", true),
            prebuffer4k60 = prefs.getBoolean("prebuffer_4k60", true),
            fileCacheMs = prefs.getInt("file_cache_ms", 1_200).coerceIn(250, 5_000),
            autoRecoverStalls = prefs.getBoolean("auto_recover_stalls", true),
            openVideosExternally = prefs.getBoolean("open_videos_externally", false),
            openPhotosExternally = prefs.getBoolean("open_photos_externally", false)
        )
    }

    fun save(context: Context, snapshot: Snapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("intelligent_playback", snapshot.intelligentPlayback)
            .putBoolean("drop_late_frames", snapshot.dropLateFrames)
            .putBoolean("prebuffer_4k60", snapshot.prebuffer4k60)
            .putInt("file_cache_ms", snapshot.fileCacheMs.coerceIn(250, 5_000))
            .putBoolean("auto_recover_stalls", snapshot.autoRecoverStalls)
            .putBoolean("open_videos_externally", snapshot.openVideosExternally)
            .putBoolean("open_photos_externally", snapshot.openPhotosExternally)
            .remove("prefer_vlc")
            .apply()
    }

    fun restoreDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
