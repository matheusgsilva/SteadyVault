package com.steadyvault.camera

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.storage.vault.FileDurability
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeSmokeTest {
    @Test
    fun runtimeStorageAndCaptureSettingsAreUsable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val probe = File(context.cacheDir, "android_runtime_smoke_${System.nanoTime()}.tmp")
        probe.writeText("steadyvault")
        FileDurability.syncFileAndDirectory(probe)
        assertTrue(probe.isFile && probe.readText() == "steadyvault")
        assertTrue(CaptureSettings.supportedFpsValues.contains(60))
        probe.delete()
    }
}
