package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that validates reference sidecars can be reproduced by
 * {@code tools/reference-export/export_references.py}.
 *
 * <p>The test is automatically <em>skipped</em> when Python 3 is not available
 * or when {@code rawpy} cannot be imported, so it does not break CI
 * environments that only have Java.  Run it locally after installing the
 * Python prerequisites:
 *
 * <pre>
 *   pip install -r tools/reference-export/requirements.txt
 *   mvn verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
 *       -Dit.test=ReferenceRebuildValidationIT
 * </pre>
 *
 * <p>When the regenerated sidecar is <em>bit-identical</em> to the committed
 * file the test passes normally.  When hashes differ (e.g. because a newer
 * LibRaw version produces slightly different raw values) the test logs
 * MAE / RMSE / histogram-delta metrics and still passes, because pixel-exact
 * bit identity is not guaranteed across library versions.  The hard-fail gate
 * is {@link ReferenceManifestValidationIT}.
 */
class ReferenceRebuildValidationIT {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path EXPORT_SCRIPT =
            REPO_ROOT.resolve("tools/reference-export/export_references.py");

    /** Smallest available fixture – keeps the probe fast. */
    private static final String PROBE_FIXTURE = "sample.cr3";

    // ---------------------------------------------------------------------------
    // Pre-flight checks
    // ---------------------------------------------------------------------------

    private static boolean isPython3Available() {
        return runSilently("python3", "--version") == 0;
    }

    private static boolean isRawpyAvailable() {
        return runSilently("python3", "-c", "import rawpy") == 0;
    }

