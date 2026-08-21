package com.steadyvault.camera.photo.quality

import kotlin.math.abs

object PhotoQualityPolicy {
    data class Dimensions(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
        val longEdge: Int get() = maxOf(width, height)
        val shortEdge: Int get() = minOf(width, height)
    }

    fun selectHighestResolution(
        sizes: Collection<Dimensions>,
        maxPixels: Long
    ): Dimensions? {
        val valid = sizes
            .asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
            .toList()

        if (valid.isEmpty()) return null

        val withinLimit = valid.filter { it.pixels <= maxPixels }
        val preferredPool = withinLimit.ifEmpty {
            listOf(valid.minBy { abs(it.pixels - maxPixels) })
        }

        return preferredPool
            .filter(::isNearFourByThree)
            .maxWithOrNull(compareBy<Dimensions> { it.pixels }.thenBy { it.longEdge })
            ?: preferredPool.maxWithOrNull(
                compareBy<Dimensions> { it.pixels }.thenBy { it.longEdge }
            )
    }


    fun isNearFourByThree(size: Dimensions): Boolean {
        val ratio = size.longEdge.toDouble() / size.shortEdge.toDouble()
        return abs(ratio - FOUR_BY_THREE) <= ASPECT_TOLERANCE
    }

    private const val FOUR_BY_THREE = 4.0 / 3.0
    private const val ASPECT_TOLERANCE = 0.06
}
