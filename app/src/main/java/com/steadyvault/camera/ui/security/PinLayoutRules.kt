package com.steadyvault.camera.ui.security

import kotlin.math.min

object PinLayoutRules {
    fun dialogHeightPx(screenHeightPx: Int, density: Float): Int =
        min((screenHeightPx * 0.88f).toInt(), dp(690, density))

    fun keySizePx(dialogWidthPx: Int, density: Float): Int {
        val availableWidth = dialogWidthPx - dp(96, density)
        return (availableWidth / 3).coerceIn(dp(58, density), dp(82, density))
    }

    fun keyHorizontalMarginPx(density: Float): Int = dp(7, density)
    fun unlockTopSpacingPx(density: Float): Int = dp(30, density)

    private fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
