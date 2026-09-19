package com.steadyvault.camera.ui.security

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.steadyvault.camera.R
import com.steadyvault.camera.core.feedback.Haptics
import com.steadyvault.camera.ui.theme.AppearanceRuntime
import com.steadyvault.camera.ui.theme.AppearanceStore
import kotlin.math.min

object PinPadDialog {
    fun showVerify(
        activity: Activity,
        title: String = "Desbloquear cofre",
        subtitle: String = "Digite o PIN para acessar suas mídias privadas.",
        cancelable: Boolean = true,
        verify: (String) -> Boolean,
        onVerified: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        Pad(activity, title, subtitle, "Desbloquear", cancelable, onCancel) { pin, pad ->
            if (verify(pin)) {
                Haptics.success(activity)
                pad.dismiss()
                onVerified()
            } else {
                Haptics.error(activity)
                pad.showError("PIN incorreto. Tente novamente.")
                pad.clear()
            }
        }.show()
    }

    fun showCreate(
        activity: Activity,
        title: String = "Criar PIN do cofre principal",
        onCreated: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        var firstPin: String? = null
        Pad(activity, title, "Escolha de 4 a 12 números.", "Continuar", true, onCancel) { pin, pad ->
            val first = firstPin
            if (first == null) {
                firstPin = pin
                pad.update("Confirme o PIN", "Digite novamente para confirmar.", "Salvar PIN")
                pad.clear()
            } else if (first == pin) {
                Haptics.success(activity)
                pad.dismiss()
                onCreated(pin)
            } else {
                Haptics.error(activity)
                firstPin = null
                pad.update(title, "Os PINs não conferem. Crie novamente.", "Continuar")
                pad.showError("Os números digitados foram diferentes.")
                pad.clear()
            }
        }.show()
    }

