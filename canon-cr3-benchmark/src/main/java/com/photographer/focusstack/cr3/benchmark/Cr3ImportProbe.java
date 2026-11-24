package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.MemoryMode;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cross-engine full-file probe. Each import opens and closes its own reader/input.
 * Provider preflight, output-format validation, hashing, diagnostics and optional
 * PGM/PPM writing are outside the import timer. ImageIO modes open the input once
 * for provider preflight before any warmup or measured import; this can warm the
 * provider and file cache. The timer does not measure whole-process startup.
 * RSS is a Linux process-lifetime high-water mark; allocation counts include the
 * small measurement overhead and all live Java threads. Use JMH for statistical
 * comparisons, and fresh processes for first-import latency and attributable peak RSS.
 *
 * <p>Required option: {@code --input FILE}. Optional values are {@code --mode MODE},
 * {@code --warmup N}, {@code --iterations N}, {@code --workers N},
 * {@code --memory-mode AUTO|LOW_MEMORY|FAST}, {@code --orientation 1..8},
 * {@code --subsample-x N}, {@code --subsample-y N}, and {@code --dump FILE}.
 * Memory-mode values are case-insensitive. Sampling applies to the explicit RGB modes.
 * Boolean flags include {@code --checksum} and {@code --gc-between}. For full-size
 * {@code rgb16}, {@code rgb16camera}, or {@code rgb16linear}, either {@code --staged-destination} or
 * {@code --reuse-destination} allocates one advertised destination before timing. Only
 * {@code --reuse-destination} opts into using it as decoder workspace. Repeat
 * {@code --input FILE} to rotate through captures in order. When sharing a destination, all
 * inputs must have the same developed dimensions. Each import still opens a fresh reader.
 * The defaults are mode {@code default}, no warmup, one measured import, and an
 * automatic worker budget (zero), with automatic memory policy and camera orientation.</p>
 *
 * <p>The {@code default} mode calls {@link ImageIO#read(java.io.File)} and keeps
 * its normal options; nonzero {@code --workers}, a nonautomatic memory policy,
 * and orientation overrides are rejected for this mode.
 * {@code rgb8}, {@code rgb16}, and {@code rgb16linear} use an ImageIO reader with
 * explicit processing options. {@code rgb16camera} uses that reader with
 * {@link Cr3DecodeOptions#cameraRendered()}. {@code sensor} uses the ImageIO sensor path,
 * while {@code sensor-path} opens the file directly through {@link Cr3Decoder}.
 * Both sensor modes preserve sensor orientation and reject orientation overrides.</p>
 */
public final class Cr3ImportProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private Cr3ImportProbe() {
        // Command-line utility.
    }

    public static void main(String[] argv) throws Exception {
        List<Path> files = new ArrayList<>();
        Map<String, String> arguments = parseArguments(argv, files);
        String mode = arguments.getOrDefault("--mode", "default");
        int warmup = integerOption(arguments, "--warmup", 0, 0);
        int iterations = integerOption(arguments, "--iterations", 1, 1);
        int workers = integerOption(arguments, "--workers", 0, 0);
        MemoryMode memoryMode = memoryModeOption(arguments);
        int orientationOverride = integerOption(arguments, "--orientation", 0, 1);
        int xStep = integerOption(arguments, "--subsample-x", 1, 1);
        int yStep = integerOption(arguments, "--subsample-y", 1, 1);
        validateReadRequest(mode, workers, memoryMode, orientationOverride);
        if ((xStep != 1 || yStep != 1) && !mode.startsWith("rgb")) {
            throw new IllegalArgumentException("Source subsampling requires an explicit RGB mode, received " + mode);
        }
        boolean checksum = arguments.containsKey("--checksum");
        boolean workspace = arguments.containsKey("--reuse-destination");
        boolean staged = arguments.containsKey("--staged-destination");
        if (workspace && staged) {
            throw new IllegalArgumentException("Choose one of --reuse-destination and --staged-destination");
        }
        boolean reuse = workspace || staged;
        if (reuse && (!mode.startsWith("rgb16") || xStep != 1 || yStep != 1)) {
            throw new IllegalArgumentException("A supplied destination requires a full-size rgb16, rgb16camera, or rgb16linear read");
        }

        ImageIO.scanForPlugins();
        BufferedImage destination = null;
        if (!mode.equals("sensor-path")) {
            for (Path file : files) {
                destination = prepareInput(file, mode, orientationOverride, reuse, destination);
            }
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        for (int iteration = -warmup; iteration < iterations; iteration++) {
            Path file = files.get(Math.floorMod(iteration, files.size()));
            if (arguments.containsKey("--gc-between")) {
                System.gc();
            }
            Map<Long, Long> before = allocated();
            long totalAllocatedStart = THREADS.getTotalThreadAllocatedBytes();
            long cpuStart = OS.getProcessCpuTime();
            long start = System.nanoTime();
            BufferedImage image = read(file, mode, workers, memoryMode, orientationOverride, xStep, yStep,
                    destination, workspace);
            long elapsed = System.nanoTime() - start;
            long cpu = OS.getProcessCpuTime() - cpuStart;
            long totalAllocated = THREADS.getTotalThreadAllocatedBytes() - totalAllocatedStart;
            long allocated = allocated().entrySet().stream()
                    .mapToLong(entry -> Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0L)))
                    .sum();
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long maxRss = statusKb("VmHWM");
            validateOutput(image, mode);
            if (reuse && image != destination) {
                throw new IOException("The ImageIO reader did not return the supplied destination");
            }
            long hash = hash(image, checksum);

            if (iteration >= 0) {
                System.out.printf(Locale.ROOT,
                        "{\"engine\":\"Photographer\",\"input\":\"%s\",\"mode\":\"%s\","
                                + "\"iteration\":%d,\"warmup\":%d,\"workers\":%d,\"available_processors\":%d,"
                                + "\"requested_memory_mode\":\"%s\",\"orientation_override\":%d,"
                                + "\"subsample_x\":%d,\"subsample_y\":%d,\"reuse_destination\":%b,\"destination_workspace\":%b,"
                                + "\"width\":%d,\"height\":%d,\"channels\":%d,\"bits\":%d,"
                                + "\"total_ms\":%.6f,\"cpu_ms\":%.6f,\"allocated_bytes\":%d,\"total_thread_allocated_bytes\":%d,"
                                + "\"heap_used_bytes\":%d,\"maxrss_kb\":%d,"
                                + "\"checksum_kind\":\"%s\",\"fnv1a64\":\"%016x\"}%n",
                        escape(file.toString()), mode, iteration, warmup, workers,
                        Runtime.getRuntime().availableProcessors(), memoryMode.name(), orientationOverride,
                        xStep, yStep, reuse, workspace,
                        image.getWidth(), image.getHeight(), image.getRaster().getNumBands(),
                        image.getSampleModel().getSampleSize(0),
                        elapsed / 1e6, cpu / 1e6, allocated, totalAllocated, heapUsed, maxRss,
                        checksum ? "full-u16le-or-u8" : "sampled-rows-u16le-or-u8", hash);
            }
            if (iteration == iterations - 1 && arguments.containsKey("--dump")) {
                dump(image, Path.of(arguments.get("--dump")));
            }
            Reference.reachabilityFence(image);
        }
    }

    private static BufferedImage prepareInput(Path file, String mode, int orientationOverride,
                                              boolean reuse, BufferedImage destination) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                throw new IOException("Could not open ImageIO input for " + file);
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("No ImageIO reader found for " + file);
            }
            ImageReader reader = readers.next();
            try {
                requirePhotographerReader(reader);
                if (!reuse) {
                    return null;
                }
                stream.seek(0);
                reader.setInput(stream, false, false);
                applyOrientation(reader, orientationOverride);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (destination != null) {
                    if (destination.getWidth() != width || destination.getHeight() != height) {
                        throw new IllegalArgumentException("A shared destination requires equal developed dimensions: " + file);
                    }
                    return destination;
                }
                var colorSpace = ColorSpace.getInstance(mode.equals("rgb16linear")
                        ? ColorSpace.CS_LINEAR_RGB : ColorSpace.CS_sRGB);
                var types = reader.getImageTypes(0);
                while (types.hasNext()) {
                    var type = types.next();
                    if (type.getNumBands() == 3 && type.getBitsPerBand(0) == 16
                            && colorSpace.equals(type.getColorModel().getColorSpace())) {
                        return type.createBufferedImage(width, height);
                    }
                }
                throw new IOException("No advertised full-precision destination for " + mode);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void requirePhotographerReader(ImageReader reader) throws IOException {
        if (!(reader instanceof CanonCr3ImageReader)) {
            throw new IOException("Expected Photographer CanonCr3ImageReader; first ImageIO reader is "
                    + reader.getClass().getName());
        }
    }

    static void validateOutput(BufferedImage image, String mode) throws IOException {
        if (image == null) {
            throw new IOException("No image returned for --mode " + mode);
        }
        int expectedBands = mode.startsWith("sensor") ? 1 : 3;
        int expectedBits = mode.equals("rgb8") ? 8 : 16;
        var samples = image.getSampleModel();
        if (samples.getNumBands() != expectedBands) {
            throw new IOException("--mode " + mode + " requires " + expectedBands
                    + " bands; received " + samples.getNumBands());
        }
        for (int band = 0; band < expectedBands; band++) {
            if (samples.getSampleSize(band) != expectedBits) {
                throw new IOException("--mode " + mode + " requires " + expectedBits
                        + " bits in every band; band " + band + " has " + samples.getSampleSize(band));
            }
        }
    }

    private static Map<String, String> parseArguments(String[] argv, List<Path> files) {
        Set<String> valued = Set.of("--input", "--mode", "--warmup", "--iterations", "--workers", "--dump",
                "--memory-mode", "--orientation", "--subsample-x", "--subsample-y");
        Map<String, String> arguments = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String key = argv[i];
            if (arguments.containsKey(key)) {
                throw new IllegalArgumentException("Option specified more than once: " + key);
            }
            if (key.equals("--checksum") || key.equals("--gc-between") || key.equals("--reuse-destination")
                    || key.equals("--staged-destination")) {
                arguments.put(key, "true");
            } else if (valued.contains(key)) {
                if (i + 1 == argv.length || argv[i + 1].startsWith("--") || argv[i + 1].isEmpty()) {
                    throw new IllegalArgumentException("Missing value for " + key);
                }
                String value = argv[++i];
                if (key.equals("--input")) {
                    files.add(Path.of(value));
                } else {
                    arguments.put(key, value);
                }
            } else {
                throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("--input FILE is required");
        }
        return arguments;
    }

    private static int integerOption(Map<String, String> arguments, String key, int defaultValue, int minimum) {
        String value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer >= " + minimum + ": " + value,
                    exception);
        }
        throw new IllegalArgumentException(key + " must be an integer >= " + minimum + ": " + value);
    }

    private static MemoryMode memoryModeOption(Map<String, String> arguments) {
        String value = arguments.getOrDefault("--memory-mode", "AUTO");
        try {
            return MemoryMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("--memory-mode must be AUTO, LOW_MEMORY, or FAST: " + value,
                    exception);
        }
    }

    private static void validateReadRequest(String mode, int workers, MemoryMode memoryMode, int orientationOverride) {
        if (mode == null || !Set.of("default", "rgb8", "rgb16", "rgb16camera", "rgb16linear",
                "sensor", "sensor-path").contains(mode)) {
            throw new IllegalArgumentException("Unsupported --mode: " + mode
                    + "; expected default, rgb8, rgb16, rgb16camera, rgb16linear, sensor, or sensor-path");
        }
        if (workers < 0) {
            throw new IllegalArgumentException("--workers must be an integer >= 0: " + workers);
        }
        if (memoryMode == null) {
            throw new IllegalArgumentException("--memory-mode must be AUTO, LOW_MEMORY, or FAST");
        }
        if (orientationOverride < 0 || orientationOverride > 8) {
            throw new IllegalArgumentException("--orientation must be an integer from 1 through 8 when specified: "
                    + orientationOverride);
        }
        if (mode.equals("default") && (workers != 0 || memoryMode != MemoryMode.AUTO || orientationOverride != 0)) {
            throw new IllegalArgumentException("--mode default requires --workers 0, --memory-mode AUTO, "
                    + "and no --orientation override; ImageIO.read(File) uses the reader's normal options");
        }
        if (mode.startsWith("sensor") && orientationOverride != 0) {
            throw new IllegalArgumentException("--mode " + mode
                    + " preserves sensor orientation and does not support --orientation overrides");
        }
    }

    static BufferedImage read(Path file, String mode, int workers) throws Exception {
        return read(file, mode, workers, MemoryMode.AUTO, 0);
    }

    static BufferedImage read(Path file, String mode, int workers, MemoryMode memoryMode, int orientationOverride)
            throws Exception {
        return read(file, mode, workers, memoryMode, orientationOverride, 1, 1, null, false);
    }

    private static BufferedImage read(Path file, String mode, int workers, MemoryMode memoryMode,
                                      int orientationOverride, int xStep, int yStep, BufferedImage destination, boolean workspace)
            throws Exception {
        validateReadRequest(mode, workers, memoryMode, orientationOverride);
        if (mode.equals("default")) {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new IOException("No reader");
            }
            return image;
        }
        Cr3DecodeOptions options = switch (mode) {
            case "rgb16camera" -> Cr3DecodeOptions.cameraRendered();
            case "rgb16" -> Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN);
            case "rgb16linear" -> Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applySrgbConversion(false);
            case "sensor", "sensor-path" -> Cr3DecodeOptions.rawSensor();
            default -> Cr3DecodeOptions.rawProcessed();
        };
        options.maxWorkers(workers).memoryMode(memoryMode);
        if (mode.endsWith("-path")) {
            try (Cr3Decoder decoder = new Cr3Decoder()) {
                decoder.setInput(file);
                return decoder.decode(options);
            }
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) {
                throw new IOException("No stream");
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("No reader");
            }
            ImageReader reader = readers.next();
            try {
                requirePhotographerReader(reader);
                stream.seek(0);
                reader.setInput(stream, false, false);
                applyOrientation(reader, orientationOverride);
                var param = new CanonCr3ImageReadParam();
                param.setDecodeOptions(options);
                if (destination != null) {
                    param.setDestination(destination);
                    param.setUseDestinationAsWorkspace(workspace);
                }
                if (xStep != 1 || yStep != 1) {
                    param.setSourceSubsampling(xStep, yStep, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void applyOrientation(ImageReader reader, int orientationOverride) throws IOException {
        if (orientationOverride != 0) {
            var cr3 = (CanonCr3ImageReader) reader;
            cr3.getImageMetadata(0);
            var decoder = cr3.getDecoder();
            var metadata = decoder == null ? null : decoder.getMetadata();
            if (metadata == null) {
                throw new IOException("Cannot override orientation because CR3 metadata is unavailable");
            }
            metadata.setOrientation(orientationOverride);
        }
    }

    static Map<Long, Long> allocated() {
        long[] ids = THREADS.getAllThreadIds();
        long[] sizes = THREADS.getThreadAllocatedBytes(ids);
        Map<Long, Long> values = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (sizes[i] >= 0) {
                values.put(ids[i], sizes[i]);
            }
        }
        return values;
    }

    static long statusKb(String key) throws IOException {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return -1;
        }
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith(key + ":")) {
                return Long.parseLong(line.split("\\s+")[1]);
            }
        }
        return -1;
    }

    static long hash(BufferedImage image, boolean full) {
        Raster raster = image.getRaster();
        int width = image.getWidth();
        int height = image.getHeight();
        int step = full ? 1 : Math.max(1, height / 64);
        int bits = image.getSampleModel().getSampleSize(0);
        int[] row = new int[width * raster.getNumBands()];
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < height; y += step) {
            raster.getPixels(0, y, width, 1, row);
            for (int value : row) {
                hash = (hash ^ (value & 255)) * 0x100000001b3L;
                if (bits > 8) {
                    hash = (hash ^ (value >>> 8)) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    static void dump(BufferedImage image, Path path) throws IOException {
        Raster raster = image.getRaster();
        int width = image.getWidth();
        int height = image.getHeight();
        int bands = raster.getNumBands();
        int bits = image.getSampleModel().getSampleSize(0);
        try (var out = new BufferedOutputStream(Files.newOutputStream(path))) {
            String header = "P" + (bands == 1 ? 5 : 6) + "\n" + width + " " + height + "\n"
                    + (bits > 8 ? 65535 : 255) + "\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            int[] row = new int[width * bands];
            byte[] bytes = new byte[row.length * (bits > 8 ? 2 : 1)];
            for (int y = 0; y < height; y++) {
                raster.getPixels(0, y, width, 1, row);
                int offset = 0;
                for (int value : row) {
                    if (bits > 8) {
                        bytes[offset++] = (byte) (value >>> 8);
                    }
                    bytes[offset++] = (byte) value;
                }
                out.write(bytes);
            }
        }
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u00")
                                .append(Character.forDigit(character >>> 4, 16))
                                .append(Character.forDigit(character & 0xf, 16));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
