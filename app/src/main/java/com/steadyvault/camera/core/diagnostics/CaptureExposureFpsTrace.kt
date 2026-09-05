package com.steadyvault.camera.core.diagnostics

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class CaptureExposureFpsTrace private constructor(
    private val app: Context,
    private val sessionId: String,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val previewExposureNs: Long,
    private val previewIso: Int,
    private val startedWallMs: Long
) {
    companion object {
        private const val DIRECTORY = "diagnostics"
        private const val PREFIX = "fps_exposure_"
        private const val MAX_FRAMES = 36_000
        private const val NS = 1_000_000_000L

        fun begin(
            context: Context,
            sessionId: String,
            cameraId: String,
            width: Int,
            height: Int,
            targetFps: Int,
            previewExposureNs: Long,
            previewIso: Int
        ) = CaptureExposureFpsTrace(
            context.applicationContext,
            sessionId,
            cameraId,
            width,
            height,
            targetFps,
            previewExposureNs,
            previewIso,
            System.currentTimeMillis()
        )

        fun latestSummary(context: Context): File? = latest(context, ".txt")
        fun latestCsv(context: Context): File? = latest(context, ".csv")

        private fun latest(context: Context, extension: String): File? =
            File(context.filesDir, DIRECTORY)
                .listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(extension) }
                .orEmpty()
                .maxByOrNull(File::lastModified)
    }

    private data class Mp4Stats(
        val samples: Int,
        val medianDeltaUs: Long,
        val maxDeltaUs: Long,
        val gaps: Int,
        val doubleGaps: Int,
        val missing: Int,
        val declaredFps: Int?
    )

    private val lock = Any()
    private val active = AtomicBoolean(true)
    private var count = 0
    private var overflow = 0
    private var failures = 0
    private var bufferLosses = 0

    private val frameNumber = LongArray(MAX_FRAMES)
    private val sensorTs = LongArray(MAX_FRAMES)
    private val resultFrameDuration = LongArray(MAX_FRAMES)
    private val resultExposure = LongArray(MAX_FRAMES)
    private val resultIso = IntArray(MAX_FRAMES)
    private val requestAe = IntArray(MAX_FRAMES) { Int.MIN_VALUE }
    private val requestFpsLow = IntArray(MAX_FRAMES)
    private val requestFpsHigh = IntArray(MAX_FRAMES)
    private val requestFrameDuration = LongArray(MAX_FRAMES)
    private val requestExposure = LongArray(MAX_FRAMES)
    private val requestIso = IntArray(MAX_FRAMES)

    fun record(request: CaptureRequest, result: TotalCaptureResult) {
        if (!active.get()) return
        synchronized(lock) {
            if (!active.get()) return
            if (count >= MAX_FRAMES) {
                overflow++
                return
            }
            val i = count++
            frameNumber[i] = result.frameNumber
            sensorTs[i] = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            resultFrameDuration[i] = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            resultExposure[i] = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            resultIso[i] = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            requestAe[i] = request.get(CaptureRequest.CONTROL_AE_MODE) ?: Int.MIN_VALUE
            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.let {
                requestFpsLow[i] = it.lower
                requestFpsHigh[i] = it.upper
            }
            requestFrameDuration[i] = request.get(CaptureRequest.SENSOR_FRAME_DURATION) ?: 0L
            requestExposure[i] = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 0L
            requestIso[i] = request.get(CaptureRequest.SENSOR_SENSITIVITY) ?: 0
        }
    }

    fun recordFailure(failure: CaptureFailure) {
        if (!active.get()) return
        synchronized(lock) { failures++ }
    }

    fun recordBufferLost() {
        if (!active.get()) return
        synchronized(lock) { bufferLosses++ }
    }

    fun finish(outputFile: File?, reason: String) {
        if (!active.compareAndSet(true, false)) return
        val frames: Int
        val dropped: Int
        val failed: Int
        val lost: Int
        synchronized(lock) {
            frames = count
            dropped = overflow
            failed = failures
            lost = bufferLosses
        }
        Thread({
            runCatching { writeFiles(outputFile, reason, frames, dropped, failed, lost) }
                .onFailure { AppLogRepository.error(app, "fps_exposure_trace", "Falha ao salvar diagnóstico", it) }
        }, "SteadyVault-FpsExposure-Writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun writeFiles(outputFile: File?, reason: String, frames: Int, dropped: Int, failed: Int, lost: Int) {
        val directory = File(app.filesDir, DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedWallMs))
        val base = "${PREFIX}${stamp}_${sessionId.takeLast(8).replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        val csv = File(directory, "$base.csv")
        val txt = File(directory, "$base.txt")
        val safeCount = min(frames, MAX_FRAMES)

        csv.bufferedWriter().use { out ->
            out.appendLine("# SteadyVault FPS + exposure trace")
            out.appendLine("# session=$sessionId")
            out.appendLine("# cameraId=$cameraId")
            out.appendLine("# mode=${width}x${height}@${targetFps}")
            out.appendLine("# previewExposureNs=$previewExposureNs")
            out.appendLine("# previewIso=$previewIso")
            out.appendLine("# output=${outputFile?.name.orEmpty()}")
            out.appendLine("# finishReason=$reason")
            out.appendLine("index,frame_number,sensor_timestamp_ns,sensor_delta_ns,result_frame_duration_ns,result_exposure_ns,result_iso,request_ae_mode,request_fps_low,request_fps_high,request_frame_duration_ns,request_exposure_ns,request_iso")
            var previous = 0L
            for (i in 0 until safeCount) {
                val delta = if (sensorTs[i] > 0L && previous > 0L) sensorTs[i] - previous else 0L
                if (sensorTs[i] > 0L) previous = sensorTs[i]
                out.append(i.toString()).append(',')
                    .append(frameNumber[i].toString()).append(',')
                    .append(sensorTs[i].toString()).append(',')
                    .append(delta.toString()).append(',')
                    .append(resultFrameDuration[i].toString()).append(',')
                    .append(resultExposure[i].toString()).append(',')
                    .append(resultIso[i].toString()).append(',')
                    .append(requestAe[i].toString()).append(',')
                    .append(requestFpsLow[i].toString()).append(',')
                    .append(requestFpsHigh[i].toString()).append(',')
                    .append(requestFrameDuration[i].toString()).append(',')
                    .append(requestExposure[i].toString()).append(',')
                    .append(requestIso[i].toString()).appendLine()
            }
        }

        val mp4 = analyzeMp4(outputFile)
        txt.writeText(buildSummary(safeCount, dropped, failed, lost, mp4, csv.name, reason, outputFile?.name))
        pruneOld(directory)
        AppLogRepository.info(app, "fps_exposure_trace", "Diagnóstico salvo: ${txt.name} + ${csv.name}")
    }

    private fun buildSummary(
        frames: Int,
        dropped: Int,
        failed: Int,
        lost: Int,
        mp4: Mp4Stats?,
        csvName: String,
        reason: String,
        outputName: String?
    ): String {
        val deltas = ArrayList<Long>(frames)
        val autoExposure = ArrayList<Long>()
        val autoIso = ArrayList<Int>()
        val manualReqExposure = ArrayList<Long>()
        val manualReqIso = ArrayList<Int>()
        val manualResExposure = ArrayList<Long>()
        val manualResIso = ArrayList<Int>()
        val manualReqEnergy = ArrayList<Long>()
        val manualResEnergy = ArrayList<Long>()
        val autoEnergy = ArrayList<Long>()
        var previous = 0L
        var gaps = 0
        var doubleGaps = 0
        var exposureMismatch = 0
        var isoMismatch = 0
        var manualFrames = 0
        var autoFrames = 0
        var range60Frames = 0
        var maxDelta = 0L
        val period = if (targetFps > 0) NS.toDouble() / targetFps else 0.0
        val gapThreshold = (period * 1.5).toLong()
        val doubleLow = (period * 1.75).toLong()
        val doubleHigh = (period * 2.25).toLong()

        for (i in 0 until frames) {
            val ts = sensorTs[i]
            if (ts > 0L && previous > 0L) {
                val delta = ts - previous
                deltas += delta
                if (delta > maxDelta) maxDelta = delta
                if (delta > gapThreshold) {
                    gaps++
                    if (delta in doubleLow..doubleHigh) doubleGaps++
                }
            }
            if (ts > 0L) previous = ts

            val manual = requestAe[i] == CameraMetadata.CONTROL_AE_MODE_OFF
            if (manual) {
                manualFrames++
                if (requestFpsLow[i] == 60 && requestFpsHigh[i] == 60) range60Frames++
                if (requestExposure[i] > 0L) manualReqExposure += requestExposure[i]
                if (requestIso[i] > 0) manualReqIso += requestIso[i]
                if (resultExposure[i] > 0L) manualResExposure += resultExposure[i]
                if (resultIso[i] > 0) manualResIso += resultIso[i]
                energy(requestExposure[i], requestIso[i])?.let(manualReqEnergy::add)
                energy(resultExposure[i], resultIso[i])?.let(manualResEnergy::add)
                if (requestExposure[i] > 0L && resultExposure[i] > 0L && relativeDiff(requestExposure[i], resultExposure[i]) > 0.05) exposureMismatch++
                if (requestIso[i] > 0 && resultIso[i] > 0 && relativeDiff(requestIso[i].toLong(), resultIso[i].toLong()) > 0.05) isoMismatch++
            } else {
                autoFrames++
                if (resultExposure[i] > 0L) autoExposure += resultExposure[i]
                if (resultIso[i] > 0) autoIso += resultIso[i]
                energy(resultExposure[i], resultIso[i])?.let(autoEnergy::add)
            }
        }

        val sensorMedian = median(deltas)
        val previewEnergy = energy(previewExposureNs, previewIso)
        val autoBaselineEnergy = previewEnergy ?: median(autoEnergy).takeIf { it > 0L }
        val autoBaselineExposure = previewExposureNs.takeIf { it > 0L } ?: median(autoExposure)
        val autoBaselineIso = previewIso.takeIf { it > 0 } ?: medianInt(autoIso)
        val reqExposure = median(manualReqExposure)
        val reqIso = medianInt(manualReqIso)
        val resExposure = median(manualResExposure)
        val resIso = medianInt(manualResIso)
        val reqEnergy = median(manualReqEnergy)
        val resEnergy = median(manualResEnergy)
        val reqVsAuto = percent(reqEnergy, autoBaselineEnergy)
        val resVsReq = percent(resEnergy, reqEnergy.takeIf { it > 0L })
        val resVsAuto = percent(resEnergy, autoBaselineEnergy)

        val diagnosis = when {
            manualFrames == 0 -> "Sensor manual não entrou; este teste não mede a regressão de exposição manual."
            autoBaselineEnergy != null && reqEnergy > 0L && reqVsAuto < 80.0 && resVsReq in 90.0..110.0 ->
                "O request manual já pede menos energia de exposição que a referência automática; a escuridão nasce no cálculo exposição/ISO do app, não no encoder."
            reqEnergy > 0L && resEnergy > 0L && resVsReq < 80.0 ->
                "A HAL/sensor entregou bem menos energia do que o request manual pediu; verificar limitação de ISO/exposição com FPS_RANGE 60-60."
            autoBaselineEnergy != null && reqVsAuto in 85.0..115.0 && resVsReq in 90.0..110.0 && resVsAuto in 80.0..120.0 ->
                "Exposição/ISO manual preservaram aproximadamente a referência automática. Se o MP4 ainda estiver escuro, investigar tone mapping/cor depois da exposição."
            else -> "Os dados de exposição são mistos; compare os valores pedido x resultado abaixo e o CSV."
        }

        return buildString {
            appendLine("SteadyVault - diagnóstico FPS + exposição")
            appendLine("Sessão: $sessionId")
            appendLine("Arquivo: ${outputName ?: "não disponível"}")
            appendLine("Modo: ${width}x${height} @ $targetFps FPS")
            appendLine("Câmera: $cameraId")
            appendLine("Fechamento: $reason")
            appendLine()
            appendLine("Frames Camera2: $frames")
            appendLine("Overflow: $dropped")
            appendLine("CaptureFailure: $failed")
            appendLine("BufferLost: $lost")
            appendLine("Frames AE automático: $autoFrames")
            appendLine("Frames AE OFF/manual: $manualFrames")
            appendLine("Frames manuais com FPS_RANGE 60-60: $range60Frames/$manualFrames")
            appendLine("Delta mediano SENSOR_TIMESTAMP: ${ms(sensorMedian)} ms (${fps(sensorMedian)} FPS)")
            appendLine("Maior delta SENSOR_TIMESTAMP: ${ms(maxDelta)} ms")
            appendLine("Lacunas >1,5x: $gaps")
            appendLine("Lacunas próximas de 33 ms: $doubleGaps")
            appendLine()
            appendLine("EXPOSIÇÃO / LUMINOSIDADE")
            appendLine("Referência automática/preview: ${ms(autoBaselineExposure)} ms • ISO $autoBaselineIso")
            appendLine("Request manual mediano: ${ms(reqExposure)} ms • ISO $reqIso")
            appendLine("Resultado sensor mediano: ${ms(resExposure)} ms • ISO $resIso")
            appendLine("Energia request manual vs automática: ${pct(reqVsAuto)}")
            appendLine("Energia resultado vs request: ${pct(resVsReq)}")
            appendLine("Energia resultado vs automática: ${pct(resVsAuto)}")
            appendLine("Frames com exposição resultado >5% diferente do pedido: $exposureMismatch/$manualFrames")
            appendLine("Frames com ISO resultado >5% diferente do pedido: $isoMismatch/$manualFrames")
            appendLine()
            appendLine("MP4 / PTS")
            if (mp4 == null) {
                appendLine("Não foi possível analisar o MP4.")
            } else {
                appendLine("Samples: ${mp4.samples}")
                appendLine("FPS declarado: ${mp4.declaredFps ?: -1}")
                appendLine("Delta PTS mediano: ${String.format(Locale.US, "%.3f", mp4.medianDeltaUs / 1000.0)} ms")
                appendLine("Maior delta PTS: ${String.format(Locale.US, "%.3f", mp4.maxDeltaUs / 1000.0)} ms")
                appendLine("Lacunas PTS >1,5x: ${mp4.gaps}")
                appendLine("Lacunas PTS próximas de 33 ms: ${mp4.doubleGaps}")
                appendLine("Frames aparentemente ausentes: ${mp4.missing}")
            }
            appendLine()
            appendLine("DIAGNÓSTICO AUTOMÁTICO: $diagnosis")
            appendLine("CSV: $csvName")
            appendLine("O callback desta build é apenas diagnóstico; não há escrita em disco por frame.")
        }
    }

    private fun analyzeMp4(file: File?): Mp4Stats? {
        if (file == null || !file.isFile || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var declared: Int? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
                track = i
                declared = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull() else null
                break
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val pts = ArrayList<Long>(36_000)
            while (true) {
                val time = extractor.sampleTime
                if (time < 0L) break
                pts += time
                if (!extractor.advance()) break
            }
            if (pts.size < 2) return Mp4Stats(pts.size, 0L, 0L, 0, 0, 0, declared)
            val sorted = pts.sorted()
            val deltas = ArrayList<Long>(sorted.size - 1)
            for (i in 1 until sorted.size) if (sorted[i] > sorted[i - 1]) deltas += sorted[i] - sorted[i - 1]
            val period = 1_000_000.0 / targetFps
            val threshold = (period * 1.5).toLong()
            val low = (period * 1.75).toLong()
            val high = (period * 2.25).toLong()
            var gaps = 0
            var doubles = 0
            var missing = 0
            var max = 0L
            deltas.forEach { delta ->
                if (delta > max) max = delta
                if (delta > threshold) {
                    gaps++
                    if (delta in low..high) doubles++
                    missing += ((delta / period).toInt() - 1).coerceAtLeast(1)
                }
            }
            Mp4Stats(pts.size, median(deltas), max, gaps, doubles, missing, declared)
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun energy(exposureNs: Long, iso: Int): Long? =
        if (exposureNs > 0L && iso > 0) exposureNs * iso.toLong() else null

    private fun relativeDiff(a: Long, b: Long): Double = abs(a - b).toDouble() / a.coerceAtLeast(1L).toDouble()
    private fun percent(value: Long, base: Long?): Double = if (value > 0L && base != null && base > 0L) value * 100.0 / base else Double.NaN
    private fun pct(value: Double): String = if (value.isNaN()) "n/d" else String.format(Locale.US, "%.1f%%", value)
    private fun fps(deltaNs: Long): String = if (deltaNs > 0L) String.format(Locale.US, "%.3f", NS.toDouble() / deltaNs) else "n/d"
    private fun ms(ns: Long): String = String.format(Locale.US, "%.3f", ns / 1_000_000.0)

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else sorted[m - 1] / 2L + sorted[m] / 2L
    }

    private fun medianInt(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else ((sorted[m - 1].toLong() + sorted[m]) / 2L).toInt()
    }

    private fun pruneOld(directory: File) {
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }.orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(12)
            .forEach { runCatching { it.delete() } }
    }
}