    private class Pad(
        private val activity: Activity,
        title: String,
        subtitle: String,
        actionLabel: String,
        private val cancelable: Boolean,
        private val onCancel: (() -> Unit)?,
        private val onSubmit: (String, Pad) -> Unit
    ) {
        private val dialog = Dialog(activity)
        private val pin = StringBuilder()
        private lateinit var titleView: TextView
        private lateinit var subtitleView: TextView
        private lateinit var dotsRow: LinearLayout
        private lateinit var errorView: TextView
        private lateinit var actionView: TextView
        private val dotViews = mutableListOf<View>()

        init {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(cancelable)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnCancelListener { onCancel?.invoke() }
            val root = createContent(title, subtitle, actionLabel)
            AppearanceRuntime.applyTo(activity, root)
            dialog.setContentView(root)
        }

        fun show() {
            if (activity.isFinishing || activity.isDestroyed) return
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    setBackgroundBlurRadius(dp(64))
                    attributes = attributes.apply {
                        dimAmount = 0.50f
                        blurBehindRadius = dp(40)
                    }
                } else {
                    attributes = attributes.apply { dimAmount = 0.72f }
                }
                val width = min((activity.resources.displayMetrics.widthPixels * 0.97f).toInt(), dp(560))
                setLayout(width, PinLayoutRules.dialogHeightPx(activity.resources.displayMetrics.heightPixels, activity.resources.displayMetrics.density))
                setGravity(Gravity.CENTER)
            }
            updateDots()
        }

        fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
        fun clear() { pin.clear(); updateDots() }
        fun showError(message: String) { errorView.text = message; errorView.visibility = View.VISIBLE }
        fun update(title: String, subtitle: String, actionLabel: String) {
            titleView.text = title
            subtitleView.text = subtitle
            actionView.text = actionLabel
            errorView.visibility = View.GONE
        }

        private fun createContent(title: String, subtitle: String, actionLabel: String): View {
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val dialogWidth = min((screenWidth * 0.97f).toInt(), dp(560))
            val density = activity.resources.displayMetrics.density
            val horizontalMargin = PinLayoutRules.keyHorizontalMarginPx(density)
            val keySize = PinLayoutRules.keySizePx(dialogWidth, density)
            val utilityTextSize = if (keySize >= dp(78)) 12.5f else 11f
            val numericTextSize = if (keySize >= dp(78)) 29f else 27f

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(18), dp(16), dp(24))
                setBackgroundResource(R.drawable.bg_oneui_dialog)
            }

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleView = TextView(activity).apply {
                text = title
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 20f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            }
            header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(12)
            })
            header.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_secondary))
                contentDescription = "Fechar"
                setBackgroundResource(R.drawable.bg_oneui_choice)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                isClickable = true
                isFocusable = true
                setOnClickListener { this@Pad.dismiss(); onCancel?.invoke() }
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) })
            subtitleView = TextView(activity).apply {
                text = subtitle
                setTextColor(activity.getColor(R.color.text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.05f)
                setPadding(dp(8), dp(8), dp(8), 0)
            }
            dotsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(12))
            }
            repeat(MAX_PIN_LENGTH) {
                val dot = View(activity).apply { background = dotDrawable(false) }
                dotViews += dot
                dotsRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
            errorView = TextView(activity).apply {
                setTextColor(activity.getColor(R.color.record_red))
                textSize = 12f
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(dp(6), 0, dp(6), dp(6))
            }
            content.addView(header, matchWrap())
            content.addView(subtitleView, matchWrap())
            content.addView(dotsRow, matchWrap())
            content.addView(errorView, matchWrap())

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Cancelar", "0", "⌫")
            )
            rows.forEachIndexed { rowIndex, labels ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                }
                labels.forEach { label ->
                    row.addView(
                        keyView(label, numericTextSize, utilityTextSize),
                        LinearLayout.LayoutParams(keySize, keySize).apply {
                            marginStart = horizontalMargin
                            marginEnd = horizontalMargin
                        }
                    )
                }
                content.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keySize).apply {
                        topMargin = dp(if (rowIndex == 0) 6 else 10)
                    }
                )
            }

            actionView = TextView(activity).apply {
                text = actionLabel
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.background))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_oneui_button_primary)
                isEnabled = false
                alpha = 0.42f
                setOnClickListener { if (pin.length >= MIN_PIN_LENGTH) onSubmit(pin.toString(), this@Pad) }
            }
            content.addView(
                actionView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply {
                    topMargin = PinLayoutRules.unlockTopSpacingPx(density)
                    bottomMargin = dp(10)
                }
            )

            val scroll = ScrollView(activity).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isFillViewport = false
                clipToPadding = false
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            return FrameLayout(activity).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
        }

        private fun keyView(label: String, numericTextSize: Float, utilityTextSize: Float): TextView = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = when (label) {
                "Cancelar" -> utilityTextSize
                "⌫" -> 26f
                else -> numericTextSize
            }
            setTypeface(typeface, if (label.length == 1 && label[0].isDigit()) Typeface.NORMAL else Typeface.BOLD)
            setTextColor(activity.getColor(if (label == "Cancelar") R.color.text_secondary else R.color.text_primary))
            setBackgroundResource(if (label.length == 1 && label[0].isDigit()) R.drawable.bg_pin_key else R.drawable.bg_pin_key_utility)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                when (label) {
                    "Cancelar" -> if (cancelable) { dismiss(); onCancel?.invoke() }
                    "⌫" -> if (pin.isNotEmpty()) pin.deleteCharAt(pin.lastIndex)
                    else -> if (pin.length < MAX_PIN_LENGTH) pin.append(label)
                }
                Haptics.tap(activity)
                errorView.visibility = View.GONE
                updateDots()
            }
        }

        private fun updateDots() {
            dotViews.forEachIndexed { index, view ->
                view.visibility = if (index < maxOf(MIN_PIN_LENGTH, pin.length)) View.VISIBLE else View.GONE
                view.background = dotDrawable(index < pin.length)
            }
            val enabled = pin.length >= MIN_PIN_LENGTH
            actionView.isEnabled = enabled
            actionView.alpha = if (enabled) 1f else 0.42f
        }

        private fun dotDrawable(filled: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (filled) setColor(AppearanceStore.palette(activity).accent) else {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), activity.getColor(R.color.text_muted))
            }
        }

        private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    }

    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12
}
