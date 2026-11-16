package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.cr3.decode.Cr3PreparedRaw;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoBackend;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.exceptions.TornadoBackendNotFound;
import uk.ac.manchester.tornado.api.exceptions.TornadoBailoutRuntimeException;
import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.ShortArray;
import uk.ac.manchester.tornado.api.types.arrays.TornadoNativeArray;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.photographer.focusstack.analysis.service.TornadoRawStackKernels.*;

/** Experimental GPU backend for developing active-area RAW frames and merging them in order. */
final class TornadoRawStack implements AutoCloseable {
    private static final String DEVICE_PROPERTY = "photographer.tornado.device";
    private static final String GPU_REQUIREMENT = "TornadoVM RAW stacking requires an OpenCL or CUDA GPU with FP64 support";
    private static final int GAMMA_SIZE = 256;
    private static final int PIXEL_BYTES = Short.BYTES + 2 * Integer.BYTES + 2 * Float.BYTES;

    private final TornadoDevice device;
    private final Buffers buffers;
    private final TornadoExecutionPlan plan;
    private TornadoExecutionResult lastResult;
    private boolean closed;

    TornadoRawStack(Cr3PreparedRaw first) {
        device = gpuDevice();
        Cr3RawImage raw = first.image();
        int pixels = Math.multiplyExact(raw.width(), raw.height());
        int maxRawHeight = Math.max(raw.width(), raw.height());
        checkDeviceMemory(device, pixels, maxRawHeight);
        Buffers allocated = new Buffers(first);
        try {
            plan = allocated.plan(device);
        } catch (RuntimeException | Error failure) {
            allocated.close();
            throw failure;
        }
        buffers = allocated;
    }

    void add(Cr3PreparedRaw frame) {
        ensureOpen();
        buffers.stage(frame, lastResult == null);
        try {
            lastResult = plan.execute();
        } catch (RuntimeException | Error failure) {
            // A failed execution can leave the resident winners partially updated.
            closeAfterFailure(failure);
            throw failure;
        }
    }

