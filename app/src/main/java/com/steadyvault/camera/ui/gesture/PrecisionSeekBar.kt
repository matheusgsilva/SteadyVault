package com.steadyvault.camera.ui.gesture

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import kotlin.math.abs

/** SeekBar cuja precisão horizontal é escolhida por um controle dedicado no player. */
class PrecisionSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {
    private var tracking = false
    private var lastTouchX = 0f
    private var virtualProgress = 0f
    private var precision = 1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var dragGesture = false

    fun isDragGesture(): Boolean = dragGesture

    fun setScrubPrecision(value: Float) {
        precision = when (value) {
            0.5f, 0.25f, 0.125f -> value
            else -> 1f
        }
        virtualProgress = progress.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val handled = super.onTouchEvent(event)
                tracking = handled
                downX = event.x
                dragGesture = false
                lastTouchX = event.x
                virtualProgress = progress.toFloat()
                handled
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return super.onTouchEvent(event)
                if (!dragGesture && abs(event.x - downX) > touchSlop) dragGesture = true
                val trackWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
                virtualProgress = (virtualProgress +
                    (event.x - lastTouchX) * direction / trackWidth.toFloat() * max.toFloat() * precision)
                    .coerceIn(min.toFloat(), max.toFloat())
                lastTouchX = event.x
                dispatchMappedEvent(event, MotionEvent.ACTION_MOVE)
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return super.onTouchEvent(event)
                val handled = dispatchMappedEvent(event, MotionEvent.ACTION_UP)
                finishTracking()
                handled
            }

            MotionEvent.ACTION_CANCEL -> {
                val handled = super.onTouchEvent(event)
                finishTracking()
                handled
            }

            else -> super.onTouchEvent(event)
        }
    }

    private fun dispatchMappedEvent(source: MotionEvent, action: Int): Boolean {
        val trackWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val range = (max - min).coerceAtLeast(1)
        val fraction = ((virtualProgress - min) / range.toFloat()).coerceIn(0f, 1f)
        val visualFraction = if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        val mappedX = paddingLeft + visualFraction * trackWidth
        val mapped = MotionEvent.obtain(source)
        mapped.action = action
        mapped.setLocation(mappedX, source.y)
        return try {
            super.onTouchEvent(mapped)
        } finally {
            mapped.recycle()
        }
    }

    private fun finishTracking() {
        tracking = false
        parent?.requestDisallowInterceptTouchEvent(false)
        virtualProgress = progress.toFloat()
        dragGesture = false
    }
}
