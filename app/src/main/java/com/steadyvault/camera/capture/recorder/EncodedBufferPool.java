package com.steadyvault.camera.capture.recorder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Bounded, non-blocking handoff from codec drain to disk writer. No Android dependency. */
public final class EncodedBufferPool {
    private final long maxInFlightBytes;
    private final int maxInFlightCount;
    private final long maxCachedBytes;
    private final ArrayList<ByteBuffer> available = new ArrayList<>();
    // ByteBuffer.equals/hashCode depend on contents/position; ownership must use identity.
    private final IdentityHashMap<ByteBuffer, Boolean> inFlight = new IdentityHashMap<>();
    private long inFlightBytes;
    private long cachedBytes;
    private long allocations;
    private long peakInFlightBytes;
    private int peakInFlightCount;

    public EncodedBufferPool(long maxInFlightBytes, int maxInFlightCount, long maxCachedBytes) {
        if (maxInFlightBytes <= 0 || maxInFlightCount <= 0 || maxCachedBytes < 0) {
            throw new IllegalArgumentException("Invalid encoded buffer budget");
        }
        this.maxInFlightBytes = maxInFlightBytes;
        this.maxInFlightCount = maxInFlightCount;
        this.maxCachedBytes = maxCachedBytes;
    }

    /** Null means overload: caller must report failure, never wait while holding a codec buffer. */
    public synchronized ByteBuffer acquire(int size) {
        if (size <= 0) throw new IllegalArgumentException("Empty encoded sample");
        if (inFlight.size() >= maxInFlightCount || size > maxInFlightBytes - inFlightBytes) {
            return null;
        }
        int best = -1;
        for (int i = 0; i < available.size(); i++) {
            int capacity = available.get(i).capacity();
            if (capacity >= size && capacity <= maxInFlightBytes - inFlightBytes
                    && (best < 0 || capacity < available.get(best).capacity())) {
                best = i;
            }
        }
        ByteBuffer buffer;
        if (best >= 0) {
            buffer = available.remove(best);
            cachedBytes -= buffer.capacity();
        } else {
            // Buckets avoid allocation churn when compressed frame sizes fluctuate.
            long rounded = ((long) size + 65_535L) / 65_536L * 65_536L;
            int capacity = (int) Math.min(Integer.MAX_VALUE, rounded);
            if (capacity > maxInFlightBytes - inFlightBytes) capacity = size;
            buffer = ByteBuffer.allocateDirect(capacity);
            allocations++;
        }
        buffer.clear();
        buffer.limit(size);
        inFlight.put(buffer, Boolean.TRUE);
        inFlightBytes += buffer.capacity();
        peakInFlightBytes = Math.max(peakInFlightBytes, inFlightBytes);
        peakInFlightCount = Math.max(peakInFlightCount, inFlight.size());
        return buffer;
    }

    /** Return only after writeSampleData finishes, including the error path. */
    public synchronized void recycle(ByteBuffer buffer) {
        if (inFlight.remove(buffer) == null) {
            throw new IllegalArgumentException("Buffer was not leased or was recycled twice");
        }
        inFlightBytes -= buffer.capacity();
        buffer.clear();
        if (available.size() < maxInFlightCount && buffer.capacity() <= maxCachedBytes - cachedBytes) {
            available.add(buffer);
            cachedBytes += buffer.capacity();
        }
    }

    public synchronized long getInFlightBytes() { return inFlightBytes; }
    public synchronized int getInFlightCount() { return inFlight.size(); }
    public synchronized long getCachedBytes() { return cachedBytes; }
    public synchronized long getAllocations() { return allocations; }
    public synchronized long getPeakInFlightBytes() { return peakInFlightBytes; }
    public synchronized int getPeakInFlightCount() { return peakInFlightCount; }
}
