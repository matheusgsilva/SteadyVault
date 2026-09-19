package com.steadyvault.camera.ui.navigation

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.steadyvault.camera.R
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.settings.SettingsActivity
import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.ui.vault.PrimaryVaultActivity

object BottomNavigation {
    const val TAB_RECORD = 0
    const val TAB_LIBRARY = 1
    const val TAB_SETTINGS = 2

    fun bind(activity: Activity, currentTab: Int, onBeforeNavigate: (() -> Unit)? = null) {
        val root = activity.findViewById<View>(R.id.bottomNavigationRoot) ?: return
        val record = activity.findViewById<TextView>(R.id.navRecord) ?: return
        val library = activity.findViewById<TextView>(R.id.navLibrary) ?: return
        val settings = activity.findViewById<TextView>(R.id.navSettings) ?: return

        val items = listOf(
            Triple(record, R.drawable.ic_nav_record, currentTab == TAB_RECORD),
            Triple(library, R.drawable.ic_nav_vault, currentTab == TAB_LIBRARY),
            Triple(settings, R.drawable.ic_nav_settings, currentTab == TAB_SETTINGS)
        )
        items.forEach { (view, icon, selected) ->
            val palette = AppearanceStore.palette(activity)
            val textColor = if (selected) activity.getColor(R.color.text_primary) else activity.getColor(R.color.text_secondary)
            view.setTextColor(textColor)
            applyStableNavigationIcon(activity, view, icon, if (selected) palette.accent else textColor)
            normalizeNavigationItem(activity, view)
            view.setBackgroundResource(if (selected) R.drawable.bg_nav_selected else R.drawable.bg_nav_unselected)
            view.alpha = if (selected) 1f else 0.78f
            view.scaleX = if (selected) 1.02f else 1f
            view.scaleY = if (selected) 1.02f else 1f
            view.isClickable = !selected
            view.isFocusable = !selected
        }

        applyNavigationInset(root)
        record.setOnClickListener { animatePress(record); open(activity, CaptureActivity::class.java, currentTab == TAB_RECORD, onBeforeNavigate) }
        library.setOnClickListener { animatePress(library); open(activity, PrimaryVaultActivity::class.java, currentTab == TAB_LIBRARY, onBeforeNavigate) }
        settings.setOnClickListener { animatePress(settings); open(activity, SettingsActivity::class.java, currentTab == TAB_SETTINGS, onBeforeNavigate) }
    }

    private const val NAV_ITEM_WIDTH_DP = 72
    private const val NAV_ITEM_HEIGHT_DP = 60
    private const val NAV_ITEM_MARGIN_DP = 4
    private const val NAV_ICON_SIZE_DP = 22
    private const val NAV_ICON_TEXT_GAP_DP = 5

    private fun animatePress(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70L).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
        }.start()
    }

    private fun applyStableNavigationIcon(activity: Activity, view: TextView, iconRes: Int, color: Int) {
        val iconSize = dp(activity, NAV_ICON_SIZE_DP)
        val drawable = ContextCompat.getDrawable(activity, iconRes)?.mutate()?.apply {
            setBounds(0, 0, iconSize, iconSize)
        }
        view.setCompoundDrawables(null, drawable, null, null)
        view.compoundDrawableTintList = ColorStateList.valueOf(color)
        view.compoundDrawablePadding = dp(activity, NAV_ICON_TEXT_GAP_DP)
        view.includeFontPadding = false
    }

    private fun normalizeNavigationItem(activity: Activity, view: TextView) {
        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = dp(activity, NAV_ITEM_WIDTH_DP)
            params.height = dp(activity, NAV_ITEM_HEIGHT_DP)
            params.weight = 0f
            params.marginStart = dp(activity, NAV_ITEM_MARGIN_DP)
            params.marginEnd = dp(activity, NAV_ITEM_MARGIN_DP)
            view.layoutParams = params
        }
        view.gravity = Gravity.CENTER
        view.minWidth = dp(activity, NAV_ITEM_WIDTH_DP)
        view.minHeight = dp(activity, NAV_ITEM_HEIGHT_DP)
        view.maxHeight = dp(activity, NAV_ITEM_HEIGHT_DP)
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 5))
        view.compoundDrawablePadding = dp(activity, NAV_ICON_TEXT_GAP_DP)
        view.includeFontPadding = false
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt().coerceAtLeast(value)

    private fun applyNavigationInset(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            view.setPadding(initialLeft + bars.left, initialTop, initialRight + bars.right, initialBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun open(
        activity: Activity,
        target: Class<out Activity>,
        alreadyOpen: Boolean,
        onBeforeNavigate: (() -> Unit)?
    ) {
        if (alreadyOpen || activity.isFinishing || activity.isDestroyed) return
        runCatching { onBeforeNavigate?.invoke() }
            .onFailure { AppLogRepository.error(activity, "NAVIGATION", "Falha antes de navegar para ${target.simpleName}", it) }
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val result = runCatching {
            val options = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
            activity.startActivity(intent, options)
        }.recoverCatching { activity.startActivity(intent) }
        result.onFailure { error ->
            AppLogRepository.error(activity, "NAVIGATION", "Nao foi possivel abrir ${target.simpleName}", error)
            Toast.makeText(activity, "Não foi possível abrir esta tela agora.", Toast.LENGTH_LONG).show()
        }
    }
}
