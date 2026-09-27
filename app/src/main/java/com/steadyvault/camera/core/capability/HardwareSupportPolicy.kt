package com.steadyvault.camera.core.capability

object HardwareSupportPolicy {
    enum class Support {
        SUPPORTED,
        UNVERIFIED,
        UNSUPPORTED
    }

    fun modeSupport(
        advertisedModes: Collection<Int>,
        metadataComplete: Boolean,
        requestedMode: Int,
        requestKeyAvailable: Boolean
    ): Support = when {
        requestedMode in advertisedModes -> Support.SUPPORTED
        metadataComplete -> Support.UNSUPPORTED
        requestKeyAvailable -> Support.UNVERIFIED
        else -> Support.UNSUPPORTED
    }

    fun shouldExpose(support: Support): Boolean = support != Support.UNSUPPORTED

    fun shouldExpose(support: Support, scanCompleted: Boolean): Boolean =
        shouldExpose(support) || !scanCompleted

    fun isSelectable(support: Support): Boolean = support != Support.UNSUPPORTED

    fun isSelectable(
        support: Support,
        scanCompleted: Boolean,
        hasSnapshot: Boolean
    ): Boolean = when (support) {
        Support.SUPPORTED -> true
        Support.UNVERIFIED -> !scanCompleted || hasSnapshot
        Support.UNSUPPORTED -> false
    }
}
