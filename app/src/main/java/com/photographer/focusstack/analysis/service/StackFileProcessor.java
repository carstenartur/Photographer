package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Develops the originals and merges them in acquisition order within a shared working-set budget. */
@Service
public final class StackFileProcessor {
    private static final Logger log = LoggerFactory.getLogger(StackFileProcessor.class);
    private static final long MIB = 1L << 20;
    private final RawDecodeBudget budget;

    public StackFileProcessor(RawDecodeBudget budget) {
        this.budget = budget;
    }

    public enum Source {
        RAW, RASTER, EMBEDDED_PREVIEW
    }

    public record Result(byte[] png, Source source, int frameCount) {}

    public Result merge(List<OriginalFrame> originals) throws IOException {
        if (originals.isEmpty()) {
            throw new IllegalArgumentException("At least one original is required");
        }
        try {
            return merge(originals, true);
        } catch (RawDecodeException unavailable) {
            // Use one consistent development mode for the entire stack.
            log.info("Using embedded previews for stack development: {}", unavailable.getMessage());
            return merge(originals, false);
        }
    }

    private Result merge(List<OriginalFrame> originals, boolean preferRaw) throws IOException {
        List<Input> inputs = new ArrayList<>(originals.size());
        for (OriginalFrame frame : originals) {
            try {
                inputs.add(withReader(frame, reader -> {
                    boolean cr3 = reader instanceof CanonCr3ImageReader;
                    if (cr3 && preferRaw) {
                        return inspectRaw(frame);
                    }
                    Dimension size = cr3 ? withPreviewReader(frame, StackFileProcessor::imageDimensions)
                            : imageDimensions(reader);
                    return new Input(frame, size, cr3, false, 8L * size.width * size.height + 4 * MIB);
                }));
            } catch (RawDecodeException | InterruptedIOException failure) {
                throw failure;
            } catch (IOException unreadable) {
                log.warn("Skipping unreadable stack original {}", frame.fileName(), unreadable);
            }
        }
        if (inputs.isEmpty()) {
            throw new IOException("No readable stack originals");
        }
        Dimension dimensions = inputs.getFirst().size();
        long pixels = (long) dimensions.width * dimensions.height;
        if (pixels < 1 || pixels > Integer.MAX_VALUE) {
            throw new IOException("Unsupported stack dimensions: " + dimensions);
        }
        if (inputs.stream().anyMatch(input -> !dimensions.equals(input.size()))) {
            throw new IOException("All stack originals must have the same developed dimensions");
        }

        // The accumulator holds one packed output and one float score per pixel.
        long stackBytes = 8 * pixels + 8 * MIB;
        boolean hasRaw = inputs.stream().anyMatch(Input::raw);
        long frameBytes = inputs.stream().mapToLong(Input::estimatedBytes).max().orElseThrow();
        int cpuBudget = budget.capacityWorkers();
        int maxParallel = Math.min(cpuBudget, inputs.size());
        long preferredFrameSlots = Math.min(maxParallel, (Long.MAX_VALUE - stackBytes) / frameBytes);
        long preferredBytes = stackBytes + preferredFrameSlots * frameBytes;
        try (var lease = budget.acquire(stackBytes + frameBytes, preferredBytes, hasRaw ? cpuBudget : maxParallel)) {
            int slotsByMemory = (int) ((lease.bytes() - stackBytes) / frameBytes);
            int parallel = Math.min(maxParallel, Math.min(lease.workers(), slotsByMemory));
            Merged merged = mergeInOrder(inputs, parallel, lease.workers());
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(merged.image(), "png", output)) {
                throw new IOException("No PNG writer available for the merged stack");
            }
            return new Result(output.toByteArray(), merged.source(), merged.frameCount());
        } catch (RawDecodeBudget.LimitExceededException insufficient) {
            if (hasRaw) {
                throw new RawDecodeException(insufficient.getMessage(), insufficient);
            }
            throw insufficient;
        }
    }

    private static Merged mergeInOrder(List<Input> inputs, int parallel, int workerBudget) throws IOException {
        var pending = new ArrayDeque<Future<BufferedImage>>(parallel);
        try (var tasks = Executors.newFixedThreadPool(parallel)) {
            try {
                for (int i = 0; i < parallel; i++) {
                    pending.addLast(submit(tasks, inputs.get(i), workersForSlot(i, parallel, workerBudget)));
                }
                int next = parallel;
                int current = 0;
                int accepted = 0;
                Source source = Source.RASTER;
                ImageJStackMerger.ColorAccumulator accumulator = null;
                while (!pending.isEmpty()) {
                    Input input = inputs.get(current++);
                    BufferedImage image = readIfAvailable(pending.removeFirst(), input);
                    if (image != null) {
                        if (accumulator == null) {
                            accumulator = new ImageJStackMerger.ColorAccumulator(image);
                        }
                        accumulator.add(image);
                        accepted++;
                        if (input.cr3()) {
                            source = input.raw() ? Source.RAW : Source.EMBEDDED_PREVIEW;
                        }
                    }
                    image = null;
                    // Combining a finished frame uses a worker slot freed by its decoder.
                    if (next < inputs.size()) {
                        int workers = workersForSlot(next % parallel, parallel, workerBudget);
                        pending.addLast(submit(tasks, inputs.get(next++), workers));
                    }
                }
                if (accumulator == null) {
                    throw new IOException("No readable stack originals");
                }
                return new Merged(accumulator.image(), source, accepted);
            } catch (IOException | RuntimeException | Error failure) {
                tasks.shutdownNow();
                throw failure;
            }
        }
    }

    private static int workersForSlot(int slot, int slots, int budget) {
        int share = budget / slots;
        return slot < budget % slots ? share + 1 : share;
    }

    private static BufferedImage readIfAvailable(Future<BufferedImage> future, Input input) throws IOException {
        try {
            return await(future);
        } catch (RawDecodeException | InterruptedIOException failure) {
            throw failure;
        } catch (IOException unreadable) {
            log.warn("Skipping unreadable stack original {}", input.frame().fileName(), unreadable);
            return null;
        }
    }

    private static Future<BufferedImage> submit(ExecutorService tasks, Input input, int workers) {
        return tasks.submit(() -> {
            OriginalFrame frame = input.frame();
            if (input.raw()) {
                try (var decoder = new Cr3Decoder()) {
                    decoder.setInput(frame.path());
                    var options = Cr3DecodeOptions.rawProcessed().maxWorkers(workers);
                    assertDimensions(input, decoder.getRawProcessedBounds(options).getSize());
                    return decoder.decode(options);
                } catch (Cr3Exception | EOFException unavailable) {
                    throw new RawDecodeException(frame, unavailable);
                }
            }
            ReadOperation<BufferedImage> read = reader -> {
                assertDimensions(input, imageDimensions(reader));
                return reader.read(0);
            };
            return input.cr3() ? withPreviewReader(frame, read) : withReader(frame, read);
        });
    }

    private static void assertDimensions(Input input, Dimension current) throws IOException {
        if (!input.size().equals(current)) {
            throw new IOException("Frame dimensions changed while merging " + input.frame().fileName());
        }
    }

    private static BufferedImage await(Future<BufferedImage> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while processing stack originals");
        } catch (ExecutionException failure) {
            switch (failure.getCause()) {
                case IOException error -> throw error;
                case RuntimeException error -> throw error;
                case Error error -> throw error;
                default -> throw new IOException("Unable to decode a stack original", failure);
            }
        }
    }

    private Input inspectRaw(OriginalFrame frame) throws IOException {
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(frame.path());
            Dimension size = decoder.getRawProcessedBounds(Cr3DecodeOptions.rawProcessed()).getSize();
            var metadata = decoder.getMetadata();
            boolean orientationCopy = metadata != null && metadata.orientation() >= 2 && metadata.orientation() <= 8;
            return new Input(frame, size, true, true,
                    RawDecodeBudget.estimateRawBytes(size.width, size.height, orientationCopy, budget.capacityWorkers()));
        } catch (Cr3Exception | EOFException unavailable) {
            throw new RawDecodeException(frame, unavailable);
        }
    }

    private static Dimension imageDimensions(ImageReader reader) throws IOException {
        return new Dimension(reader.getWidth(0), reader.getHeight(0));
    }

    private static <T> T withReader(OriginalFrame frame, ReadOperation<T> operation) throws IOException {
        try (var input = new FileImageInputStream(frame.path().toFile())) {
            return withReader(frame, input, operation);
        }
    }

    private static <T> T withPreviewReader(OriginalFrame frame, ReadOperation<T> operation) throws IOException {
        try (var input = new FileImageInputStream(frame.path().toFile())) {
            byte[] jpeg = Cr3PreviewExtractor.extractPreviewBytes(input, Cr3Parser.parse(input));
            try (var preview = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
                // Inspect the JPEG header before reserving pixels; CR3 reader dimensions describe RAW.
                return withReader(frame, preview, operation);
            }
        } catch (Cr3Exception unavailable) {
            throw new IOException("Unable to read embedded preview of " + frame.fileName(), unavailable);
        }
    }

    private static <T> T withReader(OriginalFrame frame, ImageInputStream input, ReadOperation<T> operation)
            throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new IOException("No image reader available for " + frame.fileName());
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, false, true);
            return operation.read(reader);
        } finally {
            reader.dispose();
        }
    }

    private record Input(OriginalFrame frame, Dimension size, boolean cr3, boolean raw, long estimatedBytes) {}

    private record Merged(BufferedImage image, Source source, int frameCount) {}

    @FunctionalInterface
    private interface ReadOperation<T> {
        T read(ImageReader reader) throws IOException;
    }

    private static final class RawDecodeException extends IOException {
        private static final long serialVersionUID = 1L;

        private RawDecodeException(OriginalFrame frame, Exception cause) {
            this("Unable to develop RAW original " + frame.fileName(), cause);
        }

        private RawDecodeException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
