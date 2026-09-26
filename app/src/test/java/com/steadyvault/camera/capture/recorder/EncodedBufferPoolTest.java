package com.steadyvault.camera.capture.recorder;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class EncodedBufferPoolTest {
    @Test public void reusesBuffersAcrossChangingSampleSizes() {
        EncodedBufferPool pool = new EncodedBufferPool(262144, 4, 262144);
        ByteBuffer first = pool.acquire(50000);
        assertNotNull(first);
        first.putInt(123);
        pool.recycle(first);
        for (int i = 0; i < 10000; i++) {
            ByteBuffer next = pool.acquire(1000 + i % 60000);
            assertSame(first, next);
            assertEquals(0, next.position());
            assertEquals(1000 + i % 60000, next.limit());
            pool.recycle(next);
        }
        assertEquals(1L, pool.getAllocations());
        assertEquals(0L, pool.getInFlightBytes());
    }

    @Test public void slowWriterCannotExceedCountOrByteBudget() {
        EncodedBufferPool countLimited = new EncodedBufferPool(1048576, 2, 131072);
        ByteBuffer a = countLimited.acquire(1);
        ByteBuffer b = countLimited.acquire(1);
        assertNull(countLimited.acquire(1));
        countLimited.recycle(a);
        assertNotNull(countLimited.acquire(1));
        countLimited.recycle(b);

        EncodedBufferPool byteLimited = new EncodedBufferPool(100000, 10, 100000);
        ByteBuffer c = byteLimited.acquire(65536);
        ByteBuffer d = byteLimited.acquire(34464);
        assertNotNull(c);
        assertNotNull(d);
        assertEquals(100000L, byteLimited.getInFlightBytes());
        assertNull(byteLimited.acquire(1));
        byteLimited.recycle(c);
        byteLimited.recycle(d);
        assertTrue(byteLimited.getCachedBytes() <= 100000L);
        assertNull(byteLimited.acquire(Integer.MAX_VALUE));
    }

    @Test public void sampleContentDoesNotAffectOwnership() {
        EncodedBufferPool pool = new EncodedBufferPool(131072, 2, 131072);
        ByteBuffer a = pool.acquire(10);
        ByteBuffer b = pool.acquire(10);
        assertNotSame(a, b);
        a.putInt(42).flip();
        b.putInt(42).flip();
        assertEquals(a, b);
        pool.recycle(a);
        assertEquals(1, pool.getInFlightCount());
        pool.recycle(b);
        assertEquals(0, pool.getInFlightCount());
        try { pool.recycle(a); fail("double recycle accepted"); }
        catch (IllegalArgumentException expected) { /* ownership invariant */ }
    }

    @Test public void cachedBuffersAndLargeKeyframesStayBounded() {
        EncodedBufferPool pool = new EncodedBufferPool(1048576, 8, 65536);
        ByteBuffer keyframe = pool.acquire(700000);
        assertNotNull(keyframe);
        pool.recycle(keyframe);
        assertEquals(0L, pool.getCachedBytes());
        ByteBuffer small = pool.acquire(1000);
        pool.recycle(small);
        assertEquals(65536L, pool.getCachedBytes());
        assertEquals(0L, pool.getInFlightBytes());
    }

    @Test public void concurrentWriterReceivesUnchangedSamplesInOrder() throws Exception {
        EncodedBufferPool pool = new EncodedBufferPool(1048576, 16, 1048576);
        ArrayBlockingQueue<ByteBuffer> queue = new ArrayBlockingQueue<>(8);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> {
                for (int i = 0; i < 5000; i++) {
                    ByteBuffer buffer = queue.poll(5, TimeUnit.SECONDS);
                    assertNotNull(buffer);
                    assertEquals(i, buffer.getInt());
                    assertEquals(~i, buffer.getInt());
                    pool.recycle(buffer);
                }
                return true;
            });
            for (int i = 0; i < 5000; i++) {
                ByteBuffer buffer = pool.acquire(8);
                assertNotNull(buffer);
                buffer.putInt(i).putInt(~i).flip();
                assertTrue(queue.offer(buffer, 5, TimeUnit.SECONDS));
            }
            assertTrue(writer.get(10, TimeUnit.SECONDS));
            assertEquals(0, pool.getInFlightCount());
            assertTrue(pool.getPeakInFlightBytes() <= 1048576L);
            assertTrue(pool.getAllocations() <= 16L);
        } finally {
            executor.shutdownNow();
        }
    }
}
