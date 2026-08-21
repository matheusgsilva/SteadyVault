package com.steadyvault.camera.storage.vault

object VaultAreaId {
    const val PRIMARY = "primary"
    const val SECONDARY = "secondary"
    const val TERTIARY = "tertiary"
    val ALL: List<String> = listOf(PRIMARY, SECONDARY, TERTIARY)

    fun isValid(value: String): Boolean = value in ALL
}
