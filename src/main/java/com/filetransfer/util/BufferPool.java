package com.filetransfer.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Pool of reusable direct ByteBuffers for file I/O operations.
 *
 * Why direct buffers?
 * - Heap ByteBuffers (ByteBuffer.allocate) are managed by the JVM GC.
 *   For high-throughput file transfers, allocating and discarding large
 *   heap buffers creates significant GC pressure.
 * - Direct ByteBuffers (ByteBuffer.allocateDirect) are allocated outside
 *   the JVM heap in native memory. The OS can DMA directly into them,
 *   skipping one copy. They are expensive to allocate but cheap to reuse.
 *
 * This pool pre-allocates POOL_SIZE buffers at startup and lends them out.
 * Callers MUST call release() in a finally block or use try-with-resources.
 *
 * Usage:
 *   ByteBuffer buf = BufferPool.acquire();
 *   try {
 *       buf.clear();
 *       channel.read(buf);
 *       buf.flip();
 *       // ... use buf ...
 *   } finally {
 *       BufferPool.release(buf);
 *   }
 */
@Slf4j
public final class BufferPool {

    private static final int BUFFER_SIZE = 256 * 1024;    // 256 KB per buffer
    private static final int POOL_SIZE   = 16;            // 16 buffers = 4 MB total

    private static final BlockingQueue<ByteBuffer> POOL = new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        for (int i = 0; i < POOL_SIZE; i++) {
            POOL.offer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        }
        log.info("BufferPool initialised: {} x {}KB direct buffers ({} MB total)",
            POOL_SIZE, BUFFER_SIZE / 1024, (long) POOL_SIZE * BUFFER_SIZE / (1024 * 1024));
    }

    private BufferPool() {}

    /**
     * Borrow a buffer from the pool.
     * Blocks if all buffers are in use (back-pressure — prevents unbounded memory use).
     * Always call release() when done.
     */
    public static ByteBuffer acquire() {
        try {
            ByteBuffer buf = POOL.take();  // blocks until one is available
            buf.clear();
            return buf;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Fall back to a fresh heap buffer rather than crashing
            log.warn("BufferPool.acquire interrupted, allocating fallback heap buffer");
            return ByteBuffer.allocate(BUFFER_SIZE);
        }
    }

    /**
     * Return a buffer to the pool.
     * Direct buffers are not freed — they go back to the pool for reuse.
     * If the buffer is a fallback heap buffer (from interrupted acquire),
     * we just discard it — it will be GC'd normally.
     */
    public static void release(ByteBuffer buffer) {
        if (buffer.isDirect()) {
            buffer.clear();
            if (!POOL.offer(buffer)) {
                log.warn("BufferPool is full — discarding returned buffer");
            }
        }
        // Non-direct fallback buffers are simply dropped here
    }

    public static int bufferSize() { return BUFFER_SIZE; }
}