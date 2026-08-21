package com.steadyvault.camera.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultGridRulesTest {
    @Test fun defaultUsesLargerTiles() = assertEquals(3, VaultGridRules.DEFAULT_COLUMNS)
    @Test fun pinchOpenMakesTilesLarger() = assertEquals(2, VaultGridRules.afterScale(3, 1.2f))
    @Test fun pinchCloseShowsMoreTiles() = assertEquals(4, VaultGridRules.afterScale(3, 0.8f))
}
