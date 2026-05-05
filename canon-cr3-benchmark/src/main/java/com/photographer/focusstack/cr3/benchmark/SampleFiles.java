package com.photographer.focusstack.cr3.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared helper that resolves the directory containing the sample CR3 fixtures.
 *
 * <p>Lookup order:</p>
 * <ol>
 *   <li>System property {@code samples.dir} (used by CI / explicit overrides).</li>
 *   <li>Environment variable {@code PHOTOGRAPHER_SAMPLES_DIR}.</li>
 *   <li>Repository default: {@code canon-cr3-imageio/src/test/resources/samples} resolved
 *       relative to the current working directory or its parent (so it works whether
 *       JMH is launched from the repo root or from {@code canon-cr3-benchmark/}).</li>
 * </ol>
 *
 * <p>The eight names below are the {@code @Param} axis used by every benchmark in
 * this module — keep them in sync with the fixtures actually committed to
 * {@code canon-cr3-imageio/src/test/resources/samples/}.</p>
 */
public final class SampleFiles {

    /**
     * Default {@code @Param} list of all sample CR3 file names committed to
     * {@code canon-cr3-imageio/src/test/resources/samples/}.
     */
    public static final String[] ALL_SAMPLES = {
            "sample.cr3",
            "sample-iso-high.cr3",
            "sample-wb-tungsten.cr3",
            "sample-wide.cr3",
            "sample-tele.cr3",
            "eosr-iso-low.cr3",
            "eosr-iso-mid.cr3",
            "eosr-iso-high.cr3"
    };

    private SampleFiles() {
        // utility class
    }

    /**
     * Resolves the absolute path of the named sample CR3 fixture.
     *
     * @param sampleName the bare file name (e.g. {@code "sample.cr3"})
     * @return the absolute path to the fixture
     * @throws IllegalStateException if the samples directory cannot be located
     *                               or the named file does not exist
     */
    public static Path resolve(String sampleName) {
        Path dir = samplesDir();
        Path candidate = dir.resolve(sampleName);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalStateException("Sample CR3 not found: " + candidate.toAbsolutePath()
                    + " (set -Dsamples.dir=... to override)");
        }
        return candidate;
    }

    /**
     * Resolves the directory holding the sample CR3 fixtures.
     */
    public static Path samplesDir() {
        String fromProperty = System.getProperty("samples.dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Paths.get(fromProperty).toAbsolutePath().normalize();
        }
        String fromEnv = System.getenv("PHOTOGRAPHER_SAMPLES_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv).toAbsolutePath().normalize();
        }
        // Try running from repo root, then from inside the benchmark module.
        Path[] candidates = {
                Paths.get("canon-cr3-imageio", "src", "test", "resources", "samples"),
                Paths.get("..", "canon-cr3-imageio", "src", "test", "resources", "samples")
        };
        for (Path candidate : candidates) {
            Path abs = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(abs)) {
                return abs;
            }
        }
        throw new IllegalStateException("Could not locate sample CR3 directory; "
                + "set -Dsamples.dir=/path/to/samples");
    }

    /**
     * Reads the entire sample file into a byte array. Convenience helper for
     * benchmarks that want to amortise I/O out of the measured section.
     */
    public static byte[] readAllBytes(String sampleName) throws IOException {
        return Files.readAllBytes(resolve(sampleName));
    }
}
