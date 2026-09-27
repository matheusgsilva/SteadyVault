package com.steadyvault.camera.capture.recorder

import java.util.ArrayDeque

internal data class EncodedSample(
    val data: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int
) {
    val storageBytes: Int get() = data.size
}

/**
 * Fila limitada entre o drain do MediaCodec e a escrita no MediaMuxer.
 *
 * O produtor nunca espera I/O de disco. Se o armazenamento ficar lento por alguns
 * segundos, os samples comprimidos ficam em RAM e os buffers do codec são liberados
 * imediatamente. O limite evita OOM: estourar a fila é tratado como falha explícita,
 * nunca como motivo para segurar um output buffer do encoder.
 */
internal class EncodedSampleWriteQueue(
    private val maxBytes: Long,
    private val maxSamples: Int
) {
    private val lock = Object()
    private val queue = ArrayDeque<EncodedSample>()
    private var bytes = 0L
    private var closed = false

    fun offer(sample: EncodedSample): Boolean = synchronized(lock) {
        if (closed) return false
        if (queue.size >= maxSamples || bytes + sample.storageBytes > maxBytes) return false
        queue.addLast(sample)
        bytes += sample.storageBytes
        lock.notifyAll()
        true
    }

    fun take(timeoutMs: Long): EncodedSample? = synchronized(lock) {
        if (queue.isEmpty() && !closed) {
            try {
                lock.wait(timeoutMs.coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val sample = queue.pollFirst() ?: return null
        bytes -= sample.storageBytes
        sample
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    fun isClosedAndEmpty(): Boolean = synchronized(lock) { closed && queue.isEmpty() }
    fun pendingBytes(): Long = synchronized(lock) { bytes }
    fun pendingSamples(): Int = synchronized(lock) { queue.size }
}

/**
 * Reaproveita arrays por classes de potência de 2 para não criar pressão de GC a
 * cada frame comprimido em 4K60.
 */
internal class EncodedSampleBufferPool(
    private val maxRetainedBytes: Long,
    private val maxReusableBufferBytes: Int
) {
    private val lock = Any()
    private val buckets = HashMap<Int, ArrayDeque<ByteArray>>()
    private var retainedBytes = 0L

    fun acquire(minBytes: Int): ByteArray {
        val bucketSize = bucketSize(minBytes.coerceAtLeast(1))
        synchronized(lock) {
            val bucket = buckets[bucketSize]
            val reused = bucket?.pollFirst()
            if (reused != null) {
                retainedBytes -= reused.size
                return reused
            }
        }
        return ByteArray(bucketSize)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size > maxReusableBufferBytes) return
        synchronized(lock) {
            if (retainedBytes + buffer.size > maxRetainedBytes) return
            buckets.getOrPut(buffer.size) { ArrayDeque() }.addLast(buffer)
            retainedBytes += buffer.size
        }
    }

    private fun bucketSize(value: Int): Int {
        if (value <= 1) return 1
        var size = 1
        while (size < value && size < (1 shl 30)) size = size shl 1
        return if (size >= value) size else value
    }
}
