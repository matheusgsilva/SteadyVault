package com.steadyvault.camera.ui.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PreviewGridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f
        canvas.drawLine(x1, 0f, x1, h, linePaint)
        canvas.drawLine(x2, 0f, x2, h, linePaint)
        canvas.drawLine(0f, y1, w, y1, linePaint)
        canvas.drawLine(0f, y2, w, y2, linePaint)
    }
}
