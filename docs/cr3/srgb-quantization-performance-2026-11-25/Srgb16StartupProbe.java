package com.photographer.focusstack.cr3.processing;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/** First table use after common converter/logger initialization; one invocation per fresh JVM. */
public final class Srgb16StartupProbe {
    public static void main(String[] args) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        SrgbConverter.linearToSrgb(0.0);
        long allocated = threads.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        int code = Srgb16Encoder.encode(0.37) & 0xffff;
        long elapsed = System.nanoTime() - started;
        allocated = threads.getThreadAllocatedBytes(thread) - allocated;
        System.out.printf(Locale.ROOT,
                "{\"table_first_use_ms\":%.6f,\"thread_allocated_bytes\":%d,\"code\":%d}%n",
                elapsed / 1e6, allocated, code);
    }
}
