package com.steadyvault.camera.ui.capture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File

class MinimalCamera2ProbeActivity : Activity() {
    companion object {
        private const val TAG = "SteadyVaultProbe"
        private const val CAMERA_ID = "0"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val FPS = 60
        private const val DURATION_MS = 20_000L
        private const val NOMINAL_NS = 1_000_000_000L / FPS
        private const val GAP_THRESHOLD_NS = 25_000_000L
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var started = false
    private var firstTimestampNs = 0L
    private var previousTimestampNs = 0L
    private var frameCount = 0
    private var gapCount = 0
    private var worstDeltaNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread = HandlerThread("MinimalCamera2Probe").apply { start() }
        handler = Handler(thread.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "PROBE FAIL permissions camera/audio")
            finishProbe()
            return
        }
        handler.post { runProbe() }
    }

    private fun runProbe() {
        try {
            val output = File(getExternalFilesDir(null), "minimal_camera2_probe_" + java.lang.System.currentTimeMillis() + ".mp4")
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                setVideoSize(WIDTH, HEIGHT)
                setVideoFrameRate(FPS)
                setVideoEncodingBitRate(120_000_000)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(320_000)
                setAudioChannels(2)
                setOrientationHint(90)
                setOutputFile(output.absolutePath)
                prepare()
            }
            recorderSurface = recorder!!.surface
            Log.i(TAG, "PROBE CONFIG camera=$CAMERA_ID size=${WIDTH}x${HEIGHT} fps=$FPS backend=MediaRecorder durationMs=$DURATION_MS file=${output.absolutePath}")

            val manager = getSystemService(CameraManager::class.java)
            manager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    Log.e(TAG, "PROBE FAIL camera disconnected")
                    finishProbe()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "PROBE FAIL camera error=$error")
                    finishProbe()
                }
            }, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "PROBE FAIL setup ${t.javaClass.simpleName}: ${t.message}", t)
            finishProbe()
        }
    }

    private fun createSession(device: CameraDevice) {
        val surface = recorderSurface ?: return finishProbe()
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AWB_LOCK, false)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()

        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        frameCount++
                        if (firstTimestampNs == 0L) firstTimestampNs = ts
                        if (previousTimestampNs != 0L) {
                            val delta = ts - previousTimestampNs
                            if (delta > worstDeltaNs) worstDeltaNs = delta
                            if (delta >= GAP_THRESHOLD_NS) {
                                gapCount++
                                Log.w(TAG, "PROBE GAP frame=$frameCount delta=${"%.3f".format(delta / 1_000_000.0)}ms nominal=${"%.3f".format(NOMINAL_NS / 1_000_000.0)}ms gaps=$gapCount")
                            }
                        }
                        previousTimestampNs = ts
                        val intervals = frameCount - 1
                        if (intervals > 0 && intervals % 120 == 0) {
                            val elapsedNs = ts - firstTimestampNs
                            val avg = intervals * 1_000_000_000.0 / elapsedNs
                            Log.i(TAG, "PROBE CADENCE frames=$frameCount intervals=$intervals avg=${"%.2f".format(avg)} FPS gaps=$gapCount worst=${"%.3f".format(worstDeltaNs / 1_000_000.0)}ms")
                        }
                    }
                }
                s.setRepeatingRequest(request, callback, handler)
                recorder?.start()
                started = true
                Log.i(TAG, "PROBE START template=PREVIEW AE=60-60 outputs=1 physicalBinding=false")
                handler.postDelayed({ finishProbe() }, DURATION_MS)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "PROBE FAIL session configure")
                finishProbe()
            }
        }, handler)
    }

    private fun finishProbe() {
        if (started) {
            runCatching { recorder?.stop() }
            started = false
        }
        val intervals = (frameCount - 1).coerceAtLeast(0)
        val elapsedNs = if (firstTimestampNs > 0L && previousTimestampNs >= firstTimestampNs) previousTimestampNs - firstTimestampNs else 0L
        val avg = if (intervals > 0 && elapsedNs > 0L) intervals * 1_000_000_000.0 / elapsedNs else 0.0
        Log.i(TAG, "PROBE RESULT frames=$frameCount intervals=$intervals avg=${"%.3f".format(avg)} FPS gaps=$gapCount worst=${"%.3f".format(worstDeltaNs / 1_000_000.0)}ms")

        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        session = null
        camera = null
        recorder = null
        recorderSurface = null
        runOnUiThread { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { recorder?.release() }
        if (::thread.isInitialized) thread.quitSafely()
    }
}
