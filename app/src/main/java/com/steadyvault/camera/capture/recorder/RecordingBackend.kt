package com.steadyvault.camera.capture.recorder

import android.view.Surface

interface RecordingBackend {
    val backendName: String
    val integratedAudio: Boolean
    val videoBitrateBps: Long
    val audioBitrateBps: Long

    fun prepare(): Surface
    fun arm()
    fun commitStart()
    fun stop(): Boolean
    fun release()
}
