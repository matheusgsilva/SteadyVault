package com.steadyvault.camera.ui.navigation

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceStore
import com.steadyvault.camera.ui.capture.CaptureActivity
import com.steadyvault.camera.ui.apps.ProtectedAppsActivity
import com.steadyvault.camera.ui.browser.PrivateBrowserActivity
import com.steadyvault.camera.ui.settings.SettingsActivity
import com.steadyvault.camera.ui.vault.PrimaryVaultActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object BottomNavigation {
    const val TAB_RECORD = 0
    const val TAB_LIBRARY = 1
    const val TAB_BROWSER = 2
    const val TAB_APPS = 3
    const val TAB_SETTINGS = 4

    fun bind(activity: Activity, currentTab: Int, onBeforeNavigate: (() -> Unit)? = null) {
        val root = activity.findViewById<View>(R.id.bottomNavigationRoot)
        val record = activity.findViewById<TextView>(R.id.navRecord)
        val library = activity.findViewById<TextView>(R.id.navLibrary)
        val browser = activity.findViewById<TextView>(R.id.navBrowser)
        val apps = activity.findViewById<TextView>(R.id.navApps)
        val settings = activity.findViewById<TextView>(R.id.navSettings)

        val items = listOf(
            Triple(record, R.drawable.ic_nav_record, currentTab == TAB_RECORD),
            Triple(library, R.drawable.ic_nav_vault, currentTab == TAB_LIBRARY),
            Triple(browser, R.drawable.ic_nav_browser, currentTab == TAB_BROWSER),
            Triple(apps, R.drawable.ic_nav_apps, currentTab == TAB_APPS),
            Triple(settings, R.drawable.ic_nav_settings, currentTab == TAB_SETTINGS)
        )
        items.forEach { (view, icon, selected) ->
            // A aba selecionada deve mudar somente a marcação verde.
            // Tamanho do ícone, espaçamento, texto e altura ficam idênticos aos itens não selecionados.
            val color = if (selected) AppearanceStore.palette(activity).accent else activity.getColor(R.color.text_secondary)
            view.setTextColor(color)
            applyStableNavigationIcon(activity, view, icon, color)
            normalizeNavigationItem(activity, view)
            view.setBackgroundResource(if (selected) R.drawable.bg_nav_selected else R.drawable.bg_nav_unselected)
            view.alpha = if (selected) 1f else 0.72f
            view.isClickable = !selected
            view.isFocusable = !selected
        }

        applyNavigationInset(root)
        record.setOnClickListener {
            open(activity, CaptureActivity::class.java, currentTab == TAB_RECORD, onBeforeNavigate)
        }
        library.setOnClickListener {
            open(activity, PrimaryVaultActivity::class.java, currentTab == TAB_LIBRARY, onBeforeNavigate)
        }
        browser.setOnClickListener {
            open(activity, PrivateBrowserActivity::class.java, currentTab == TAB_BROWSER, onBeforeNavigate)
        }
        apps.setOnClickListener {
            open(activity, ProtectedAppsActivity::class.java, currentTab == TAB_APPS, onBeforeNavigate)
        }
        settings.setOnClickListener {
            open(activity, SettingsActivity::class.java, currentTab == TAB_SETTINGS, onBeforeNavigate)
        }
    }


    private const val NAV_ITEM_WIDTH_DP = 64
    private const val NAV_ITEM_HEIGHT_DP = 58
    private const val NAV_ITEM_MARGIN_DP = 2
    private const val NAV_ICON_SIZE_DP = 22
    private const val NAV_ICON_TEXT_GAP_DP = 5

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
        view.setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        view.compoundDrawablePadding = dp(activity, NAV_ICON_TEXT_GAP_DP)
        view.includeFontPadding = false
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        view.setLineSpacing(0f, 1f)
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
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
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
        if (alreadyOpen) return
        onBeforeNavigate?.invoke()
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
        activity.startActivity(intent, options)
    }
}
