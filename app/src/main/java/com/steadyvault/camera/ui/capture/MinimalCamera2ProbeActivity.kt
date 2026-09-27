package com.steadyvault.camera.ui.capture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import java.util.concurrent.Executor
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.DynamicRangeProfiles
import android.graphics.ImageFormat
import android.graphics.ColorSpace
import android.os.Build
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
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        Log.i(TAG, "PROBE ACTIVITY CREATED package=$packageName")
        thread = HandlerThread("MinimalCamera2Probe").apply { start() }
        handler = Handler(thread.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "PROBE FAIL permissions camera/audio")
            finishProbe()
            return
        }
        Log.i(TAG, "PROBE permissions OK; iniciando teste mínimo Camera2 1080p60")
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
        val characteristics = getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(CAMERA_ID)
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            )
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            set(CaptureRequest.CONTROL_AWB_LOCK, false)

            val afModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
            ) ?: intArrayOf()
            when {
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF) ->
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            }

            val awbModes = characteristics.get(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
            ) ?: intArrayOf()
            if (awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO)) {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            val oisModes = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            ) ?: intArrayOf()
            if (oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }

            set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
            set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            )

            val noiseModes = characteristics.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
            ) ?: intArrayOf()
            if (noiseModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            }

            val edgeModes = characteristics.get(
                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES
            ) ?: intArrayOf()
            if (edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)) {
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val distortionModes = characteristics.get(
                    CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES
                ) ?: intArrayOf()
                if (distortionModes.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)) {
                    set(
                        CaptureRequest.DISTORTION_CORRECTION_MODE,
                        CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
                    )
                }
            }

            val aberrationModes = characteristics.get(
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES
            ) ?: intArrayOf()
            if (aberrationModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)) {
                set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
                )
            }

            val hotPixelModes = characteristics.get(
                CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES
            ) ?: intArrayOf()
            if (hotPixelModes.contains(CameraMetadata.HOT_PIXEL_MODE_OFF)) {
                set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
            }

            val shadingModes = characteristics.get(
                CameraCharacteristics.SHADING_AVAILABLE_MODES
            ) ?: intArrayOf()
            if (shadingModes.contains(CameraMetadata.SHADING_MODE_OFF)) {
                set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
            }
        }.build()

        Log.i(
            TAG,
            "PROBE MINIMAL60 FULL request: AE=60-60 AF=continuous/off AWB=auto " +
                "EIS=off OIS=off NR=off EDGE=off DISTORTION=off ABERRATION=off " +
                "HOTPIXEL=off SHADING=off FACE=off ZSL=off intent=VIDEO_RECORD"
        )

        val outputConfiguration = OutputConfiguration(surface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setDynamicRangeProfile(DynamicRangeProfiles.STANDARD)
            }
        }
        val sessionExecutor = Executor { command -> handler.post(command) }
        val stateCallback = object : CameraCaptureSession.StateCallback() {
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
                                Log.w(
                                    TAG,
                                    "PROBE GAP frame=$frameCount " +
                                        "delta=${"%.3f".format(delta / 1_000_000.0)}ms " +
                                        "nominal=${"%.3f".format(NOMINAL_NS / 1_000_000.0)}ms " +
                                        "gaps=$gapCount"
                                )
                            }
                        }
                        previousTimestampNs = ts
                        val intervals = frameCount - 1
                        if (intervals > 0 && intervals % 120 == 0) {
                            val elapsedNs = ts - firstTimestampNs
                            val avg = intervals * 1_000_000_000.0 / elapsedNs
                            Log.i(
                                TAG,
                                "PROBE CADENCE frames=$frameCount intervals=$intervals " +
                                    "avg=${"%.2f".format(avg)} FPS gaps=$gapCount " +
                                    "worst=${"%.3f".format(worstDeltaNs / 1_000_000.0)}ms"
                            )
                        }
                    }
                }
                s.setRepeatingRequest(request, callback, handler)
                recorder?.start()
                started = true
                Log.i(
                    TAG,
                    "PROBE START SESSIONCONFIG regular outputConfig=1 dynamicRange=STANDARD " +
                        "sessionParameters=true colorSpace=BT709-if-supported " +
                        "template=PREVIEW AE=60-60 physicalBinding=false streamUseCase=off"
                )
                handler.postDelayed({ finishProbe() }, DURATION_MS)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "PROBE FAIL session configure")
                finishProbe()
            }
        }

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfiguration),
            sessionExecutor,
            stateCallback
        ).apply {
            setSessionParameters(request)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val supported = runCatching {
                    characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES
                    )?.getSupportedColorSpacesForDynamicRange(
                        ImageFormat.PRIVATE,
                        DynamicRangeProfiles.STANDARD
                    )
                }.getOrNull()
                if (supported?.contains(ColorSpace.Named.BT709) == true) {
                    setColorSpace(ColorSpace.Named.BT709)
                }
            }
        }

        Log.i(
            TAG,
            "PROBE SESSION MIRROR OutputConfiguration + SessionConfiguration(REGULAR) + " +
                "sessionParameters + STANDARD dynamic range + BT709-if-supported"
        )
        device.createCaptureSession(sessionConfiguration)
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
