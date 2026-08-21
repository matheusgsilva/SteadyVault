package com.steadyvault.camera.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.steadyvault.camera.ui.theme.AppearanceStore
import kotlin.math.abs
import kotlin.math.roundToInt

/** Faixa de corte com duas alças, marcador e precisão escolhida pelo player. */
class VideoTrimRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Handle { START, END, PLAYHEAD }

    interface Listener {
        fun onTrackingStarted(handle: Handle, fraction: Float)
        fun onRangeChanged(startFraction: Float, endFraction: Float, activeHandle: Handle)
        fun onPlayheadChanged(fraction: Float)
        fun onTrackingStopped(handle: Handle, fraction: Float)
    }

    var listener: Listener? = null
    var minimumSpanFraction: Float = 0.01f
        set(value) { field = value.coerceIn(0.001f, 0.95f) }

    private val density = resources.displayMetrics.density
    private val track = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 47, 52) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppearanceStore.palette(context).accent }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
    }
    private val playheadKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frameSource = Rect()
    private val frameDestination = RectF()

    private var startFraction = 0f
    private var endFraction = 1f
    private var playheadFraction = 0f
    private var activeHandle: Handle? = null
    private var frames: List<Bitmap> = emptyList()
    private var lastTouchX = 0f
    private var virtualFraction = 0f
    private var precision = 1f

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(64f).toInt()
        contentDescription = "Faixa de corte do vídeo"
    }

    fun setRange(start: Float, end: Float, playhead: Float = start) {
        startFraction = start.coerceIn(0f, (1f - minimumSpanFraction).coerceAtLeast(0f))
        endFraction = end.coerceIn(startFraction + minimumSpanFraction, 1f)
        playheadFraction = playhead.coerceIn(startFraction, endFraction)
        invalidate()
    }

    fun setPlayhead(fraction: Float) {
        playheadFraction = fraction.coerceIn(startFraction, endFraction)
        invalidate()
    }

    fun setScrubPrecision(value: Float) {
        precision = when (value) {
            0.5f, 0.25f, 0.125f -> value
            else -> 1f
        }
        activeHandle?.let { virtualFraction = activeFraction(it) }
    }

    fun setFrames(value: List<Bitmap>) {
        clearFrames()
        frames = value.filterNot { it.isRecycled }
        invalidate()
    }

    fun clearFrames() {
        frames.forEach { if (!it.isRecycled) it.recycle() }
        frames = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = dp(14f)
        track.set(inset, dp(10f), w - inset, h - dp(10f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (track.width() <= 0f) return
        val radius = dp(8f)
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        if (frames.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(track)
            val frameWidth = track.width() / frames.size
            frames.forEachIndexed { index, bitmap ->
                setCenterCropSource(bitmap, frameWidth, track.height())
                frameDestination.set(
                    track.left + frameWidth * index, track.top,
                    track.left + frameWidth * (index + 1), track.bottom
                )
                canvas.drawBitmap(bitmap, frameSource, frameDestination, framePaint)
            }
            canvas.restore()
        }
        for (index in 1 until 10) {
            val x = track.left + track.width() * index / 10f
            canvas.drawLine(x, track.top + dp(7f), x, track.bottom - dp(7f), segmentPaint)
        }
        val startX = xFor(startFraction)
        val endX = xFor(endFraction)
        canvas.drawRect(track.left, track.top, startX, track.bottom, shadePaint)
        canvas.drawRect(endX, track.top, track.right, track.bottom, shadePaint)
        selectedPaint.style = Paint.Style.STROKE
        selectedPaint.strokeWidth = dp(3f)
        canvas.drawRoundRect(RectF(startX, track.top, endX, track.bottom), radius, radius, selectedPaint)
        selectedPaint.style = Paint.Style.FILL
        drawHandle(canvas, startX)
        drawHandle(canvas, endX)
        val playheadX = xFor(playheadFraction)
        canvas.drawLine(playheadX, track.top - dp(3f), playheadX, track.bottom + dp(3f), playheadPaint)
        canvas.drawCircle(playheadX, track.top - dp(3f), dp(4f), playheadKnobPaint)
    }

    private fun drawHandle(canvas: Canvas, x: Float) {
        val width = dp(10f)
        canvas.drawRoundRect(
            RectF(x - width / 2f, track.top - dp(5f), x + width / 2f, track.bottom + dp(5f)),
            dp(4f), dp(4f), selectedPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || track.width() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeHandle = chooseHandle(event.x)
                lastTouchX = event.x
                val handle = requireNotNull(activeHandle)
                updateFromTouch(handle, fractionFor(event.x))
                virtualFraction = activeFraction(handle)
                listener?.onTrackingStarted(handle, virtualFraction)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val handle = activeHandle ?: return false
                virtualFraction += (event.x - lastTouchX) / track.width() * precision
                lastTouchX = event.x
                updateFromTouch(handle, virtualFraction)
                virtualFraction = activeFraction(handle)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handle = activeHandle ?: return false
                listener?.onTrackingStopped(handle, activeFraction(handle))
                activeHandle = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun chooseHandle(x: Float): Handle {
        val startDistance = abs(x - xFor(startFraction))
        val endDistance = abs(x - xFor(endFraction))
        val hitRadius = dp(24f)
        return when {
            startDistance <= hitRadius && startDistance <= endDistance -> Handle.START
            endDistance <= hitRadius -> Handle.END
            else -> Handle.PLAYHEAD
        }
    }

    private fun updateFromTouch(handle: Handle, requested: Float) {
        when (handle) {
            Handle.START -> {
                startFraction = requested.coerceIn(0f, endFraction - minimumSpanFraction)
                playheadFraction = startFraction
                listener?.onRangeChanged(startFraction, endFraction, handle)
            }
            Handle.END -> {
                endFraction = requested.coerceIn(startFraction + minimumSpanFraction, 1f)
                playheadFraction = endFraction
                listener?.onRangeChanged(startFraction, endFraction, handle)
            }
            Handle.PLAYHEAD -> {
                playheadFraction = requested.coerceIn(startFraction, endFraction)
                listener?.onPlayheadChanged(playheadFraction)
            }
        }
        invalidate()
    }

    private fun activeFraction(handle: Handle): Float = when (handle) {
        Handle.START -> startFraction
        Handle.END -> endFraction
        Handle.PLAYHEAD -> playheadFraction
    }

    private fun setCenterCropSource(bitmap: Bitmap, destinationWidth: Float, destinationHeight: Float) {
        val sourceWidth = bitmap.width.coerceAtLeast(1)
        val sourceHeight = bitmap.height.coerceAtLeast(1)
        val destinationAspect = destinationWidth / destinationHeight.coerceAtLeast(1f)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        if (sourceAspect > destinationAspect) {
            val cropWidth = (sourceHeight * destinationAspect).roundToInt().coerceIn(1, sourceWidth)
            val left = (sourceWidth - cropWidth) / 2
            frameSource.set(left, 0, left + cropWidth, sourceHeight)
        } else {
            val cropHeight = (sourceWidth / destinationAspect).roundToInt().coerceIn(1, sourceHeight)
            val top = (sourceHeight - cropHeight) / 2
            frameSource.set(0, top, sourceWidth, top + cropHeight)
        }
    }

    private fun xFor(fraction: Float): Float = track.left + track.width() * fraction.coerceIn(0f, 1f)
    private fun fractionFor(x: Float): Float = ((x - track.left) / track.width()).coerceIn(0f, 1f)
    private fun dp(value: Float): Float = value * density
}
