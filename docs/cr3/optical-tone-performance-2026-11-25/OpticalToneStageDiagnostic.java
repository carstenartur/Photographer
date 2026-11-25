import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.processing.CameraToneCurve;
import com.photographer.focusstack.cr3.processing.LensProfiles;
import com.photographer.focusstack.cr3.processing.OpticalCorrection;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Scratch-only, warmed stage diagnostic. It is deliberately not an import benchmark. */
public final class OpticalToneStageDiagnostic {
    private static final String BASELINE = "90e5ef4bb6286103845f08f3fd548f3fae88059a14a9dd91270ff6f1bb29aba8";
    private static final String CANDIDATE = "d61b7edcbd1b3f64f5e1051cb31d113717a4c37c4adc3ef474860a35ccdf887a";

    private enum Mode {
        COMBINED, SEQUENTIAL, OPTICAL, TONE, KERNEL_ALIAS, KERNEL_SPLIT;

        boolean toneInput() {
            return this == TONE || this == KERNEL_ALIAS || this == KERNEL_SPLIT;
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: OpticalToneStageDiagnostic "
                    + "combined|sequential|optical|tone|kernel-alias|kernel-split INPUT WARMUP ITERATIONS WORKERS");
        }
        Mode mode = Mode.valueOf(args[0].replace('-', '_').toUpperCase(Locale.ROOT));
        Path input = Path.of(args[1]).toAbsolutePath();
        int warmup = Integer.parseInt(args[2]), iterations = Integer.parseInt(args[3]);
        int workers = Integer.parseInt(args[4]);
        if (warmup < 1 || iterations < 1 || workers < 0) {
            throw new IllegalArgumentException("This warmed diagnostic requires warmup >= 1, iterations >= 1, workers >= 0");
        }
        Path artifact = Path.of(CameraToneCurve.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String artifactHash = hashFile(artifact);
        if (!BASELINE.equals(artifactHash) && !CANDIDATE.equals(artifactHash)) {
            throw new IllegalArgumentException("Expected one of the two frozen JARs; found " + artifactHash);
        }
        String inputHash = hashFile(input);
        Method combined = findCombined();
        if (CANDIDATE.equals(artifactHash) != (combined != null)) {
            throw new IllegalStateException("Frozen artifact and combined-method availability disagree");
        }

        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            String camera = metadata.cameraModel();
            int style = metadata.pictureStyle();
            if (!CameraToneCurve.supports(camera, style)) {
                throw new IllegalArgumentException("This diagnostic requires supported camera tone");
            }
            OpticalCorrection lens = LensProfiles.find(camera, metadata.lensModel(), metadata.focalLengthMm(),
                    metadata.fNumber(), metadata.focusDistanceMetres(), metadata.visibleWidth(),
                    metadata.visibleHeight()).orElseThrow();
            BufferedImage template = decoder.decode(Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applyLensCorrection(false).applyCameraTone(false).maxWorkers(workers));
            String neutralHash = hashPixels(template);
            // Tone-only modes use the actual corrected codes, never an invented distribution.
            if (mode.toneInput()) lens.applyToOwned(template, true, workers, 1);
            String stageInputHash = hashPixels(template);
            short[][] original = denseBands(template);
            int sites = Math.multiplyExact(template.getWidth(), template.getHeight());
            var data = new DataBufferUShort(new short[3][sites], sites);
            var raster = Raster.createBandedRaster(data, template.getWidth(), template.getHeight(),
                    template.getWidth(), new int[]{0, 1, 2}, new int[3], null);
            BufferedImage image = new BufferedImage(template.getColorModel(), raster, false, null);
            short[][] destination = denseBands(image);
            short[] splitBlue = mode == Mode.KERNEL_SPLIT ? new short[sites] : null;

            // Oracle and lazy table initialization are outside every timed iteration.
            reset(original, destination, splitBlue);
            if (mode == Mode.OPTICAL) {
                lens.applyToOwned(image, true, workers, 1);
            } else {
                if (!mode.toneInput()) lens.applyToOwned(image, true, workers, 1);
                if (!CameraToneCurve.apply(image, camera, style, workers)) throw new AssertionError("Missing tone");
            }
            String expectedHash = hashPixels(image);
            Action action;
            String implementation;
            if (mode == Mode.COMBINED && combined != null) {
                Object[] arguments = {image, true, workers, 1.0, camera, style};
                action = () -> invoke(combined, lens, arguments);
                implementation = "fused-public-reflection";
            } else if (mode == Mode.COMBINED || mode == Mode.SEQUENTIAL) {
                action = () -> {
                    lens.applyToOwned(image, true, workers, 1);
                    if (!CameraToneCurve.apply(image, camera, style, workers)) throw new AssertionError("Missing tone");
                };
                implementation = "sequential-public";
            } else if (mode == Mode.OPTICAL) {
                action = () -> lens.applyToOwned(image, true, workers, 1);
                implementation = "optical-public";
            } else if (mode == Mode.TONE) {
                action = () -> {
                    if (!CameraToneCurve.apply(image, camera, style, workers)) throw new AssertionError("Missing tone");
                };
                implementation = "tone-public";
            } else {
                Method kernel;
                Object[] arguments;
                try {
                    kernel = CameraToneCurve.class.getDeclaredMethod("transform16", short[].class,
                            short[].class, short[].class, short[].class, int.class, int.class);
                    arguments = new Object[]{destination[0], destination[1],
                            splitBlue == null ? destination[2] : splitBlue, destination[2], 0, sites};
                    implementation = splitBlue == null ? "four-array-kernel-alias" : "four-array-kernel-split";
                } catch (NoSuchMethodException absent) {
                    if (mode == Mode.KERNEL_SPLIT) {
                        throw new IllegalArgumentException("kernel-split requires the candidate; compare with baseline kernel-alias");
                    }
                    kernel = CameraToneCurve.class.getDeclaredMethod("transform16", short[].class,
                            short[].class, short[].class, int.class, int.class);
                    arguments = new Object[]{destination[0], destination[1], destination[2], 0, sites};
                    implementation = "three-array-kernel";
                }
                kernel.setAccessible(true);
                Method selected = kernel;
                Object[] selectedArguments = arguments;
                action = () -> invoke(selected, null, selectedArguments);
            }

            System.out.printf(Locale.ROOT,
                    "{\"kind\":\"setup\",\"mode\":%s,\"implementation\":%s,\"input\":%s,\"input_sha256\":%s,"
                    + "\"artifact\":%s,\"artifact_sha256\":%s,\"java\":%s,\"jvm_arguments\":%s,"
                    + "\"available_processors\":%d,\"workers_requested\":%d,\"kernel_workers\":%d,"
                    + "\"width\":%d,\"height\":%d,\"neutral_sha256\":%s,\"stage_input_sha256\":%s,"
                    + "\"expected_sha256\":%s,\"preinitialized\":true,\"warmup\":%d,\"iterations\":%d}%n",
                    json(args[0]), json(implementation), json(input.toString()), json(inputHash),
                    json(artifact.toString()), json(artifactHash), json(System.getProperty("java.runtime.version")),
                    json(ManagementFactory.getRuntimeMXBean().getInputArguments().toString()),
                    Runtime.getRuntime().availableProcessors(), workers,
                    mode == Mode.KERNEL_ALIAS || mode == Mode.KERNEL_SPLIT ? 1 : -1,
                    template.getWidth(), template.getHeight(), json(neutralHash), json(stageInputHash),
                    json(expectedHash), warmup, iterations);
            for (int iteration = -warmup; iteration < iterations; iteration++) {
                // No decoder, copying, image allocation, reflection lookup or hash inside the timer.
                reset(original, destination, splitBlue);
                Counters before = counters();
                long start = System.nanoTime();
                action.run();
                long elapsed = System.nanoTime() - start;
                Counters after = counters();
                String actualHash = hashPixels(image);
                if (!expectedHash.equals(actualHash)) {
                    throw new AssertionError("Output mismatch at " + iteration + ": " + actualHash
                            + ", expected " + expectedHash);
                }
                System.out.printf(Locale.ROOT,
                        "{\"kind\":\"iteration\",\"mode\":%s,\"iteration\":%d,\"phase\":%s,"
                        + "\"elapsed_ms\":%.6f,\"process_cpu_ms\":%.6f,\"caller_cpu_ms\":%.6f,"
                        + "\"gc_count\":%d,\"gc_ms\":%d,\"compilation_ms\":%d,\"sha256\":%s}%n",
                        json(args[0]), iteration, json(iteration < 0 ? "warmup" : "measured"), elapsed / 1e6,
                        delta(before.processCpu(), after.processCpu()) / 1e6,
                        delta(before.callerCpu(), after.callerCpu()) / 1e6,
                        delta(before.gcCount(), after.gcCount()), delta(before.gcMs(), after.gcMs()),
                        delta(before.compilationMs(), after.compilationMs()), json(actualHash));
            }
            if (!stageInputHash.equals(hashPixels(template))) throw new AssertionError("Template was mutated");
        }
    }

    private static Method findCombined() {
        try {
            return OpticalCorrection.class.getMethod("applyToOwnedAndTone", BufferedImage.class,
                    boolean.class, int.class, double.class, String.class, int.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static void invoke(Method method, Object receiver, Object[] arguments) throws Exception {
        try {
            method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Error error) throw error;
            if (wrapper.getCause() instanceof Exception exception) throw exception;
            throw new AssertionError(wrapper.getCause());
        }
    }

    private static short[][] denseBands(BufferedImage image) {
        var raster = image.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferUShort data)
                || !(raster.getSampleModel() instanceof BandedSampleModel layout)
                || data.getNumBanks() != 3 || layout.getNumBands() != 3
                || raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0
                || layout.getScanlineStride() != image.getWidth()) {
            throw new IllegalArgumentException("Expected a complete dense RGB16 template");
        }
        int size = Math.multiplyExact(image.getWidth(), image.getHeight());
        int[] indices = layout.getBankIndices(), offsets = layout.getBandOffsets(), dataOffsets = data.getOffsets();
        short[][] physical = data.getBankData(), logical = new short[3][];
        for (int band = 0; band < 3; band++) {
            int index = indices[band];
            if (index < 0 || index > 2 || offsets[band] != 0 || dataOffsets[index] != 0
                    || physical[index].length != size) throw new IllegalArgumentException("Unexpected band layout");
            logical[band] = physical[index];
            for (int previous = 0; previous < band; previous++) {
                if (logical[previous] == logical[band]) throw new IllegalArgumentException("Aliased source banks");
            }
        }
        return logical;
    }

    private static void reset(short[][] source, short[][] destination, short[] splitBlue) {
        for (int band = 0; band < 3; band++) {
            System.arraycopy(source[band], 0, destination[band], 0, source[band].length);
        }
        if (splitBlue != null) System.arraycopy(source[2], 0, splitBlue, 0, source[2].length);
    }

    private record Counters(long processCpu, long callerCpu, long gcCount, long gcMs, long compilationMs) {}

    private static Counters counters() {
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        long process = operatingSystem instanceof com.sun.management.OperatingSystemMXBean bean
                ? bean.getProcessCpuTime() : -1;
        var threads = ManagementFactory.getThreadMXBean();
        long caller = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                ? threads.getCurrentThreadCpuTime() : -1;
        long count = 0, milliseconds = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collected = collector.getCollectionCount(), time = collector.getCollectionTime();
            if (collected >= 0) count += collected;
            if (time >= 0) milliseconds += time;
        }
        var compilation = ManagementFactory.getCompilationMXBean();
        long compiled = compilation != null && compilation.isCompilationTimeMonitoringSupported()
                ? compilation.getTotalCompilationTime() : -1;
        return new Counters(process, caller, count, milliseconds, compiled);
    }

    private static long delta(long before, long after) {
        return before < 0 || after < 0 ? -1 : after - before;
    }

    private static String hashFile(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = stream.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashPixels(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] samples = new int[image.getWidth() * 3];
        byte[] row = new byte[samples.length * 2];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRaster().getPixels(0, y, image.getWidth(), 1, samples);
            for (int i = 0; i < samples.length; i++) {
                row[2 * i] = (byte) samples[i];
                row[2 * i + 1] = (byte) (samples[i] >>> 8);
            }
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '"') quoted.append('\\').append(character);
            else if (character < 0x20) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else quoted.append(character);
        }
        return quoted.append('"').toString();
    }
}
