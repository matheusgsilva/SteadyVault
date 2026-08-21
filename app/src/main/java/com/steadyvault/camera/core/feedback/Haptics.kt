package com.steadyvault.camera.core.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {

    fun tap(context: Context) {
        vibrateOneShot(context, 22L, 90)
    }

    fun start(context: Context) {
        vibrateWaveform(
            context,
            longArrayOf(0L, 45L, 45L, 70L),
            intArrayOf(0, 180, 0, 255)
        )
    }

    fun stop(context: Context) {
        vibrateWaveform(
            context,
            longArrayOf(0L, 70L, 35L, 35L),
            intArrayOf(0, 255, 0, 150)
        )
    }

    fun photo(context: Context) {
        vibrateWaveform(
            context,
            longArrayOf(0L, 25L, 35L, 45L),
            intArrayOf(0, 120, 0, 230)
        )
    }

    fun success(context: Context) {
        vibrateWaveform(
            context,
            longArrayOf(0L, 30L, 45L, 60L),
            intArrayOf(0, 110, 0, 220)
        )
    }

    fun error(context: Context) {
        vibrateWaveform(
            context,
            longArrayOf(0L, 80L, 55L, 80L),
            intArrayOf(0, 255, 0, 255)
        )
    }

    private fun vibrateOneShot(
        context: Context,
        durationMs: Long,
        amplitude: Int
    ) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude)
            )
        }
    }

    private fun vibrateWaveform(
        context: Context,
        timings: LongArray,
        amplitudes: IntArray
    ) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    timings,
                    amplitudes,
                    -1
                )
            )
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
}
