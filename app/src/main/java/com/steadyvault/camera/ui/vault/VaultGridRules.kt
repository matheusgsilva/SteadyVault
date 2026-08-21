package com.steadyvault.camera.ui.vault

object VaultGridRules {
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 5
    const val DEFAULT_COLUMNS = 3

    fun clamp(columns: Int): Int = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    /** Pinça abrindo deixa as mídias maiores; pinça fechando mostra mais itens. */
    fun afterScale(current: Int, scaleFactor: Float): Int = when {
        scaleFactor >= 1.12f -> clamp(current - 1)
        scaleFactor <= 0.88f -> clamp(current + 1)
        else -> clamp(current)
    }
}
