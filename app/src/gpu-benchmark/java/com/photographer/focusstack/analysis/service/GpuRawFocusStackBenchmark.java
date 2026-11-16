package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.decode.Cr3PreparedRaw;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Measures native uploads, GPU RAW development and merging, final download, and PNG writing. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class GpuRawFocusStackBenchmark {

    @Param({"eosr-iso-low.cr3"})
    public String series;

    private List<OriginalFrame> originals;
    private RawDecodeBudget budget;

    @Setup
    public void setup() throws Exception {
        var device = TornadoRawStack.gpuDevice();
        System.out.println("TornadoVM " + device.getTornadoVMBackend() + " GPU "
                + device.getPhysicalDevice().getDeviceName());
        originals = RawFocusStackBenchmark.originals(series);
        budget = new RawDecodeBudget(0, Long.getLong("focus.stack.maxBytes", 0L));
        byte[] cpu = RawFocusStackBenchmark.mergeCpu(new StackFileProcessor(budget), originals);
        verifyPixels(cpu, mergeGpu());
    }

    @Benchmark
    public byte[] mergeGpu() throws Exception {
        Rectangle bounds;
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(originals.getFirst().path());
            bounds = decoder.getRawProcessedBounds(Cr3DecodeOptions.rawProcessed());
        }
        int pixels = Math.multiplyExact(bounds.width, bounds.height);
        long frameBytes = RawDecodeBudget.estimateRawBytes(bounds.width, bounds.height, false, budget.capacityWorkers());
        // Native host arrays, packed result, and PNG writing all share the available memory.
        long stackBytes = TornadoRawStack.hostBufferBytes(pixels, Math.max(bounds.width, bounds.height))
                + 8L * pixels + (8L << 20);
        try (var lease = budget.acquire(stackBytes + frameBytes, stackBytes + 2 * frameBytes,
                budget.capacityWorkers())) {
            boolean prefetch = lease.workers() > 1 && lease.bytes() >= stackBytes + 2 * frameBytes;
            var image = mergeInOrder(bounds, lease.workers(), prefetch);
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("No PNG writer available for the merged stack");
            }
            return out.toByteArray();
        }
    }

    private BufferedImage mergeInOrder(Rectangle bounds, int workers, boolean prefetch) throws Exception {
        var current = prepare(originals.getFirst(), bounds, workers);
        try (var stack = new TornadoRawStack(current);
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < originals.size(); i++) {
                OriginalFrame next = i + 1 < originals.size() ? originals.get(i + 1) : null;
                // Allow a CPU worker for staging while the next decode runs.
                var pending = next != null && prefetch ? tasks.submit(() -> prepare(next, bounds, workers - 1)) : null;
                try {
                    stack.add(current);
                } catch (RuntimeException | Error failure) {
                    if (pending != null) {
                        pending.cancel(true);
                    }
                    throw failure;
                }
                current = null;
                if (next != null) {
                    current = pending == null ? prepare(next, bounds, workers) : pending.get();
                }
            }
            return stack.image();
        }
    }

    private static Cr3PreparedRaw prepare(OriginalFrame original, Rectangle bounds, int workers) throws Exception {
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(original.path());
            if (!bounds.equals(decoder.getRawProcessedBounds(Cr3DecodeOptions.rawProcessed()))) {
                throw new IOException("Unexpected developed dimensions for " + original.fileName());
            }
            return decoder.prepareRawDevelopment(workers);
        }
    }

    private static void verifyPixels(byte[] expected, byte[] actual) throws IOException {
        if (Arrays.equals(expected, actual)) {
            return;
        }
        var cpu = ImageIO.read(new ByteArrayInputStream(expected));
        var gpu = ImageIO.read(new ByteArrayInputStream(actual));
        if (cpu == null || gpu == null || cpu.getWidth() != gpu.getWidth() || cpu.getHeight() != gpu.getHeight()) {
            throw new IOException("GPU stack dimensions differ from CPU output");
        }
        int width = cpu.getWidth();
        int[] cpuRow = new int[width];
        int[] gpuRow = new int[width];
        for (int y = 0; y < cpu.getHeight(); y++) {
            cpu.getRGB(0, y, width, 1, cpuRow, 0, width);
            gpu.getRGB(0, y, width, 1, gpuRow, 0, width);
            int mismatch = Arrays.mismatch(cpuRow, gpuRow);
            if (mismatch >= 0) {
                throw new IOException("GPU stack pixel differs at " + mismatch + "," + y);
            }
        }
    }
}
