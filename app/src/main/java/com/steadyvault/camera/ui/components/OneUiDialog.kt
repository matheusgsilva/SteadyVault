package com.steadyvault.camera.ui.components

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max
import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.ui.theme.AppearanceStore

object OneUiDialog {
    data class Choice(
        val title: String,
        val subtitle: String = "",
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val thumbnailUrl: String = ""
    )

    class ProgressHandle internal constructor(
        private val activity: Activity,
        private val dialog: Dialog,
        private val messageView: TextView,
        private val progressBar: ProgressBar
    ) {
        fun update(percent: Int, message: String) {
            activity.runOnUiThread {
                progressBar.isIndeterminate = percent < 0
                if (percent >= 0) progressBar.progress = percent.coerceIn(0, 100)
                messageView.text = message
            }
        }

        fun dismiss() {
            activity.runOnUiThread { if (dialog.isShowing) dialog.dismiss() }
        }

        fun isShowing(): Boolean = dialog.isShowing
    }

    fun message(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String = "Entendi",
        onPositive: (() -> Unit)? = null
    ): Dialog = build(
        activity = activity,
        title = title,
        message = message,
        positiveLabel = positiveLabel,
        negativeLabel = null,
        destructive = false,
        cancelable = true,
        onPositive = onPositive
    )

    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String,
        negativeLabel: String = "Cancelar",
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog = build(
        activity = activity,
        title = title,
        message = message,
        positiveLabel = positiveLabel,
        negativeLabel = negativeLabel,
        destructive = destructive,
        cancelable = true,
        onPositive = onConfirm
    )

