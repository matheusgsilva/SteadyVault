package com.steadyvault.camera.capture.recorder

import android.annotation.TargetApi
import android.content.Context
import android.media.CamcorderProfile
import android.media.EncoderProfiles
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import java.io.File
import kotlin.math.abs

class DirectMediaRecorderBackend(
    private val context: Context,
    private val outputFile: File,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val videoMime: String,
    private val videoBitrate: Int,
    private val hdrHlg10: Boolean,
    private val orientationHint: Int,
    override val integratedAudio: Boolean,
    private val audioSampleRate: Int,
    private val audioBitrate: Int,
    private val audioChannels: Int,
    private val onError: (Throwable) -> Unit
) : RecordingBackend {

    override val backendName: String
        get() = if (usesExactOemProfile) "OEM MediaRecorder direto" else "MediaRecorder direto"
    override val videoBitrateBps: Long
        get() = if (usesExactOemProfile) selectedProfile!!.videoProfile.bitrate.toLong() else videoBitrate.toLong()
    override val audioBitrateBps: Long get() = if (integratedAudio) audioBitrate.toLong() else 0L

    private var recorder: MediaRecorder? = null
    private var prepared = false
    private var armed = false
    private var started = false
    private var released = false
    private var selectedProfile: Selection? = null

    private val usesExactOemProfile: Boolean
        get() = selectedProfile != null

    val profileDescription: String
        get() = buildString {
            append(width).append('x').append(height).append(' ')
            append(targetFps).append(" FPS ")
            append(videoMime.substringAfter('/').uppercase()).append(' ')
            append((if (usesExactOemProfile) selectedProfile!!.videoProfile.bitrate else videoBitrate) / 1_000_000).append(" Mbps")
            if (hdrHlg10) append(" HLG10")
            if (usesExactOemProfile) append(" • perfil OEM exato") else append(" • configuração exata")
        }

    override fun prepare(): Surface {
        check(!released) { "MediaRecorder já liberado" }
        require(!outputFile.exists() || outputFile.delete()) { "não foi possível preparar o arquivo final" }
        outputFile.parentFile?.mkdirs()

        selectedProfile = findExactSelection(
            cameraId = cameraId,
            width = width,
            height = height,
            fps = targetFps,
            mime = videoMime,
            hdrHlg10 = hdrHlg10,
            requestedBitrate = videoBitrate
        )

        if (hdrHlg10 && selectedProfile == null) {
            throw IllegalStateException(
                "HLG10 direto exige perfil OEM compatível exatamente com " +
                    "${width}x${height} ${targetFps} FPS ${videoMime.substringAfter('/').uppercase()}"
            )
        }

        val mediaRecorder = createMediaRecorder()
        recorder = mediaRecorder

        try {
            mediaRecorder.apply {
                if (integratedAudio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)

                val oemProfile = selectedProfile
                if (usesExactOemProfile) {
                    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Perfil OEM direto exige Android 12 ou superior"
                    }
                    setOutputFormat(oemProfile!!.outputFormat)
                    setVideoProfile(oemProfile.videoProfile)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(videoEncoderFor(videoMime))
                    setVideoSize(width, height)
                    setVideoFrameRate(targetFps)
                    setVideoEncodingBitRate(videoBitrate)
                }

                if (integratedAudio) configureAudioManually(this)

                setOutputFile(outputFile.absolutePath)
                setOrientationHint(orientationHint)
                setOnErrorListener { _, what, extra ->
                    if (!released) {
                        onError(IllegalStateException("MediaRecorder error what=$what extra=$extra"))
                    }
                }
                prepare()
            }

            prepared = true
            return mediaRecorder.surface
        } catch (throwable: Throwable) {
            release()
            throw throwable
        }
    }

    override fun arm() {
        check(prepared && !released) { "MediaRecorder não preparado" }
        armed = true
    }

    override fun commitStart() {
        check(armed && prepared && !released) { "MediaRecorder não armado" }
        if (started) return
        recorder?.start() ?: throw IllegalStateException("MediaRecorder indisponível")
        started = true
    }

    override fun stop(): Boolean {
        if (!started || released) return false
        return runCatching {
            recorder?.stop()
            started = false
            outputFile.isFile && outputFile.length() > 0L
        }.getOrElse {
            started = false
            false
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        prepared = false
        armed = false
        started = false
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun configureAudioManually(mediaRecorder: MediaRecorder) {
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mediaRecorder.setAudioSamplingRate(audioSampleRate)
        mediaRecorder.setAudioEncodingBitRate(audioBitrate)
        mediaRecorder.setAudioChannels(audioChannels.coerceIn(1, 2))
    }

    data class Selection(
        val videoProfile: EncoderProfiles.VideoProfile,
        val outputFormat: Int
    )

    companion object {
        @TargetApi(Build.VERSION_CODES.S)
        fun findExactSelection(
            cameraId: String,
            width: Int,
            height: Int,
            fps: Int,
            mime: String,
            hdrHlg10: Boolean,
            requestedBitrate: Int
        ): Selection? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val quality = qualityFor(width, height) ?: return null
            val profiles = runCatching { CamcorderProfile.getAll(cameraId, quality) }.getOrNull() ?: return null
            val exact = profiles.videoProfiles
                .filter {
                    it.width == width &&
                        it.height == height &&
                        it.frameRate == fps &&
                        it.mediaType.equals(mime, ignoreCase = true) &&
                        hdrMatches(it, hdrHlg10)
                }
                .minByOrNull { abs(it.bitrate.toLong() - requestedBitrate.toLong()) }
                ?: return null

            return Selection(exact, profiles.recommendedFileFormat)
        }

        private fun qualityFor(width: Int, height: Int): Int? = when {
            width == 7680 && height == 4320 -> null
            width == 3840 && height == 2160 -> CamcorderProfile.QUALITY_2160P
            width == 1920 && height == 1080 -> CamcorderProfile.QUALITY_1080P
            width == 1280 && height == 720 -> CamcorderProfile.QUALITY_720P
            else -> null
        }

        @TargetApi(Build.VERSION_CODES.S)
        private fun hdrMatches(profile: EncoderProfiles.VideoProfile, hdrHlg10: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return !hdrHlg10
            return if (hdrHlg10) {
                profile.hdrFormat == EncoderProfiles.VideoProfile.HDR_HLG
            } else {
                profile.hdrFormat == EncoderProfiles.VideoProfile.HDR_NONE
            }
        }

        private fun videoEncoderFor(mime: String): Int = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaRecorder.VideoEncoder.HEVC
            MediaFormat.MIMETYPE_VIDEO_AVC -> MediaRecorder.VideoEncoder.H264
            else -> throw IllegalArgumentException("codec direto não suportado: $mime")
        }
    }
}
