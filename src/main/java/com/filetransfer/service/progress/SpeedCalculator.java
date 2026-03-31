package com.filetransfer.service.progress;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling-average speed calculator.
 *
 * Keeps a sliding window of (timestamp, bytes) samples.
 * Speed = total bytes in window / window duration.
 * This smooths out burst spikes better than an instant sample.
 *
 * One instance per active transfer — not shared across transfers.
 */
public class SpeedCalculator {

    private static final long WINDOW_MS = 5_000; // 5-second rolling window

    private record Sample(long timestampMs, long bytes) {
    }

    private final Deque<Sample> window = new ArrayDeque<>();
    private long totalBytes = 0;

    public synchronized void record(long bytes) {
        long now = System.currentTimeMillis();
        window.addLast(new Sample(now, bytes));
        totalBytes += bytes;
        evictOld(now);
    }

    public synchronized double getSpeedBps() {
        long now = System.currentTimeMillis();
        evictOld(now);
        if (window.size() < 2)
            return 0.0;
        long windowBytes = window.stream().mapToLong(Sample::bytes).sum();
        long windowMs = now - window.peekFirst().timestampMs();
        return windowMs > 0 ? (windowBytes * 1000.0 / windowMs) : 0.0;
    }

    public long getEtaSeconds(long remainingBytes) {
        double speed = getSpeedBps();
        if (speed <= 0)
            return -1;
        return (long) (remainingBytes / speed);
    }

    private void evictOld(long now) {
        while (!window.isEmpty() && (now - window.peekFirst().timestampMs()) > WINDOW_MS) {
            totalBytes -= window.pollFirst().bytes();
        }
    }
}