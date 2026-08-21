package com.steadyvault.camera.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Spinner
import kotlin.math.max

class OneUiSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.spinnerStyle
) : Spinner(context, attrs, defStyleAttr, MODE_DROPDOWN) {

    private val touchSlop = max(context.resources.displayMetrics.density * 4f, ViewConfiguration.get(context).scaledTouchSlop * 0.55f)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var movedWhilePressed = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                movedWhilePressed = false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    movedWhilePressed = true
                    isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downAt
                val shouldOpen = !movedWhilePressed && duration <= ViewConfiguration.getLongPressTimeout().toLong() && canActivateInGuardedScroll()
                isPressed = false
                if (shouldOpen) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                movedWhilePressed = false
                isPressed = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        val activity = context.findActivity() ?: return super.performClick()
        val choiceAdapter = adapter as? ChoiceSpinnerAdapter ?: return super.performClick()
        val options = choiceAdapter.snapshot()
        if (options.isEmpty()) return false
        OneUiDialog.choices(
            activity = activity,
            title = prompt?.toString()?.takeIf { it.isNotBlank() }
                ?: contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: "Escolher opção",
            choices = options.map {
                OneUiDialog.Choice(
                    title = it.label,
                    subtitle = if (!it.enabled && it.disabledReason.isNotBlank()) it.disabledReason else it.description,
                    enabled = it.enabled
                )
            },
            selectedIndex = choiceAdapter.selectedPosition,
            confirmLabel = "Aplicar"
        ) { position ->
            if (options[position].enabled && position != choiceAdapter.selectedPosition) {
                choiceAdapter.selectedPosition = position
                setSelection(position, true)
                post { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            }
        }
        return true
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
