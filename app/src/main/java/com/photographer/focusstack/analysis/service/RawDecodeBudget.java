package com.photographer.focusstack.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;

/** Process-wide admission budget for RAW decoding and file-backed stack processing. */
@Service
public final class RawDecodeBudget {

    private static final long MIB = 1L << 20;
    private final int workers;
    private final long maxBytes;
    private int availableWorkers;
    private long availableBytes;

    public RawDecodeBudget(
            @Value("${focusstack.processing.decode.max-workers:0}") int workers,
            @Value("${focusstack.processing.decode.max-bytes:0}") long maxBytes) {
        if (workers < 0 || maxBytes < 0) {
            throw new IllegalArgumentException("Decode limits must be non-negative");
        }
        Runtime runtime = Runtime.getRuntime();
        int cpuCount = runtime.availableProcessors();
        this.workers = workers == 0 ? cpuCount : Math.min(workers, cpuCount);
        this.maxBytes = maxBytes == 0 ? Math.min(runtime.maxMemory() / 4 * 3, 1024 * MIB) : maxBytes;
        this.availableWorkers = this.workers;
        this.availableBytes = this.maxBytes;
    }

    public int capacityWorkers() {
        return workers;
    }

    /** Allows a packed RGB raster, an orientation copy, and codec working buffers. */
    public static long estimateRawBytes(int width, int height) {
        return estimateRawBytes(width, height, true);
    }

    static long estimateRawBytes(int width, int height, boolean orientationCopy) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        return Math.addExact(Math.multiplyExact((long) width * height, orientationCopy ? 8 : 4), 8 * MIB);
    }

    public Lease acquire(long estimatedBytes, int preferredWorkers) throws IOException {
        return acquire(estimatedBytes, estimatedBytes, preferredWorkers);
    }

    synchronized Lease acquire(long minimumBytes, long preferredBytes, int preferredWorkers) throws IOException {
        if (minimumBytes < 1 || preferredBytes < minimumBytes || preferredWorkers < 1) {
            throw new IllegalArgumentException("Invalid decode reservation");
        }
        if (minimumBytes > maxBytes) {
            throw new LimitExceededException("Image processing needs an estimated " + minimumBytes
                    + " bytes, above the configured decode budget of " + maxBytes);
        }
        try {
            while (availableWorkers == 0 || availableBytes < minimumBytes) {
                wait();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while waiting for the decode budget");
        }
        int reservedWorkers = Math.min(preferredWorkers, availableWorkers);
        long reservedBytes = Math.min(preferredBytes, availableBytes);
        availableWorkers -= reservedWorkers;
        availableBytes -= reservedBytes;
        return new Lease(reservedWorkers, reservedBytes);
    }

    static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        private LimitExceededException(String message) {
            super(message);
        }
    }

    public final class Lease implements AutoCloseable {
        private final int workers;
        private final long bytes;
        private boolean closed;

        private Lease(int workers, long bytes) {
            this.workers = workers;
            this.bytes = bytes;
        }

        public int workers() {
            return workers;
        }

        long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            synchronized (RawDecodeBudget.this) {
                if (!closed) {
                    availableWorkers += workers;
                    availableBytes += bytes;
                    closed = true;
                    RawDecodeBudget.this.notifyAll();
                }
            }
        }
    }
}
