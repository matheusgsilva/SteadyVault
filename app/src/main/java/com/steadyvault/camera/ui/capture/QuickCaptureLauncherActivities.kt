package com.steadyvault.camera.ui.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class PhotoLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openQuickCapture(CaptureActivity.ACTION_LAUNCHER_TAKE_PHOTO)
    }
}

class VideoLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openQuickCapture(CaptureActivity.ACTION_LAUNCHER_RECORD_VIDEO)
    }
}

private fun Activity.openQuickCapture(captureAction: String) {
    startActivity(Intent(this, CaptureActivity::class.java).apply {
        action = captureAction
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
    finish()
}
