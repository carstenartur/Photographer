package com.photographer.focusstack.cr3.decode;

/** Per-read, advisory heap planning. A snapshot is not an allocation reservation. */
final class Cr3MemoryPolicy {
    private static final long MIB = 1024 * 1024;

    private Cr3MemoryPolicy() {}

    static Cr3DecodeOptions apply(Cr3DecodeOptions requested, long estimatedBytes) {
        Runtime runtime = Runtime.getRuntime();
        long maximum = runtime.maxMemory();
        long available = Math.max(0, maximum - (runtime.totalMemory() - runtime.freeMemory()));
        if (!useLowMemory(requested.memoryMode(), estimatedBytes, available, maximum)) {
            return requested;
        }
        String reason = requested.memoryMode() == Cr3DecodeOptions.MemoryMode.LOW_MEMORY
                ? "requested" : "limited heap headroom";
        System.err.println("CR3 low-memory: " + reason
                + "; using one worker and compact owned-image processing. Resolution and sample precision are preserved"
                + " (estimated normal-path heap " + estimatedBytes + " bytes, available " + available + " bytes).");
        return requested.copy().maxWorkers(1).memoryMode(Cr3DecodeOptions.MemoryMode.LOW_MEMORY);
    }

    static boolean useLowMemory(Cr3DecodeOptions.MemoryMode mode, long estimatedBytes,
                                long availableBytes, long maximumBytes) {
        // Leave room for metadata, allocator granularity, runtime activity and small lookup tables.
        // Reclaimable garbage is included in used heap: conservatively choosing the slower path is safe.
        long reserve = Math.max(8 * MIB, Math.min(64 * MIB, maximumBytes / 10));
        return switch (mode) {
            case LOW_MEMORY -> true;
            case FAST -> false;
            case AUTO -> estimatedBytes > Math.max(0, availableBytes - reserve);
        };
    }
}
