package com.steadyvault.camera.capture.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedSampleWriteQueueTest {
    @Test
    fun preservesOrderAndTimestamps() {
        val queue = EncodedSampleWriteQueue(maxBytes = 1024L, maxSamples = 4)
        assertTrue(queue.offer(sample(1, 10L)))
        assertTrue(queue.offer(sample(2, 20L)))
        assertEquals(10L, queue.take(1L)?.presentationTimeUs)
        assertEquals(20L, queue.take(1L)?.presentationTimeUs)
    }

    @Test
    fun refusesOverflowInsteadOfBlockingProducer() {
        val queue = EncodedSampleWriteQueue(maxBytes = 8L, maxSamples = 8)
        assertTrue(queue.offer(sample(1, 10L, storage = 8)))
        assertFalse(queue.offer(sample(2, 20L, storage = 8)))
        assertEquals(1, queue.pendingSamples())
        assertEquals(8L, queue.pendingBytes())
    }

    @Test
    fun closeStillLetsWriterDrainQueuedSamples() {
        val queue = EncodedSampleWriteQueue(maxBytes = 64L, maxSamples = 4)
        queue.offer(sample(1, 10L))
        queue.close()
        assertFalse(queue.isClosedAndEmpty())
        assertEquals(10L, queue.take(1L)?.presentationTimeUs)
        assertTrue(queue.isClosedAndEmpty())
        assertNull(queue.take(1L))
    }

    @Test
    fun bufferPoolReusesReleasedBucket() {
        val pool = EncodedSampleBufferPool(
            maxRetainedBytes = 1024L,
            maxReusableBufferBytes = 1024
        )
        val first = pool.acquire(300)
        assertEquals(512, first.size)
        pool.release(first)
        val second = pool.acquire(400)
        assertTrue(first === second)
    }

    private fun sample(id: Int, ptsUs: Long, storage: Int = 4) =
        EncodedSample(ByteArray(storage) { id.toByte() }, storage, ptsUs, 0)
}
