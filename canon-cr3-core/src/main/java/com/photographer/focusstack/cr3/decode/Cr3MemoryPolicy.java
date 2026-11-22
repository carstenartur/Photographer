package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.raw.Cr3Workers;

/** Per-read, advisory heap planning. A snapshot is not an allocation reservation. */
final class Cr3MemoryPolicy {
    private static final long MIB = 1024 * 1024;

    private Cr3MemoryPolicy() {}

    /** Additional pixel storage for the read. Caller-owned buffers are already in the heap snapshot. */
    record ImageBytes(long normal, long compact, long retained) {
        static ImageBytes samples(long sites, int bytesPerSite) {
            long bytes = multiply(sites, bytesPerSite);
            return new ImageBytes(bytes, bytes, bytes);
        }

        static ImageBytes sampledRgb(boolean sixteenBit, long areaSites, long outputSites, boolean transpose) {
            long mosaic = multiply(areaSites, sixteenBit ? Short.BYTES : Integer.BYTES);
            long rgb = multiply(outputSites, sixteenBit ? 3 * Short.BYTES : Integer.BYTES);
            long visited = transpose ? multiply(Math.ceilDiv(outputSites, Long.SIZE), Long.BYTES) : 0;
            long peak = add(add(mosaic, rgb), visited);
            return new ImageBytes(peak, peak, rgb);
        }

        static ImageBytes rgb(boolean sixteenBit, long areaSites, long outputSites, boolean partial,
                              int orientation, boolean suppliedPackedSamples) {
            boolean transpose = orientation >= 5 && orientation <= 8;
            // Only complete decoder-owned dense images take the in-place transpose path.
            long visited = transpose && !partial ? multiply(Math.ceilDiv(outputSites, 64), Long.BYTES) : 0;
            if (sixteenBit) {
                long rgb = multiply(outputSites, 6);
                long withMosaic = add(rgb, multiply(areaSites, Short.BYTES));
                if (partial) {
                    return new ImageBytes(withMosaic, withMosaic, rgb);
                }
                // Compact development always reuses the complete native mosaic as the green bank.
                return new ImageBytes(orientation == 1 ? rgb : withMosaic, add(rgb, visited), rgb);
            }

            long packed = suppliedPackedSamples ? 0 : multiply(areaSites, Integer.BYTES);
            long oriented = transpose ? add(packed, multiply(outputSites, Integer.BYTES)) : packed;
            // A partial packed view uses the ordinary orientation path, with a separate transpose.
            long retained = transpose ? multiply(outputSites, Integer.BYTES) : packed;
            return new ImageBytes(oriented, partial ? oriented : add(packed, visited), retained);
        }
    }

    /** Codec and processing stages each clamp the shared worker limit to their own work items. */
    record Estimate(ImageBytes pixels, long fixedBytes, long codecBytesPerWorker, int codecItems,
                    long processingBytesPerWorker, int processingItems) {
        static Estimate forTrack(ImageBytes pixels, int width, int height, int tileWidth,
                                 boolean lossy, int processingWidth) {
            // Profile and directory validation precede planning. RAW retains two predictor rows;
            // C-RAW retains entropy windows and a bounded three-level wavelet cascade.
            int columns = Math.min(width, tileWidth) / 2;
            long codec = lossy ? 160L * 1024 + 64L * (columns + 16L) : 32L * 1024 + 8L * columns;
            int tilePlanes = (int) Math.min(Integer.MAX_VALUE, 4L * Math.ceilDiv(width, tileWidth));
            long processing = processingWidth == 0 ? 0 : 40L * (processingWidth + 2L);
            return new Estimate(pixels, MIB + 32L * height, codec, tilePlanes, processing,
                    processingWidth == 0 ? 0 : height);
        }

        long bytes(long imageBytes, int workers) {
            long codec = multiply(codecBytesPerWorker, Math.min(workers, codecItems));
            long processing = multiply(processingBytesPerWorker, Math.min(workers, processingItems));
            return add(add(imageBytes, fixedBytes), add(codec, processing));
        }

        long peakBytes(long imageBytes, int workers, long postDecode) {
            // A later destination coexists with the returned pixels. Codec rows, old Bayer
            // storage and orientation scratch from completed stages have left scope by then.
            return Math.max(bytes(imageBytes, workers), add(pixels.retained, postDecode));
        }

        /** Zero retains the requested normal path. Otherwise return the compact per-stage limit. */
        int compactWorkers(Cr3DecodeOptions requested, int availableWorkers, long availableBytes, long maximumBytes) {
            if (requested.memoryMode() == Cr3DecodeOptions.MemoryMode.FAST) {
                return 0;
            }
            int cap = workerCap(requested, availableWorkers);
            long reserve = Math.clamp(maximumBytes / 10, 8 * MIB, 64 * MIB);
            long budget = Math.max(0, availableBytes) - reserve;
            long postDecode = requested.postDecodeMemoryBytes();
            if (requested.memoryMode() == Cr3DecodeOptions.MemoryMode.AUTO
                    && peakBytes(pixels.normal, cap, postDecode) <= budget) {
                return 0;
            }
            // If even one worker cannot fit, try the smallest layout. The final image is unavoidable.
            int low = 1;
            int high = cap;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (peakBytes(pixels.compact, middle, postDecode) <= budget) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        private int workerCap(Cr3DecodeOptions requested, int availableWorkers) {
            int cap = Math.min(availableWorkers, Math.max(codecItems, processingItems));
            return requested.maxWorkers() == 0 ? cap : Math.min(cap, requested.maxWorkers());
        }
    }

    static Cr3DecodeOptions apply(Cr3DecodeOptions requested, Estimate estimate) {
        Runtime runtime = Runtime.getRuntime();
        long maximum = runtime.maxMemory();
        long available = maximum - (runtime.totalMemory() - runtime.freeMemory());
        int availableWorkers = Cr3Workers.count(0, Integer.MAX_VALUE);
        int workers = estimate.compactWorkers(requested, availableWorkers, available, maximum);
        if (workers == 0) {
            return requested;
        }
        long normal = estimate.peakBytes(estimate.pixels.normal, estimate.workerCap(requested, availableWorkers),
                requested.postDecodeMemoryBytes());
        boolean unknown = normal == Long.MAX_VALUE;
        String estimateText = unknown ? "unknown or unrepresentable" : normal + " bytes";
        String reason = requested.memoryMode() == Cr3DecodeOptions.MemoryMode.LOW_MEMORY
                ? "requested" : unknown ? "unknown or unrepresentable heap requirement" : "limited heap headroom";
        System.err.println("CR3 low-memory: " + reason + "; compact RAW processing, worker limit " + workers
                + ". Resolution and sample precision are preserved (estimated normal-path heap "
                + estimateText + ", available " + available + " bytes).");
        return requested.copy().maxWorkers(workers).memoryMode(Cr3DecodeOptions.MemoryMode.LOW_MEMORY);
    }

    // An invalid or unrepresentable estimate must never look small enough to fit.
    private static long add(long a, long b) {
        return a < 0 || b < 0 || a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static long multiply(long a, long b) {
        return a < 0 || b < 0 || (b != 0 && a > Long.MAX_VALUE / b) ? Long.MAX_VALUE : a * b;
    }
}
