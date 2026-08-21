package com.steadyvault.camera.ui.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLayoutRulesTest {
    @Test
    fun largePhoneUsesCircular82DpKeys() {
        val density = 3f
        val keySize = PinLayoutRules.keySizePx((412 * density).toInt(), density)
        assertEquals((82 * density).toInt(), keySize)
    }

    @Test
    fun narrowPhoneKeepsKeysInsideThreeColumnGrid() {
        val density = 3f
        val dialogWidth = (320 * density).toInt()
        val keySize = PinLayoutRules.keySizePx(dialogWidth, density)
        val margin = PinLayoutRules.keyHorizontalMarginPx(density)
        val rowContentWidth = keySize * 3 + margin * 6
        val rowAvailableWidth = dialogWidth - (52 * density).toInt()
        assertTrue(rowContentWidth <= rowAvailableWidth)
    }

    @Test
    fun dialogHeightIsCappedAndUnlockSpacingIsStable() {
        val density = 3f
        assertEquals((690 * density).toInt(), PinLayoutRules.dialogHeightPx((1000 * density).toInt(), density))
        assertEquals((30 * density).toInt(), PinLayoutRules.unlockTopSpacingPx(density))
    }
}
