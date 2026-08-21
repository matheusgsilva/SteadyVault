package com.steadyvault.camera.ui.components

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewParent
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max

class GuardedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var blockChildClicksUntil = 0L

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        observeGesture(event)
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        observeGesture(event)
        return super.onTouchEvent(event)
    }

    fun canActivateChild(): Boolean = !moved && SystemClock.uptimeMillis() >= blockChildClicksUntil

    private fun observeGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!moved && dx * dx + dy * dy > touchSlop * touchSlop) moved = true
                if (moved) blockChildClicksUntil = max(blockChildClicksUntil, event.eventTime + CLICK_GUARD_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) blockChildClicksUntil = max(blockChildClicksUntil, event.eventTime + CLICK_GUARD_MS)
            }
        }
    }

    companion object {
        private const val CLICK_GUARD_MS = 180L
    }
}

class ScrollSafeSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.switchStyle
) : Switch(context, attrs, defStyleAttr) {
    private val tapSlop = max(context.resources.displayMetrics.density * 4f, ViewConfiguration.get(context).scaledTouchSlop * 0.55f)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downAt = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                moved = false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) {
                    moved = true
                    isPressed = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cleanTap = !moved && event.eventTime - downAt <= ViewConfiguration.getLongPressTimeout().toLong() && canActivateInGuardedScroll()
                isPressed = false
                if (cleanTap) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                isPressed = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

class ScrollSafeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {
    private val tapSlop = max(context.resources.displayMetrics.density * 4f, ViewConfiguration.get(context).scaledTouchSlop * 0.55f)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downAt = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                moved = false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) {
                    moved = true
                    isPressed = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cleanTap = !moved && event.eventTime - downAt <= ViewConfiguration.getLongPressTimeout().toLong() && canActivateInGuardedScroll()
                isPressed = false
                if (cleanTap) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                isPressed = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

internal fun View.canActivateInGuardedScroll(): Boolean {
    var current: ViewParent? = parent
    while (current != null) {
        if (current is GuardedScrollView) return current.canActivateChild()
        current = current.parent
    }
    return true
}
