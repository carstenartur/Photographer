package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * {@code --warmup N}, {@code --iterations N}, {@code --workers N}, and
 * {@code --dump FILE}. Boolean flags are {@code --checksum} and {@code --gc-between}.
 * The defaults are mode {@code default}, no warmup, one measured import, and an
 * automatic worker budget (zero).</p>
 *
 * <p>The {@code default} mode calls {@link ImageIO#read(java.io.File)} and keeps
 * its normal options; nonzero {@code --workers} is rejected for this mode.
 * {@code rgb8}, {@code rgb16}, and {@code rgb16linear} use an ImageIO reader with
 * explicit processing options. {@code sensor} uses the ImageIO sensor path,
 * while {@code sensor-path} opens the file directly through {@link Cr3Decoder}.</p>
 */
public final class Cr3DestinationProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private Cr3DestinationProbe() {
        // Command-line utility.
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> arguments = parseArguments(argv);
        Path file = Path.of(arguments.get("--input"));
        String mode = arguments.getOrDefault("--mode", "rgb16");
        if (!mode.equals("rgb16") && !mode.equals("rgb16linear")) {
            throw new IllegalArgumentException("Destination-copy probe requires rgb16 or rgb16linear");
        }
        if (!Set.of("default", "rgb8", "rgb16", "rgb16linear", "sensor", "sensor-path").contains(mode)) {
            throw new IllegalArgumentException("Unsupported --mode: " + mode
                    + "; expected default, rgb8, rgb16, rgb16linear, sensor, or sensor-path");
        }
        int warmup = integerOption(arguments, "--warmup", 0, 0);
        int iterations = integerOption(arguments, "--iterations", 1, 1);
        int workers = integerOption(arguments, "--workers", 0, 0);
        boolean checksum = arguments.containsKey("--checksum");
        if (mode.equals("default") && workers != 0) {
            throw new IllegalArgumentException("--mode default requires --workers 0; "
                    + "ImageIO.read(File) uses the reader's automatic worker budget");
        }

        ImageIO.scanForPlugins();
        if (!mode.equals("sensor-path")) {
            verifyFirstImageIoProvider(file);
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        for (int iteration = -warmup; iteration < iterations; iteration++) {
            if (arguments.containsKey("--gc-between")) {
                System.gc();
            }
            Map<Long, Long> before = allocated();
            long cpuStart = OS.getProcessCpuTime();
            long start = System.nanoTime();
            BufferedImage image = read(file, mode, workers);
            long elapsed = System.nanoTime() - start;
            long cpu = OS.getProcessCpuTime() - cpuStart;
            long allocated = allocated().entrySet().stream()
                    .mapToLong(entry -> Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0L)))
                    .sum();
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long maxRss = statusKb("VmHWM");
            validateOutput(image, mode);
            long hash = hash(image, checksum);

            if (iteration >= 0) {
                System.out.printf(Locale.ROOT,
                        "{\"engine\":\"Photographer\",\"input\":\"%s\",\"mode\":\"%s\","
                                + "\"iteration\":%d,\"warmup\":%d,\"workers\":%d,\"available_processors\":%d,"
                                + "\"width\":%d,\"height\":%d,\"channels\":%d,\"bits\":%d,"
                                + "\"total_ms\":%.6f,\"cpu_ms\":%.6f,\"allocated_bytes\":%d,"
                                + "\"heap_used_bytes\":%d,\"maxrss_kb\":%d,"
                                + "\"checksum_kind\":\"%s\",\"fnv1a64\":\"%016x\"}%n",
                        escape(file.toString()), mode, iteration, warmup, workers,
                        Runtime.getRuntime().availableProcessors(),
                        image.getWidth(), image.getHeight(), image.getRaster().getNumBands(),
                        image.getSampleModel().getSampleSize(0),
                        elapsed / 1e6, cpu / 1e6, allocated, heapUsed, maxRss,
                        checksum ? "full-u16le-or-u8" : "sampled-rows-u16le-or-u8", hash);
            }
            if (iteration == iterations - 1 && arguments.containsKey("--dump")) {
                dump(image, Path.of(arguments.get("--dump")));
            }
            Reference.reachabilityFence(image);
        }
    }

    private static void verifyFirstImageIoProvider(Path file) throws IOException {
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
        int expectedBits = mode.startsWith("sensor") || mode.startsWith("rgb16") ? 16 : 8;
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

    private static Map<String, String> parseArguments(String[] argv) {
        Set<String> valued = Set.of("--input", "--mode", "--warmup", "--iterations", "--workers", "--dump");
        Map<String, String> arguments = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String key = argv[i];
            if (arguments.containsKey(key)) {
                throw new IllegalArgumentException("Option specified more than once: " + key);
            }
            if (key.equals("--checksum") || key.equals("--gc-between")) {
                arguments.put(key, "true");
            } else if (valued.contains(key)) {
                if (i + 1 == argv.length || argv[i + 1].startsWith("--") || argv[i + 1].isEmpty()) {
                    throw new IllegalArgumentException("Missing value for " + key);
                }
                arguments.put(key, argv[++i]);
            } else {
                throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        if (!arguments.containsKey("--input")) {
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

    static BufferedImage read(Path file, String mode, int workers) throws Exception {
        if (mode.equals("default")) {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new IOException("No reader");
            }
            return image;
        }
        Cr3DecodeOptions options = mode.startsWith("sensor")
                ? Cr3DecodeOptions.rawSensor() : Cr3DecodeOptions.rawProcessed();
        options.maxWorkers(workers);
        if (mode.startsWith("rgb16")) {
            options.bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN);
        }
        if (mode.endsWith("linear")) {
            options.applySrgbConversion(false);
        }
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
                var param = new CanonCr3ImageReadParam();
                param.setDecodeOptions(options);
                var types = reader.getImageTypes(0);
                types.next(); // RGB8.
                if (mode.equals("rgb16linear")) types.next(); // Skip sRGB16.
                param.setDestinationType(types.next());
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
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