    BufferedImage image() {
        ensureOpen();
        if (lastResult == null) {
            throw new IllegalStateException("No RAW frames have been added");
        }
        try {
            lastResult.transferToHost(buffers.winner);
            return buffers.image();
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    private void closeAfterFailure(Throwable failure) {
        // An interrupted graph can still have a nonblocking transfer using a host segment.
        try {
            device.sync(plan.getId());
        } catch (RuntimeException | Error syncFailure) {
            failure.addSuppressed(syncFailure);
        }
        try {
            close();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The TornadoVM RAW stack is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try (buffers) {
                plan.close();
            } catch (TornadoExecutionPlanException failure) {
                throw new IllegalStateException("Could not release the TornadoVM stack plan", failure);
            }
        }
    }

    static TornadoDevice gpuDevice() {
        var runtime = TornadoRuntimeProvider.getTornadoRuntime();
        if (Boolean.parseBoolean(TornadoRuntimeProvider.getProperty("tornado.virtual.device", "false"))) {
            throw new IllegalStateException(GPU_REQUIREMENT + ". Set -Dtornado.virtual.device=false for execution");
        }
        if (Boolean.parseBoolean(TornadoRuntimeProvider.getProperty("tornado.recover.bailout", "false"))) {
            throw new IllegalStateException("Set -Dtornado.recover.bailout=false to require GPU execution");
        }
        String configured = System.getProperty(DEVICE_PROPERTY, "").trim();
        if (!configured.isEmpty() && !configured.equalsIgnoreCase("auto")) {
            String[] indexes = configured.split(":", -1);
            if (indexes.length != 2) {
                throw new IllegalArgumentException(DEVICE_PROPERTY + " expects backend:device indexes");
            }
            int backendIndex = Integer.parseInt(indexes[0]);
            int deviceIndex = Integer.parseInt(indexes[1]);
            if (backendIndex < 0 || backendIndex >= runtime.getNumBackends()) {
                throw new IllegalArgumentException("TornadoVM backend index is out of range: " + backendIndex);
            }
            TornadoBackend backend;
            try {
                backend = runtime.getBackend(backendIndex);
            } catch (TornadoBackendNotFound | TornadoBailoutRuntimeException missingBackend) {
                throw new IllegalStateException(GPU_REQUIREMENT + ". Backend " + backendIndex + " is unavailable",
                        missingBackend);
            }
            if (deviceIndex < 0 || deviceIndex >= backend.getNumDevices()) {
                throw new IllegalArgumentException("TornadoVM device index is out of range: " + deviceIndex);
            }
            TornadoDevice selected = backend.getDevice(deviceIndex);
            if (!supportsGpu(selected)) {
                throw new IllegalStateException(GPU_REQUIREMENT + ". Selected " + selected.getPhysicalDevice().getDeviceName());
            }
            return selected;
        }
        var unavailable = new IllegalStateException(GPU_REQUIREMENT);
        for (int i = 0; i < runtime.getNumBackends(); i++) {
            try {
                for (TornadoDevice candidate : runtime.getBackend(i).getAllDevices()) {
                    if (supportsGpu(candidate)) {
                        return candidate;
                    }
                }
            } catch (TornadoBackendNotFound | TornadoBailoutRuntimeException missingBackend) {
                unavailable.addSuppressed(missingBackend);
            }
        }
        throw unavailable;
    }

    private static boolean supportsGpu(TornadoDevice candidate) {
        TornadoVMBackendType backend = candidate.getTornadoVMBackend();
        return candidate.getDeviceType() == TornadoDeviceType.GPU
                && (backend == TornadoVMBackendType.OPENCL || backend == TornadoVMBackendType.CUDA)
                && candidate.getDeviceContext().isFP64Supported();
    }

    /** Native host buffer bytes, excluding the decoded Bayer and final heap RGB images. */
    static long hostBufferBytes(int pixels, int maxRawHeight) {
        return (long) pixels * PIXEL_BYTES + 2L * maxRawHeight * Integer.BYTES
                + (long) (SETTINGS_SIZE + GAMMA_SIZE) * Integer.BYTES + (long) COLOR_SIZE * Double.BYTES
                + 9 * TornadoNativeArray.ARRAY_HEADER;
    }

    private static void checkDeviceMemory(TornadoDevice device, int pixels, int maxRawHeight) {
        long total = hostBufferBytes(pixels, maxRawHeight);
        long largest = Math.max(Math.max((long) pixels * Integer.BYTES, 2L * maxRawHeight * Integer.BYTES),
                GAMMA_SIZE * Integer.BYTES) + TornadoNativeArray.ARRAY_HEADER;
        long global = device.getMaxGlobalMemory();
        long perBuffer = device.getMaxAllocMemory();
        if ((global > 0 && total > global) || (perBuffer > 0 && largest > perBuffer)) {
            throw new IllegalArgumentException("TornadoVM RAW stack needs %d device bytes and a %d-byte allocation on %s"
                    .formatted(total, largest, device.getDeviceName()));
        }
    }

    static final class Buffers implements AutoCloseable {
        final ShortArray raw;
        final IntArray settings;
        final IntArray black;
        final DoubleArray color;
        final IntArray gamma;
        final IntArray rgb;
        final FloatArray luminance;
        final IntArray winner;
        final FloatArray bestScore;

        private final int width;
        private final int height;
        private final Arena storage;

        Buffers(Cr3PreparedRaw first) {
            int[] size = dimensions(first);
            width = size[0];
            height = size[1];
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("RAW stack dimensions must be positive");
            }
            int pixels = Math.multiplyExact(width, height);
            int blackEntries = Math.multiplyExact(2, Math.max(width, height));
            storage = Arena.ofShared();
            try {
                raw = ShortArray.fromSegmentShallow(memory(pixels, Short.BYTES));
                settings = IntArray.fromSegmentShallow(memory(SETTINGS_SIZE, Integer.BYTES));
                black = IntArray.fromSegmentShallow(memory(blackEntries, Integer.BYTES));
                color = DoubleArray.fromSegmentShallow(memory(COLOR_SIZE, Double.BYTES));
                gamma = IntArray.fromSegmentShallow(memory(GAMMA_SIZE, Integer.BYTES));
                rgb = IntArray.fromSegmentShallow(memory(pixels, Integer.BYTES));
                luminance = FloatArray.fromSegmentShallow(memory(pixels, Float.BYTES));
                winner = IntArray.fromSegmentShallow(memory(pixels, Integer.BYTES));
                bestScore = FloatArray.fromSegmentShallow(memory(pixels, Float.BYTES));
                for (int i = 0; i < GAMMA_SIZE; i++) {
                    double srgb = SrgbConverter.linearToSrgb(i / 255.0);
                    gamma.set(i, Math.min(255, Math.max(0, (int) (srgb * 255 + 0.5))));
                }
            } catch (RuntimeException | Error failure) {
                storage.close();
                throw failure;
            }
        }

        private MemorySegment memory(int elements, int elementBytes) {
            return storage.allocate(TornadoNativeArray.ARRAY_HEADER + (long) elements * elementBytes, Long.BYTES);
        }

        private static int[] dimensions(Cr3PreparedRaw frame) {
            return ExifOrientationTransformer.outputDimensions(frame.image().width(), frame.image().height(), frame.orientation());
        }

        void stage(Cr3PreparedRaw frame, boolean firstFrame) {
            Cr3RawImage image = frame.image();
            int[] size = dimensions(frame);
            if (size[0] != width || size[1] != height) {
                throw new IllegalArgumentException("All RAW frames must have the same developed dimensions");
            }
            if (image.bitDepth() < 1 || image.bitDepth() > Short.SIZE) {
                throw new IllegalArgumentException("TornadoVM RAW stacking requires unsigned 16-bit or narrower Bayer samples");
            }
            settings.set(RAW_WIDTH, image.width());
            settings.set(RAW_HEIGHT, image.height());
            settings.set(OUTPUT_WIDTH, width);
            settings.set(OUTPUT_HEIGHT, height);
            settings.set(ORIENTATION, frame.orientation());
            settings.set(RED_PHASE, switch (image.bayerPattern()) {
                case RGGB -> 0;
                case BGGR -> 3;
                case GRBG -> 1;
                case GBRG -> 2;
            });
            settings.set(MAX_SAMPLE, image.maxValue());
            settings.set(WHITE_LEVEL, Math.max(1, frame.whiteLevel()));
            settings.set(FIRST_FRAME, firstFrame ? 1 : 0);
            for (int y = 0; y < image.height(); y++) {
                // Black levels use sensor coordinates, while Bayer interpolation uses the local phase.
                int sensorY = frame.sensorY() + y;
                black.set(2 * y, frame.blackLevel().levelAt(frame.sensorX(), sensorY, image.bayerPattern()));
                black.set(2 * y + 1, frame.blackLevel().levelAt(frame.sensorX() + 1, sensorY, image.bayerPattern()));
            }
            for (int channel = 0; channel < MATRIX; channel++) {
                color.set(channel, frame.whiteBalance()[channel]);
            }
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    color.set(MATRIX + 3 * row + column, frame.colorMatrix()[row][column]);
                }
            }
            int[] samples = image.data();
            for (int i = 0; i < samples.length; i++) {
                raw.set(i, (short) samples[i]);
            }
        }

        TornadoExecutionPlan plan(TornadoDevice device) {
            var graph = new TaskGraph("rawStack")
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, raw, settings, black, color)
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, gamma)
                    .task("correct", TornadoRawStackKernels::correct, raw, settings, black, color)
                    .task("develop", TornadoRawStackKernels::develop, raw, settings, color, gamma, rgb, luminance)
                    .task("select", TornadoRawStackKernels::select, rgb, luminance, settings, winner, bestScore)
                    .persistOnDevice(winner, bestScore);
            // Fused multiply-add would change the CPU's channel rounding and near-equal focus scores.
            return new TornadoExecutionPlan(graph.snapshot()).withDevice(device).withStrictFloatingPoint();
        }

        BufferedImage image() {
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            MemorySegment.ofArray(pixels).copyFrom(winner.getSegment());
            return image;
        }

        @Override
        public void close() {
            storage.close();
        }
    }
}
