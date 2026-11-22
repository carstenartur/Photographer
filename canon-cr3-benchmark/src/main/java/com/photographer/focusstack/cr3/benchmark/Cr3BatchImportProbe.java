package com.photographer.focusstack.cr3.benchmark;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Compares complete CR3 imports with a fixed number of concurrently processed files. */
public final class Cr3BatchImportProbe {
    private static final String USAGE = "Usage: Cr3BatchImportProbe PARALLEL_FILES WORKERS_PER_FILE MODE "
            + "WARMUP_BATCHES MEASURED_BATCHES [--checksum] FILE [FILE ...]";

    private Cr3BatchImportProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException(USAGE);
        }
        int parallelFiles = number(args[0], 1);
        int workers = number(args[1], 0);
        String mode = args[2];
        if (!Set.of("rgb8", "rgb16", "sensor").contains(mode)) {
            throw new IllegalArgumentException("Mode must be rgb8, rgb16, or sensor");
        }
        int warmup = number(args[3], 0);
        int measured = number(args[4], 1);
        boolean checksum = args[5].equals("--checksum");
        int firstFile = checksum ? 6 : 5;
        List<Path> files = Arrays.stream(args, firstFile, args.length).map(Path::of).toList();
        if (files.size() < parallelFiles) {
            throw new IllegalArgumentException("Supply at least one input file per concurrent import");
        }

        ImageIO.scanForPlugins();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try (var executor = Executors.newFixedThreadPool(parallelFiles)) {
            for (int iteration = -warmup; iteration < measured; iteration++) {
                System.gc();
                var before = Cr3ImportProbe.allocated();
                long cpuStart = os.getProcessCpuTime();
                long start = System.nanoTime();
                List<Imported> results = readBatch(executor, files, mode, workers, checksum);
                long elapsed = System.nanoTime() - start;
                long cpu = os.getProcessCpuTime() - cpuStart;
                long allocated = Cr3ImportProbe.allocated().entrySet().stream()
                        .mapToLong(entry -> Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0L)))
                        .sum();
                long peakRss = Cr3ImportProbe.statusKb("VmHWM");
                if (iteration >= 0) {
                    print(iteration, mode, parallelFiles, workers, checksum, elapsed, cpu, allocated, peakRss, results);
                }
            }
        }
    }

    private static int number(String value, int minimum) {
        int parsed = Integer.parseInt(value);
        if (parsed < minimum) {
            throw new IllegalArgumentException("Expected an integer >= " + minimum + ", received " + value);
        }
        return parsed;
    }

    private static List<Imported> readBatch(ExecutorService executor, List<Path> files, String mode, int workers,
                                            boolean checksum) throws Exception {
        List<Future<Imported>> imports = new ArrayList<>(files.size());
        for (Path file : files) {
            imports.add(executor.submit(() -> readFile(file, mode, workers, checksum)));
        }
        List<Imported> results = new ArrayList<>(files.size());
        for (Future<Imported> completed : imports) {
            results.add(completed.get());
        }
        return results;
    }

    private static Imported readFile(Path file, String mode, int workers, boolean checksum) throws Exception {
        long start = System.nanoTime();
        var image = Cr3ImportProbe.read(file, mode, workers);
        long elapsed = System.nanoTime() - start;
        try {
            Cr3ImportProbe.validateOutput(image, mode);
            return new Imported(file, elapsed, image.getWidth(), image.getHeight(), image.getRaster().getNumBands(),
                    image.getSampleModel().getSampleSize(0), checksum ? Cr3ImportProbe.hash(image, true) : 0);
        } finally {
            image.flush();
        }
    }

    private static void print(int iteration, String mode, int parallelFiles, int workers, boolean checksum,
                              long elapsed, long cpu, long allocated, long peakRss, List<Imported> results) {
        System.out.printf(Locale.ROOT,
                "{\"iteration\":%d,\"mode\":\"%s\",\"parallel_files\":%d,\"workers_per_file\":%d,"
                        + "\"available_processors\":%d,\"max_heap_bytes\":%d,\"checksum\":%s,"
                        + "\"total_ms\":%.6f,\"cpu_ms\":%.6f,\"allocated_bytes\":%d,\"maxrss_kb\":%d,\"files\":[",
                iteration, mode, parallelFiles, workers, Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(), checksum, elapsed / 1e6, cpu / 1e6, allocated, peakRss);
        for (int i = 0; i < results.size(); i++) {
            Imported file = results.get(i);
            System.out.printf(Locale.ROOT,
                    "%s{\"input\":\"%s\",\"read_ms\":%.6f,\"width\":%d,\"height\":%d,\"channels\":%d,\"bits\":%d",
                    i == 0 ? "" : ",", Cr3ImportProbe.escape(file.path().toString()), file.elapsed() / 1e6,
                    file.width(), file.height(), file.channels(), file.bits());
            if (checksum) {
                System.out.printf(Locale.ROOT, ",\"fnv1a64\":\"%016x\"", file.hash());
            }
            System.out.print('}');
        }
        System.out.println("]}");
    }

    private record Imported(Path path, long elapsed, int width, int height, int channels, int bits, long hash) {}
}
