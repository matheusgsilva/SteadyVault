package com.steadyvault.camera.ui.theme

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.steadyvault.camera.R
import kotlin.math.roundToInt

/**
 * Identidade visual central do SteadyVault.
 *
 * O efeito Liquid Glass usa transparência, gradientes e contornos leves sem blur contínuo
 * sobre o preview. Isso preserva a estabilidade da captura em 4K/alto FPS.
 */
object AppearanceRuntime {
    fun apply(activity: Activity) {
        val palette = AppearanceStore.palette(activity)
        val root = activity.window.decorView ?: return
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, root).apply {
            val light = isLight(palette.background)
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        root.post { applyTree(activity, root, palette) }
    }

    fun applyTo(activity: Activity, root: View) {
        applyTree(activity, root, AppearanceStore.palette(activity))
    }

    private fun applyTree(activity: Activity, view: View, palette: AppearanceStore.Palette) {
        val defaultAccent = activity.getColor(R.color.accent)
        val defaultBackground = activity.getColor(R.color.background)
        val defaultSurface = activity.getColor(R.color.surface)
        val defaultSurfaceHigh = activity.getColor(R.color.surface_high)
        val defaultSurfaceElevated = activity.getColor(R.color.surface_elevated)
        val entryName = runCatching {
            if (view.id != View.NO_ID) activity.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        val semanticAction = entryName.contains("record", true) ||
            entryName.contains("stop", true) ||
            entryName.contains("delete", true) ||
            entryName.contains("trash", true) ||
            entryName.contains("danger", true)
        val screenRoot = entryName.endsWith("Root", true) ||
            entryName.contains("ScreenRoot", true) ||
            entryName == "mainRoot"

        when (view) {
            is TextView -> {
                view.typeface = Typeface.create(fontFamily(AppearanceStore.font(activity)), view.typeface?.style ?: Typeface.NORMAL)
                if (sameColor(view.currentTextColor, defaultAccent)) view.setTextColor(palette.accent)
                view.compoundDrawableTintList?.let { tint ->
                    if (sameColor(tint.defaultColor, defaultAccent)) {
                        view.compoundDrawableTintList = ColorStateList.valueOf(palette.accent)
                    }
                }
            }
            is ImageView -> view.imageTintList?.let { tint ->
                if (sameColor(tint.defaultColor, defaultAccent)) {
                    view.imageTintList = ColorStateList.valueOf(palette.accent)
                }
            }
            is ProgressBar -> {
                view.progressTintList?.let {
                    if (sameColor(it.defaultColor, defaultAccent)) view.progressTintList = ColorStateList.valueOf(palette.accent)
                }
                view.indeterminateTintList?.let {
                    if (sameColor(it.defaultColor, defaultAccent)) view.indeterminateTintList = ColorStateList.valueOf(palette.accent)
                }
            }
        }

        if (view is Switch) applySwitchColors(activity, view, palette)

        val background = view.background
        if (background is ColorDrawable && sameColor(background.color, defaultBackground)) {
            view.background = if (screenRoot) screenGradient(palette) else ColorDrawable(palette.background)
        }
        if (background is GradientDrawable && background.shape == GradientDrawable.RECTANGLE && !semanticAction) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                when (background.color?.defaultColor) {
                    defaultSurface -> background.setColor(withAlpha(palette.surface, 0.80f))
                    defaultSurfaceHigh, defaultSurfaceElevated -> background.setColor(withAlpha(palette.surfaceHigh, 0.88f))
                }
            }
            background.cornerRadius = dp(activity, AppearanceStore.cornerRadiusDp(activity))
            val border = dp(activity, AppearanceStore.borderWidthDp(activity)).roundToInt()
            background.setStroke(border, if (border > 0) withAlpha(palette.border, 0.74f) else Color.TRANSPARENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            view.backgroundTintList?.defaultColor == defaultAccent &&
            !semanticAction
        ) {
            view.backgroundTintList = ColorStateList.valueOf(palette.accent)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTree(activity, view.getChildAt(i), palette)
        }
    }

    private fun screenGradient(palette: AppearanceStore.Palette): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                mix(palette.background, palette.accent, 0.055f),
                palette.background,
                mix(palette.background, palette.surface, 0.18f)
            )
        )

    private fun applySwitchColors(activity: Activity, view: Switch, palette: AppearanceStore.Palette) {
        val textPrimary = activity.getColor(R.color.text_primary)
        val textSecondary = activity.getColor(R.color.text_secondary)
        val contrast = AppearanceStore.contrast(activity)
        val uncheckedWeight = when (contrast) {
            AppearanceStore.CONTRAST_MAXIMUM -> 0.90f
            AppearanceStore.CONTRAST_STRONG -> 0.72f
            else -> 0.56f
        }
        val checkedTrack = palette.accent
        val checkedThumb = readableOnAccent(checkedTrack)
        val uncheckedTrack = mix(palette.surfaceHigh, textSecondary, uncheckedWeight)
        val uncheckedThumb = if (contrast == AppearanceStore.CONTRAST_MAXIMUM) {
            textPrimary
        } else {
            mix(textPrimary, palette.surfaceHigh, 0.08f)
        }
        val disabledCheckedTrack = mix(palette.surfaceHigh, palette.accent, if (contrast == AppearanceStore.CONTRAST_MAXIMUM) 0.52f else 0.42f)
        val disabledUncheckedTrack = mix(palette.surfaceHigh, textSecondary, if (contrast == AppearanceStore.CONTRAST_MAXIMUM) 0.36f else 0.22f)
        val disabledThumb = mix(palette.surfaceHigh, textSecondary, if (contrast == AppearanceStore.CONTRAST_MAXIMUM) 0.56f else 0.42f)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked)
        )
        view.thumbTintList = ColorStateList(states, intArrayOf(checkedThumb, uncheckedThumb, disabledThumb, disabledThumb))
        view.trackTintList = ColorStateList(states, intArrayOf(checkedTrack, uncheckedTrack, disabledCheckedTrack, disabledUncheckedTrack))
        view.splitTrack = false
        view.showText = false
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (255 * alpha.coerceIn(0f, 1f)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun mix(first: Int, second: Int, secondWeight: Float): Int {
        val weight = secondWeight.coerceIn(0f, 1f)
        val firstWeight = 1f - weight
        return Color.rgb(
            (Color.red(first) * firstWeight + Color.red(second) * weight).roundToInt(),
            (Color.green(first) * firstWeight + Color.green(second) * weight).roundToInt(),
            (Color.blue(first) * firstWeight + Color.blue(second) * weight).roundToInt()
        )
    }

    private fun fontFamily(value: String): String = when (value) {
        AppearanceStore.FONT_SYSTEM -> "sans"
        AppearanceStore.FONT_SERIF -> "serif"
        AppearanceStore.FONT_MONO -> "monospace"
        else -> "sans-serif"
    }

    private fun readableOnAccent(color: Int): Int {
        val luminance = (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
        return if (luminance > 0.62) Color.rgb(5, 15, 19) else Color.WHITE
    }

    private fun isLight(color: Int): Boolean =
        (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0 > 0.62

    private fun sameColor(first: Int, second: Int): Boolean = first == second
    private fun dp(activity: Activity, value: Float): Float = value * activity.resources.displayMetrics.density
}