    fun choices(
        activity: Activity,
        title: String,
        message: String? = null,
        choices: List<Choice>,
        selectedIndex: Int = -1,
        cancelLabel: String = "Fechar",
        confirmLabel: String? = null,
        thumbnailBinder: ((String, ImageView) -> Unit)? = null,
        onSelected: (Int) -> Unit
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val body = dialogBody(activity, dialog, title, message)
        val choiceContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, 0)
        }

        var currentSelection = selectedIndex.takeIf { it in choices.indices && choices[it].enabled } ?: -1
        val rows = mutableListOf<LinearLayout>()
        val markers = mutableListOf<TextView>()
        var confirmButton: TextView? = null
        var renderSelection: () -> Unit = {}
        choices.forEachIndexed { index, choice ->
            val selected = index == currentSelection
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 64)
                setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 10), dp(activity, 10))
                setBackgroundResource(if (selected) R.drawable.bg_oneui_choice_selected else R.drawable.bg_oneui_choice)
                alpha = if (choice.enabled) 1f else 0.38f
                isEnabled = choice.enabled
                isClickable = choice.enabled
            }
            if (choice.thumbnailUrl.isNotBlank() && thumbnailBinder != null) {
                val thumbnail = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "Miniatura de ${choice.title}"
                    setBackgroundResource(R.drawable.bg_media_tile)
                    clipToOutline = true
                }
                row.addView(thumbnail, LinearLayout.LayoutParams(dp(activity, 86), dp(activity, 64)).apply {
                    marginEnd = dp(activity, 12)
                })
                thumbnailBinder(choice.thumbnailUrl, thumbnail)
            }
            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = choice.title
                    setTextColor(activity.getColor(if (choice.destructive) R.color.record_red else R.color.text_primary))
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (choice.subtitle.isNotBlank()) addView(TextView(activity).apply {
                    text = choice.subtitle
                    setTextColor(activity.getColor(R.color.text_secondary))
                    textSize = 12f
                    setLineSpacing(0f, 1.08f)
                    setPadding(0, dp(activity, 4), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val marker = TextView(activity).apply {
                text = if (selected) "✓" else "›"
                gravity = Gravity.CENTER
                setTextColor(if (selected) AppearanceStore.palette(activity).accent else activity.getColor(R.color.text_muted))
                textSize = if (selected) 23f else 25f
                setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(marker, LinearLayout.LayoutParams(dp(activity, 42), ViewGroup.LayoutParams.MATCH_PARENT))
            row.setTapOnlyAction(activity, choice.enabled) {
                if (confirmLabel == null) {
                    dialog.dismiss()
                    onSelected(index)
                } else {
                    currentSelection = index
                    renderSelection()
                }
            }
            rows += row
            markers += marker
            choiceContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(activity, 8)
            })
        }
        renderSelection = {
            rows.forEachIndexed { index, row ->
                val selected = index == currentSelection
                row.setBackgroundResource(if (selected) R.drawable.bg_oneui_choice_selected else R.drawable.bg_oneui_choice)
                markers[index].apply {
                    text = if (selected) "✓" else "›"
                    setTextColor(if (selected) AppearanceStore.palette(activity).accent else activity.getColor(R.color.text_muted))
                    textSize = if (selected) 23f else 25f
                }
            }
            confirmButton?.apply {
                isEnabled = currentSelection in choices.indices && choices[currentSelection].enabled
                alpha = if (isEnabled) 1f else 0.42f
            }
        }
        body.addView(choiceContainer)
        if (confirmLabel == null) {
            body.addView(button(activity, cancelLabel, primary = false, destructive = false) { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)).apply { topMargin = dp(activity, 4) })
        } else {
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 4), 0, 0)
            }
            buttons.addView(button(activity, cancelLabel, primary = false, destructive = false) { dialog.dismiss() },
                LinearLayout.LayoutParams(0, dp(activity, 52), 1f).apply { marginEnd = dp(activity, 6) })
            confirmButton = button(activity, confirmLabel, primary = true, destructive = false) {
                val selected = currentSelection
                if (selected in choices.indices && choices[selected].enabled) {
                    dialog.dismiss()
                    onSelected(selected)
                }
            }
            buttons.addView(confirmButton, LinearLayout.LayoutParams(0, dp(activity, 52), 1f).apply { marginStart = dp(activity, 6) })
            body.addView(buttons)
            renderSelection()
        }

        val root = dialogRoot(activity, body)
        AppearanceRuntime.applyTo(activity, root)
        dialog.setContentView(root)
        showDialog(activity, dialog)
        return dialog
    }

    fun textInput(
        activity: Activity,
        title: String,
        message: String? = null,
        initialValue: String = "",
        hint: String = "",
        positiveLabel: String = "Salvar",
        negativeLabel: String = "Cancelar",
        maxLength: Int = 40,
        validate: (String) -> String? = { null },
        onSubmit: (String) -> Unit
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        val body = dialogBody(activity, dialog, title, message)
        val input = EditText(activity).apply {
            setText(initialValue)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_muted))
            textSize = 15f
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            setBackgroundResource(R.drawable.bg_oneui_field)
            selectAll()
        }
        val error = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.record_red))
            textSize = 12f
            visibility = View.GONE
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), 0)
        }
        body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)).apply {
            topMargin = dp(activity, 18)
        })
        body.addView(error, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 18), 0, 0)
        }
        buttons.addView(button(activity, negativeLabel, primary = false, destructive = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(activity, 54), 1f).apply { marginEnd = dp(activity, 8) })
        buttons.addView(button(activity, positiveLabel, primary = true, destructive = false) {
            val value = input.text?.toString().orEmpty().trim().replace(Regex("\\s+"), " ")
            val validation = validate(value)
            if (validation == null) {
                dialog.dismiss()
                onSubmit(value)
            } else {
                error.text = validation
                error.visibility = View.VISIBLE
                input.requestFocus()
            }
        }, LinearLayout.LayoutParams(0, dp(activity, 54), 1f).apply { marginStart = dp(activity, 8) })
        body.addView(buttons)
        val root = dialogRoot(activity, body)
        AppearanceRuntime.applyTo(activity, root)
        dialog.setContentView(root)
        showDialog(activity, dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return dialog
    }

    fun progress(
        activity: Activity,
        title: String,
        message: String,
        cancelable: Boolean = false,
        cancelLabel: String? = null,
        onCancel: (() -> Unit)? = null
    ): ProgressHandle {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(false)
        val body = dialogBody(activity, dialog, title, null)
        val messageView = TextView(activity).apply {
            text = message
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(activity, 4), 0, dp(activity, 18))
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(AppearanceStore.palette(activity).accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.surface_border))
        }
        body.addView(messageView)
        body.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 7)))
        if (cancelLabel != null && onCancel != null) {
            body.addView(button(activity, cancelLabel, primary = false, destructive = false) {
                onCancel()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50)).apply { topMargin = dp(activity, 18) })
        }
        val root = dialogRoot(activity, body)
        AppearanceRuntime.applyTo(activity, root)
        dialog.setContentView(root)
        showDialog(activity, dialog)
        return ProgressHandle(activity, dialog, messageView, progressBar)
    }

    private fun View.setTapOnlyAction(activity: Activity, enabled: Boolean, action: () -> Unit) {
        if (!enabled) return
        val touchSlop = max(activity.resources.displayMetrics.density * 4f, ViewConfiguration.get(activity).scaledTouchSlop * 0.55f)
        var startX = 0f
        var startY = 0f
        var startAt = 0L
        var moved = false
        setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startAt = event.eventTime
                    moved = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        moved = true
                        view.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val cleanTap = !moved && event.eventTime - startAt <= ViewConfiguration.getLongPressTimeout().toLong() && view.canActivateInGuardedScroll()
                    view.isPressed = false
                    if (cleanTap) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun build(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String,
        negativeLabel: String?,
        destructive: Boolean,
        cancelable: Boolean,
        onPositive: (() -> Unit)?
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        val body = dialogBody(activity, dialog, title, message)
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 20), 0, 0)
        }
        if (negativeLabel != null) {
            buttons.addView(button(activity, negativeLabel, primary = false, destructive = false) { dialog.dismiss() },
                LinearLayout.LayoutParams(0, dp(activity, 54), 1f).apply { marginEnd = dp(activity, 8) })
        }
        buttons.addView(button(activity, positiveLabel, primary = !destructive, destructive = destructive) {
            dialog.dismiss()
            onPositive?.invoke()
        }, LinearLayout.LayoutParams(0, dp(activity, 54), 1f).apply {
            if (negativeLabel != null) marginStart = dp(activity, 8)
        })
        body.addView(buttons)
        val root = dialogRoot(activity, body)
        AppearanceRuntime.applyTo(activity, root)
        dialog.setContentView(root)
        showDialog(activity, dialog)
        return dialog
    }

    private fun dialogBody(activity: Activity, dialog: Dialog, title: String, message: String?): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 22))
        background = activity.getDrawable(R.drawable.bg_oneui_dialog)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = title
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 14)
            })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_secondary))
                contentDescription = "Fechar"
                background = activity.getDrawable(R.drawable.bg_oneui_choice)
                setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 9), dp(activity, 9))
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!message.isNullOrBlank()) addView(TextView(activity).apply {
            text = message
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 13.5f
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(activity, 9), 0, 0)
        })
    }

    private fun dialogRoot(activity: Activity, content: View): View {
        val scroll = GuardedScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return FrameLayout(activity).apply {
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun button(activity: Activity, label: String, primary: Boolean, destructive: Boolean, action: () -> Unit): TextView = ScrollSafeTextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(activity.getColor(when {
            destructive -> R.color.record_red
            primary -> R.color.background
            else -> R.color.text_primary
        }))
        setBackgroundResource(when {
            destructive -> R.drawable.bg_oneui_button_danger
            primary -> R.drawable.bg_oneui_button_primary
            else -> R.drawable.bg_oneui_button_secondary
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun showDialog(activity: Activity, dialog: Dialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                setBackgroundBlurRadius(dp(activity, 56))
                attributes = attributes.apply {
                    dimAmount = 0.48f
                    blurBehindRadius = dp(activity, 34)
                }
            } else {
                attributes = attributes.apply { dimAmount = 0.70f }
            }
            val dialogWidth = minOf((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(activity, 520))
            setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            decorView.post {
                val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.90f).toInt()
                if (decorView.height > maxHeight) setLayout(dialogWidth, maxHeight)
            }
            setGravity(Gravity.CENTER)
        }
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