    private static int runSilently(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            // drain stdout/stderr so the process does not block
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor(10, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // ---------------------------------------------------------------------------
    // Test
    // ---------------------------------------------------------------------------

    @Test
    void rebuildReferenceAndCompare() throws Exception {
        assumeTrue(isPython3Available(),
                "python3 not available – skipping rebuild validation");
        assumeTrue(isRawpyAvailable(),
                "rawpy not installed – skipping rebuild validation");
        assumeTrue(Files.isRegularFile(EXPORT_SCRIPT),
                "export script not found at " + EXPORT_SCRIPT + " – skipping");
        assumeTrue(Files.isRegularFile(SAMPLES_DIR.resolve(PROBE_FIXTURE)),
                "probe fixture not found: " + PROBE_FIXTURE + " – skipping");

        Path tempDir = Files.createTempDirectory("cr3-rebuild-");
        try {
            // Copy the probe fixture into an isolated temp samples dir so that
            // the script does not overwrite the committed sidecars.
            Path tempSamples = tempDir.resolve("samples");
            Files.createDirectories(tempSamples);
            Files.copy(SAMPLES_DIR.resolve(PROBE_FIXTURE), tempSamples.resolve(PROBE_FIXTURE));

            rebuildAndCompare(PROBE_FIXTURE, "tiff", tempSamples,
                    toSidecarPath(SAMPLES_DIR.resolve(PROBE_FIXTURE), "reference.tiff"));
            rebuildAndCompare(PROBE_FIXTURE, "png", tempSamples,
                    toSidecarPath(SAMPLES_DIR.resolve(PROBE_FIXTURE), "reference.png"));
        } finally {
            deleteRecursive(tempDir);
        }
    }

    // ---------------------------------------------------------------------------
    // Export + compare
    // ---------------------------------------------------------------------------

    private void rebuildAndCompare(String fixture, String type,
                                   Path tempSamples, Path existing) throws Exception {
        // Run the export script against the isolated temp samples directory.
        List<String> cmd = List.of(
                "python3",
                EXPORT_SCRIPT.toAbsolutePath().toString(),
                "--fixture", fixture,
                "--type", type,
                "--samples-dir", tempSamples.toAbsolutePath().toString()
        );
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);

        assertThat(finished)
                .as("export script timed out for %s --type %s", fixture, type)
                .isTrue();
        assertThat(p.exitValue())
                .as("export script failed for %s --type %s:\n%s", fixture, type, output)
                .isEqualTo(0);

        String stem = fixture.replaceFirst("(?i)\\.cr3$", "");
        Path rebuilt = tempSamples.resolve(stem + ".reference." + type);
        assertThat(Files.isRegularFile(rebuilt))
                .as("expected generated sidecar at %s", rebuilt)
                .isTrue();

        String label = type.equals("tiff") ? "RAW-linear TIFF" : "preview PNG";
        if (Files.isRegularFile(existing)) {
            compareOrReport(existing, rebuilt, label);
        } else {
            System.out.printf("[RebuildIT] %s  no committed sidecar to compare against; "
                    + "rebuilt file at %s%n", label, rebuilt);
        }
    }

    private static void compareOrReport(Path existing, Path rebuilt, String label)
            throws Exception {
        String hashExisting = sha256Hex(existing);
        String hashRebuilt = sha256Hex(rebuilt);

        if (hashExisting.equalsIgnoreCase(hashRebuilt)) {
            System.out.printf("[RebuildIT] %-22s ✓ bit-exact  sha256=%s%n",
                    label, hashExisting);
            return;
        }

        System.out.printf("[RebuildIT] %-22s hash mismatch (library version drift?)%n"
                + "  committed sha256=%s%n"
                + "  rebuilt   sha256=%s%n",
                label, hashExisting, hashRebuilt);

        BufferedImage imgA = ImageIO.read(existing.toFile());
        BufferedImage imgB = ImageIO.read(rebuilt.toFile());
        if (imgA == null || imgB == null) {
            System.out.printf("[RebuildIT] %-22s could not decode images for metric comparison%n",
                    label);
            return;
        }
        if (imgA.getWidth() != imgB.getWidth() || imgA.getHeight() != imgB.getHeight()) {
            System.out.printf("[RebuildIT] %-22s geometry differs: committed %dx%d vs rebuilt %dx%d%n",
                    label, imgA.getWidth(), imgA.getHeight(), imgB.getWidth(), imgB.getHeight());
            return;
        }

        SimilarityMetrics m = computeMetrics(imgA, imgB);
        System.out.printf("[RebuildIT] %-22s MAE=%.4f  RMSE=%.4f  histogramDelta=%.6f%n",
                label, m.mae(), m.rmse(), m.histogramDelta());
        // Not a hard failure: the manifest IT gate handles hash enforcement.
    }

    // ---------------------------------------------------------------------------
    // Pixel-level similarity metrics
    // ---------------------------------------------------------------------------

    private static SimilarityMetrics computeMetrics(BufferedImage a, BufferedImage b) {
        int width = a.getWidth();
        int height = a.getHeight();
        int commonBands = Math.min(a.getRaster().getNumBands(), b.getRaster().getNumBands());
        int aBitDepth = a.getRaster().getSampleModel().getSampleSize(0);
        int bBitDepth = b.getRaster().getSampleModel().getSampleSize(0);
        long pixelCount = (long) width * height * commonBands;
        double sumAbs = 0;
        double sumSq = 0;
        int[] histA = new int[256];
        int[] histB = new int[256];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int band = 0; band < commonBands; band++) {
                    int va = a.getRaster().getSample(x, y, band);
                    int vb = b.getRaster().getSample(x, y, band);
                    double diff = va - vb;
                    sumAbs += Math.abs(diff);
                    sumSq += diff * diff;
                    histA[normalise(va, aBitDepth)]++;
                    histB[normalise(vb, bBitDepth)]++;
                }
            }
        }

        double mae = sumAbs / pixelCount;
        double rmse = Math.sqrt(sumSq / pixelCount);

        // Histogram delta: mean absolute difference of normalised CDFs
        double totalA = 0;
        double totalB = 0;
        for (int i = 0; i < 256; i++) {
            totalA += histA[i];
            totalB += histB[i];
        }
        double cdfA = 0;
        double cdfB = 0;
        double histDelta = 0;
        for (int i = 0; i < 256; i++) {
            cdfA += histA[i] / totalA;
            cdfB += histB[i] / totalB;
            histDelta += Math.abs(cdfA - cdfB);
        }
        histDelta /= 256.0;

        return new SimilarityMetrics(mae, rmse, histDelta);
    }

    private static int normalise(int value, int bitDepth) {
        if (bitDepth <= 0 || bitDepth == 8) {
            return Math.max(0, Math.min(255, value));
        }
        int maxVal = (1 << bitDepth) - 1;
        return (int) Math.round(value * 255.0 / maxVal);
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    private static Path toSidecarPath(Path fixture, String sidecar) {
        String name = fixture.getFileName().toString();
        return fixture.resolveSibling(name.replaceFirst("(?i)\\.cr3$", "." + sidecar));
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed computing sha256 for " + path, e);
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<IOException> suppressed = new java.util.ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            suppressed.add(e);
                        }
                    });
        }
        if (!suppressed.isEmpty()) {
            IOException first = suppressed.get(0);
            suppressed.subList(1, suppressed.size()).forEach(first::addSuppressed);
            throw first;
        }
    }

    private record SimilarityMetrics(double mae, double rmse, double histogramDelta) {}
}
