package com.steadyvault.camera.ui.gesture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.steadyvault.camera.core.validation.UiBehaviorRules
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr), View.OnTouchListener {
    private val drawMatrix = Matrix()
    private var relativeScale = 1f
    private var zoomListener: ((Float) -> Unit)? = null
    private var animator: ValueAnimator? = null
    private var fillMode = false
    private var doubleTapConsumed = false
    private var dismissListener: (() -> Unit)? = null
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dismissGestureActive = false
    private var dismissCallbackSent = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                animator?.cancel()
                resetDismissTransform(animated = false)
                parent?.requestDisallowInterceptTouchEvent(true)
                return drawable != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val targetScale = (relativeScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val applied = targetScale / relativeScale.coerceAtLeast(0.0001f)
                drawMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                relativeScale = targetScale
                constrainMatrix()
                applyMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                parent?.requestDisallowInterceptTouchEvent(relativeScale > 1.001f)
                if (relativeScale < 1.02f) animateToScale(1f, width / 2f, height / 2f)
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                doubleTapConsumed = true
                animateToScale(
                    UiBehaviorRules.nextDoubleTapScale(relativeScale, MAX_SCALE),
                    event.x,
                    event.y
                )
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                second: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (relativeScale <= 1.001f || scaleDetector.isInProgress) return false
                drawMatrix.postTranslate(-distanceX, -distanceY)
                constrainMatrix()
                applyMatrix()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setOnTouchListener(this)
        scaleDetector.isQuickScaleEnabled = false
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom(immediate = true) }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) post { resetZoom(immediate = true) }
    }

    override fun onTouch(view: View?, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            animate().cancel()
            if (relativeScale <= 1.001f) resetDismissTransform(animated = false)
            doubleTapConsumed = false
            dismissGestureActive = false
            dismissCallbackSent = false
            downX = event.x
            downY = event.y
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            parent?.requestDisallowInterceptTouchEvent(relativeScale > 1.001f)
        }

        velocityTracker?.addMovement(event)
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && relativeScale <= 1.001f && dismissListener != null) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dismissGestureActive || (dy > touchSlop && dy > abs(dx) * DISMISS_DIRECTION_RATIO)) {
                        dismissGestureActive = true
                        applyDismissTransform(dy.coerceAtLeast(0f))
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dismissGestureActive) completeDismissGesture()
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
                doubleTapConsumed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dismissGestureActive) resetDismissTransform(animated = true)
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(relativeScale > 1.001f)
                doubleTapConsumed = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun resetZoom() = resetZoom(immediate = false)

    fun currentZoom(): Float = relativeScale

    fun zoomBy(delta: Float) {
        zoomTo(UiBehaviorRules.zoomStep(relativeScale, delta, MAX_SCALE))
    }

    fun zoomTo(scale: Float) {
        animateToScale(scale.coerceIn(MIN_SCALE, MAX_SCALE), width / 2f, height / 2f)
    }

    fun setFillMode(enabled: Boolean) {
        if (fillMode == enabled) return
        fillMode = enabled
        resetZoom(immediate = true)
    }

    fun isFillMode(): Boolean = fillMode

    fun setOnZoomChangedListener(listener: ((Float) -> Unit)?) {
        zoomListener = listener
        listener?.invoke(relativeScale)
    }

    fun setOnDismissListener(listener: (() -> Unit)?) {
        dismissListener = listener
    }

    private fun resetZoom(immediate: Boolean) {
        if (drawable == null || width <= 0 || height <= 0) return
        if (!immediate && relativeScale > 1.001f) {
            animateToScale(1f, width / 2f, height / 2f)
            return
        }
        animator?.cancel()
        resetDismissTransform(animated = false)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val drawableWidth = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
        val drawableHeight = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
        val initialScale = if (fillMode) {
            max(availableWidth / drawableWidth, availableHeight / drawableHeight)
        } else {
            min(availableWidth / drawableWidth, availableHeight / drawableHeight)
        }
        val dx = paddingLeft + (availableWidth - drawableWidth * initialScale) / 2f
        val dy = paddingTop + (availableHeight - drawableHeight * initialScale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(initialScale, initialScale)
        drawMatrix.postTranslate(dx, dy)
        relativeScale = 1f
        constrainMatrix()
        applyMatrix()
    }

    private fun animateToScale(destination: Float, focusX: Float, focusY: Float) {
        if (drawable == null || width <= 0 || height <= 0) return
        animator?.cancel()
        val end = destination.coerceIn(MIN_SCALE, MAX_SCALE)
        val start = relativeScale
        if (abs(end - start) < 0.001f) return
        var last = start
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = 210L
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                val next = valueAnimator.animatedValue as Float
                val factor = next / last.coerceAtLeast(0.0001f)
                drawMatrix.postScale(factor, factor, focusX, focusY)
                relativeScale = next
                last = next
                constrainMatrix()
                applyMatrix()
            }
            start()
        }
    }

    private fun applyDismissTransform(offsetY: Float) {
        val heightValue = height.coerceAtLeast(1).toFloat()
        val progress = (offsetY / (heightValue * DISMISS_PROGRESS_DISTANCE)).coerceIn(0f, 1f)
        translationY = offsetY
        val displayScale = 1f - DISMISS_SCALE_REDUCTION * progress
        scaleX = displayScale
        scaleY = displayScale
        alpha = 1f - DISMISS_ALPHA_REDUCTION * progress
    }

    private fun completeDismissGesture() {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val velocityY = tracker?.yVelocity ?: 0f
        val threshold = maxOf(height * DISMISS_DISTANCE_FRACTION, touchSlop * 6f)
        if (translationY >= threshold || velocityY >= minimumFlingVelocity * DISMISS_VELOCITY_MULTIPLIER) {
            if (dismissCallbackSent) return
            dismissCallbackSent = true
            animate().cancel()
            animate()
                .translationY(height.coerceAtLeast(1) * 1.05f)
                .scaleX(1f - DISMISS_SCALE_REDUCTION)
                .scaleY(1f - DISMISS_SCALE_REDUCTION)
                .alpha(1f - DISMISS_ALPHA_REDUCTION)
                .setDuration(DISMISS_ANIMATION_MS)
                .withEndAction { dismissListener?.invoke() }
                .start()
        } else {
            resetDismissTransform(animated = true)
        }
    }

    private fun resetDismissTransform(animated: Boolean) {
        dismissGestureActive = false
        if (animated) {
            animate().cancel()
            animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(DISMISS_RETURN_MS)
                .start()
        } else {
            animate().cancel()
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
    }

    private fun applyMatrix() {
        imageMatrix = drawMatrix
        zoomListener?.invoke(relativeScale)
        invalidate()
    }

    private fun constrainMatrix() {
        val currentDrawable = drawable ?: return
        val rect = RectF(
            0f,
            0f,
            currentDrawable.intrinsicWidth.toFloat(),
            currentDrawable.intrinsicHeight.toFloat()
        )
        drawMatrix.mapRect(rect)
        val leftBound = paddingLeft.toFloat()
        val topBound = paddingTop.toFloat()
        val rightBound = (width - paddingRight).toFloat()
        val bottomBound = (height - paddingBottom).toFloat()
        val availableWidth = rightBound - leftBound
        val availableHeight = bottomBound - topBound
        val dx = if (rect.width() <= availableWidth) {
            leftBound + (availableWidth - rect.width()) / 2f - rect.left
        } else when {
            rect.left > leftBound -> leftBound - rect.left
            rect.right < rightBound -> rightBound - rect.right
            else -> 0f
        }
        val dy = if (rect.height() <= availableHeight) {
            topBound + (availableHeight - rect.height()) / 2f - rect.top
        } else when {
            rect.top > topBound -> topBound - rect.top
            rect.bottom < bottomBound -> bottomBound - rect.bottom
            else -> 0f
        }
        if (abs(dx) > 0.001f || abs(dy) > 0.001f) drawMatrix.postTranslate(dx, dy)
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 8f
        private const val DISMISS_DIRECTION_RATIO = 1.25f
        private const val DISMISS_PROGRESS_DISTANCE = 0.55f
        private const val DISMISS_DISTANCE_FRACTION = 0.17f
        private const val DISMISS_VELOCITY_MULTIPLIER = 4f
        private const val DISMISS_SCALE_REDUCTION = 0.07f
        private const val DISMISS_ALPHA_REDUCTION = 0.32f
        private const val DISMISS_ANIMATION_MS = 170L
        private const val DISMISS_RETURN_MS = 180L
    }
}
