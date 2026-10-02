package com.steadyvault.camera.core.camera

import android.graphics.Bitmap
import android.graphics.PointF

/**
 * Alvo de foco quando o detector de pose não acha ninguém: uma mão ou um pé (pele) que acaba de
 * entrar no quadro. O ML Kit de pose precisa de um corpo/rosto para ancorar e não vê uma mão ou
 * um pé sozinhos.
 *
 * Duas travas contra falso positivo (paredes bege, madeira, bichos de pelúcia amarelos):
 *  1. cor de pele ESTRITA (Cr alto, R/G >= 1,4, saturação >= 0,42): numa cena real a parede
 *     ficou em Cr 140–144 e R/G 1,15–1,23, a mão em Cr 157–165 e R/G 1,7–1,8;
 *  2. novidade: só vale pele que NÃO estava no quadro nas análises anteriores (modelo de fundo
 *     por célula). Os 2 primeiros quadros só aprendem o fundo.
 *
 * Mesmo algoritmo do protótipo validado em vídeos reais (mão sobre a tela: detectada; paredes e
 * pelúcia: nenhuma detecção). Não é thread-safe: chamar sempre da mesma thread de análise.
 */
internal class SkinBlobFinder {

    data class Blob(val x: Float, val y: Float, val areaFraction: Float)

    private var background: FloatArray? = null
    private var gridWidth = 0
    private var gridHeight = 0
    private var updates = 0

    fun reset() {
        background = null
        updates = 0
    }

    /** Atualiza o fundo e devolve o maior bloco novo de pele (coordenadas 0..1 do bitmap). */
    fun find(bitmap: Bitmap): Blob? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 32 || height < 32) return null
        val cell = (width / 32).coerceAtLeast(4)
        val gw = width / cell
        val gh = height / cell
        if (background == null || gw != gridWidth || gh != gridHeight) {
            background = null
            gridWidth = gw
            gridHeight = gh
            updates = 0
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val skinCount = IntArray(gw * gh)
        for (y in 0 until gh * cell) {
            val row = y * width
            val cellRow = (y / cell) * gw
            for (x in 0 until gw * cell) {
                if (isSkin(pixels[row + x])) skinCount[cellRow + x / cell]++
            }
        }
        val cellArea = cell * cell
        val skinCell = BooleanArray(gw * gh) { skinCount[it] * 2 > cellArea }

        val bg = background ?: FloatArray(gw * gh) { if (skinCell[it]) 1f else 0f }.also { background = it }
        val novel = BooleanArray(gw * gh) { skinCell[it] && bg[it] < 0.5f }
        for (i in bg.indices) bg[i] = 0.6f * bg[i] + 0.4f * (if (skinCell[i]) 1f else 0f)
        updates++
        if (updates <= LEARNING_UPDATES) return null

        // Maior componente conexa (4 vizinhos) de células novas.
        val visited = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        var bestSize = 0
        var bestX = 0f
        var bestY = 0f
        for (start in novel.indices) {
            if (!novel[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var sumX = 0f
            var sumY = 0f
            while (head < tail) {
                val index = queue[head++]
                val cx = index % gw
                val cy = index / gw
                sumX += cx + 0.5f
                sumY += cy + 0.5f
                if (cx > 0) tail = push(index - 1, novel, visited, queue, tail)
                if (cx < gw - 1) tail = push(index + 1, novel, visited, queue, tail)
                if (cy > 0) tail = push(index - gw, novel, visited, queue, tail)
                if (cy < gh - 1) tail = push(index + gw, novel, visited, queue, tail)
            }
            if (tail > bestSize) {
                bestSize = tail
                bestX = sumX / tail / gw
                bestY = sumY / tail / gh
            }
        }
        val total = (gw * gh).toFloat()
        if (bestSize < MIN_AREA * total || bestSize > MAX_AREA * total) return null
        return Blob(bestX, bestY, bestSize / total)
    }

    private fun push(index: Int, novel: BooleanArray, visited: BooleanArray, queue: IntArray, tail: Int): Int {
        if (!novel[index] || visited[index]) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun isSkin(argb: Int): Boolean {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 1f) return false
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        val saturation = (max - min) / max
        return cb > 77f && cb < 127f && cr > 150f && cr < 178f && y > 35f &&
            r > g * 1.4f && g > b && saturation > 0.42f
    }

    /** Converte o centro (espaço do bitmap) para o espaço "em pé" usado pelo detector de pose. */
    fun toUpright(blob: Blob, rotationDegrees: Int): PointF = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> PointF(1f - blob.y, blob.x)
        180 -> PointF(1f - blob.x, 1f - blob.y)
        270 -> PointF(blob.y, 1f - blob.x)
        else -> PointF(blob.x, blob.y)
    }

    private companion object {
        const val LEARNING_UPDATES = 2
        const val MIN_AREA = 0.03f
        const val MAX_AREA = 0.5f
    }
}
