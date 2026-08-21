package com.steadyvault.camera.ui.gesture

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.steadyvault.camera.core.validation.UiBehaviorRules
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ZoomGestureController(
    context: Context,
    private val target: View,
    private val maxScale: Float,
    private val onSingleTap: (() -> Unit)? = null,
    private val onScaleChanged: ((Float) -> Unit)? = null,
    private val onDismiss: (() -> Unit)? = null
) : View.OnTouchListener {
    private val viewConfiguration = ViewConfiguration.get(context)
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var scale = 1f
    private var translationX = 0f
    private var translationY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var transformScheduled = false
    private var zoomAnimator: ValueAnimator? = null
    private var doubleTapConsumed = false
    private var downX = 0f
    private var downY = 0f
    private var dismissProgress = 0f
    private var dismissGestureActive = false
    private var dismissCallbackSent = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                stopAnimations()
                enableFastTransformLayer()
                if (dismissGestureActive || dismissProgress > 0f) resetDismissVisuals()
                target.parent?.requestDisallowInterceptTouchEvent(true)
                return target.width > 0 && target.height > 0
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                updateScale(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                velocityTracker?.clear()
                if (scale <= 1.02f) reset(animated = true) else enableFastTransformLayer()
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                target.performClick()
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                doubleTapConsumed = true
                enableFastTransformLayer()
                val destination = UiBehaviorRules.nextDoubleTapScale(scale, maxScale)
                animateScale(destination, event.x, event.y)
                return true
            }
        }
    )

    init {
        scaleDetector.isQuickScaleEnabled = false
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopAnimations()
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            lastX = event.x
            lastY = event.y
            downX = event.x
            downY = event.y
            dismissGestureActive = false
            dismissCallbackSent = false
            doubleTapConsumed = false
            if (scale <= 1.001f) {
                translationX = 0f
                translationY = 0f
                dismissProgress = 0f
                applyTransform()
            } else {
                target.alpha = 1f
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        val gestureHandled = gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker?.addMovement(event)
                enableFastTransformLayer()
                if (dismissGestureActive || dismissProgress > 0f) resetDismissVisuals()
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val totalX = event.x - downX
                    val totalY = event.y - downY
                    if (
                        scale <= 1.001f && onDismiss != null &&
                        (dismissGestureActive || (totalY > touchSlop && totalY > abs(totalX) * DISMISS_DIRECTION_RATIO))
                    ) {
                        dismissGestureActive = true
                        translationX = 0f
                        translationY = totalY.coerceAtLeast(0f)
                        dismissProgress = (translationY / (target.height.coerceAtLeast(1) * DISMISS_PROGRESS_DISTANCE))
                            .coerceIn(0f, 1f)
                        scheduleTransform()
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (scale > 1.001f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > 0.05f || abs(dy) > 0.05f) {
                            translationX += dx
                            translationY += dy
                            clampTranslation()
                            scheduleTransform()
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (dismissGestureActive) {
                    completeDismissGesture()
                } else {
                    if (!doubleTapConsumed && !gestureHandled) startFlingIfNeeded()
                    if (scale <= 1.001f) reset(animated = true)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                if (scale <= 1.001f && dismissProgress <= 0f) disableFastTransformLayer()
                view.parent?.requestDisallowInterceptTouchEvent(false)
                doubleTapConsumed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                if (dismissGestureActive) animateDismissReturn() else if (scale <= 1.001f) reset(animated = true)
                if (scale <= 1.001f && dismissProgress <= 0f) disableFastTransformLayer()
                view.parent?.requestDisallowInterceptTouchEvent(false)
                doubleTapConsumed = false
            }
        }
        return true
    }

    fun reset() = reset(animated = true)

    fun resetImmediately() = reset(animated = false)

    fun currentScale(): Float = scale

    fun zoomTo(requestedScale: Float, focusX: Float = target.width / 2f, focusY: Float = target.height / 2f) {
        animateScale(requestedScale.coerceIn(1f, maxScale), focusX, focusY)
    }

    fun zoomBy(delta: Float) {
        zoomTo(UiBehaviorRules.zoomStep(scale, delta, maxScale))
    }

    private fun reset(animated: Boolean) {
        if (!animated) {
            stopAnimations()
            scale = 1f
            translationX = 0f
            translationY = 0f
            dismissProgress = 0f
            dismissGestureActive = false
            target.alpha = 1f
            applyTransform()
            disableFastTransformLayer()
            onScaleChanged?.invoke(scale)
            return
        }
        animateScale(1f, target.width / 2f, target.height / 2f)
    }

    private fun updateScale(requestedScale: Float, focusX: Float, focusY: Float) {
        if (dismissGestureActive || dismissProgress > 0f) resetDismissVisuals()
        val nextScale = requestedScale.coerceIn(1f, maxScale)
        if (abs(nextScale - scale) < 0.0001f) return
        val ratio = nextScale / scale.coerceAtLeast(0.0001f)
        val centerX = target.width / 2f
        val centerY = target.height / 2f
        translationX = ratio * translationX + (1f - ratio) * (focusX - centerX)
        translationY = ratio * translationY + (1f - ratio) * (focusY - centerY)
        scale = nextScale
        clampTranslation()
        applyTransform()
        onScaleChanged?.invoke(scale)
    }

    private fun animateScale(targetScale: Float, focusX: Float, focusY: Float) {
        if (target.width <= 0 || target.height <= 0) return
        stopAnimations()
        val startScale = scale
        val startX = translationX
        val startY = translationY
        val endScale = targetScale.coerceIn(1f, maxScale)
        val ratio = endScale / startScale.coerceAtLeast(0.0001f)
        val centerX = target.width / 2f
        val centerY = target.height / 2f
        var endX = ratio * startX + (1f - ratio) * (focusX - centerX)
        var endY = ratio * startY + (1f - ratio) * (focusY - centerY)
        if (endScale <= 1.001f) {
            endX = 0f
            endY = 0f
        }

        enableFastTransformLayer()
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (endScale > startScale) DOUBLE_TAP_ZOOM_IN_MS else DOUBLE_TAP_ZOOM_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                scale = startScale + (endScale - startScale) * fraction
                translationX = startX + (endX - startX) * fraction
                translationY = startY + (endY - startY) * fraction
                clampTranslation()
                applyTransform()
                onScaleChanged?.invoke(scale)
            }
            doOnCompleted {
                if (scale <= 1.001f && dismissProgress <= 0f) disableFastTransformLayer()
            }
            start()
        }
    }

    private fun completeDismissGesture() {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val velocityY = tracker?.yVelocity ?: 0f
        val distanceThreshold = maxOf(target.height * DISMISS_DISTANCE_FRACTION, touchSlop * 6f)
        if (translationY >= distanceThreshold || velocityY >= minimumFlingVelocity * DISMISS_VELOCITY_MULTIPLIER) {
            animateDismissAway()
        } else {
            animateDismissReturn()
        }
    }

    private fun animateDismissAway() {
        if (dismissCallbackSent) return
        stopAnimations()
        val startY = translationY
        val startProgress = dismissProgress
        val endY = target.height.coerceAtLeast(1) * 1.05f
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                translationY = startY + (endY - startY) * fraction
                dismissProgress = startProgress + (1f - startProgress) * fraction
                applyTransform()
            }
            doOnCompleted {
                if (!dismissCallbackSent) {
                    dismissCallbackSent = true
                    onDismiss?.invoke()
                }
            }
            start()
        }
    }

    private fun animateDismissReturn() {
        stopAnimations()
        val startY = translationY
        val startProgress = dismissProgress
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_RETURN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                translationY = startY * (1f - fraction)
                dismissProgress = startProgress * (1f - fraction)
                applyTransform()
            }
            doOnCompleted { resetDismissVisuals() }
            start()
        }
    }

    private fun resetDismissVisuals() {
        dismissGestureActive = false
        dismissProgress = 0f
        translationY = 0f
        target.alpha = 1f
        applyTransform()
        if (scale <= 1.001f) disableFastTransformLayer()
    }

    private fun startFlingIfNeeded() {
        if (scale <= 1.001f) return
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val velocityX = tracker.xVelocity
        val velocityY = tracker.yVelocity
        if (abs(velocityX) < minimumFlingVelocity && abs(velocityY) < minimumFlingVelocity) return

        val bounds = translationBounds()
        scroller.fling(
            translationX.roundToInt(),
            translationY.roundToInt(),
            velocityX.roundToInt(),
            velocityY.roundToInt(),
            (-bounds.first).roundToInt(),
            bounds.first.roundToInt(),
            (-bounds.second).roundToInt(),
            bounds.second.roundToInt()
        )
        target.postOnAnimation(flingRunnable)
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            translationX = scroller.currX.toFloat()
            translationY = scroller.currY.toFloat()
            clampTranslation()
            scheduleTransform()
            target.postOnAnimation(this)
        }
    }

    private fun stopAnimations() {
        zoomAnimator?.cancel()
        zoomAnimator = null
        if (!scroller.isFinished) scroller.forceFinished(true)
        target.removeCallbacks(flingRunnable)
    }

    private fun scheduleTransform() {
        if (transformScheduled) return
        transformScheduled = true
        target.postOnAnimation {
            transformScheduled = false
            applyTransform()
        }
    }

    private fun applyTransform() {
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        val dismissScale = 1f - DISMISS_SCALE_REDUCTION * dismissProgress
        target.scaleX = scale * dismissScale
        target.scaleY = scale * dismissScale
        target.translationX = translationX
        target.translationY = translationY
        target.alpha = 1f - DISMISS_ALPHA_REDUCTION * dismissProgress
    }

    private fun clampTranslation() {
        val bounds = translationBounds()
        translationX = translationX.coerceIn(-bounds.first, bounds.first)
        translationY = translationY.coerceIn(-bounds.second, bounds.second)
    }

    private fun translationBounds(): Pair<Float, Float> {
        if (target.width <= 0 || target.height <= 0 || scale <= 1f) return 0f to 0f
        return target.width * (scale - 1f) / 2f to target.height * (scale - 1f) / 2f
    }

    private fun enableFastTransformLayer() {
        if (target.layerType != View.LAYER_TYPE_HARDWARE) {
            target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun disableFastTransformLayer() {
        if (target.layerType != View.LAYER_TYPE_NONE) {
            target.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private companion object {
        const val DISMISS_DIRECTION_RATIO = 1.25f
        const val DISMISS_PROGRESS_DISTANCE = 0.55f
        const val DISMISS_DISTANCE_FRACTION = 0.17f
        const val DISMISS_VELOCITY_MULTIPLIER = 4f
        const val DISMISS_SCALE_REDUCTION = 0.07f
        const val DISMISS_ALPHA_REDUCTION = 0.32f
        const val DISMISS_ANIMATION_MS = 170L
        const val DISMISS_RETURN_MS = 180L
        const val DOUBLE_TAP_ZOOM_IN_MS = 130L
        const val DOUBLE_TAP_ZOOM_OUT_MS = 120L
    }

}


private fun ValueAnimator.doOnCompleted(action: () -> Unit) {
    addListener(object : AnimatorListenerAdapter() {
        private var cancelled = false

        override fun onAnimationCancel(animation: Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: Animator) {
            if (!cancelled) action()
        }
    })
}
