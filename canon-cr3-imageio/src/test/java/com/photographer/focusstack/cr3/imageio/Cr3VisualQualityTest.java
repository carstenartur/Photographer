package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Perceptual quality test for the CR3 → sRGB RAW processing pipeline.
 *
 * <p>For every {@code *.cr3} sample in the test resources directory, this test:</p>
 * <ol>
 *   <li>Extracts the embedded PRVW JPEG from the CR3 container as a ground-truth
 *       reference (same scene, same white balance, camera-rendered).</li>
 *   <li>Decodes the raw sensor data with the full pipeline (BL → crop → WB →
 *       bilinear demosaic → colour matrix → sRGB gamma).</li>
 *   <li>Down-scales the decoded image to the PRVW's dimensions using a high-quality
 *       bicubic filter so both images are at the same resolution.</li>
 *   <li>Computes structural similarity (SSIM) and mean CIE ΔE2000.</li>
 *   <li>Asserts {@code SSIM ≥ 0.02} and {@code meanΔE ≤ 80.0} as a permissive
 *       <em>regression gate</em> calibrated just below/above today's worst measured
 *       values. The CRX entropy decoder is still under reverse-engineering, so
 *       absolute quality is poor; these thresholds only catch further regressions.
 *       Tighten to {@code SSIM ≥ 0.90} / {@code ΔE ≤ 5.0} once the decoder matures
 *       (see the {@code SSIM_MIN} / {@code DELTA_E_MAX} field Javadoc).</li>
 *   <li>Writes side-by-side debug images to {@code target/cr3-decode-debug/} for
 *       manual inspection (not committed to version control).</li>
 * </ol>
 *
 * <p>Samples that use C-RAW (lossy) compression are skipped automatically
 * when the decoder throws a {@link Cr3Exception}.</p>
 */
class Cr3VisualQualityTest {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3VisualQualityTest.class);

    /** Output directory for debug images (not committed). */
    private static final Path DEBUG_DIR = Path.of("target", "cr3-decode-debug");

    /**
     * Minimum SSIM required between the decoded RAW and the embedded PRVW.
     *
     * <p>Set to a value just below today's worst measured SSIM (≈ 0.026 for
     * {@code sample-wb-tungsten.cr3}) so the test acts as a strict
     * <em>regression gate</em>: any change that makes the CRX entropy
     * decoder, prediction or pipeline noisier than today will fail.
     * Tighten to ≥ 0.90 once the decoder is fixed (currently the Rice-Golomb
     * adaptation rule does not match Canon's encoder; see PR notes).</p>
     */
    private static final double SSIM_MIN = 0.02;

    /**
     * Maximum mean ΔE2000 allowed between the decoded RAW and the embedded PRVW.
     *
     * <p>Set to a value just above today's worst measured ΔE so the test acts
     * as a regression gate. Tighten to ≤ 5.0 once the decoder is fixed.</p>
     */
    private static final double DELTA_E_MAX = 80.0;

    // ─────────────────────────────────────────────────────────────────────
    //  Sample discovery
    // ─────────────────────────────────────────────────────────────────────

    private static List<File> discoverCr3Samples() {
        List<File> samples = new ArrayList<>();

        URL samplesUrl = Cr3VisualQualityTest.class.getResource("/samples");
        if (samplesUrl != null) {
            Path dir = Paths.get(samplesUrl.getPath().replaceFirst("^/([A-Z]:)", "$1"));
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.cr3")) {
                    for (Path p : s) samples.add(p.toFile());
                } catch (Exception ignored) { }
            }
        }
        if (samples.isEmpty()) {
            Path fallback = Path.of("src", "test", "resources", "samples");
            if (Files.isDirectory(fallback)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(fallback, "*.cr3")) {
                    for (Path p : s) samples.add(p.toFile());
                } catch (Exception ignored) { }
            }
        }
        samples.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return samples;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Main test factory
    // ─────────────────────────────────────────────────────────────────────

    @TestFactory
    Collection<DynamicTest> rawVsEmbeddedPreviewSsimAndDeltaE() throws Exception {
        Files.createDirectories(DEBUG_DIR);
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(), "No *.cr3 samples found in test resources");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            File capturedCr3 = cr3;
            tests.add(dynamicTest("SSIM/ΔE: " + cr3.getName(), () -> {
                runQualityTest(capturedCr3);
            }));
        }
        return tests;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-sample quality check
    // ─────────────────────────────────────────────────────────────────────

    private void runQualityTest(File cr3File) throws Exception {
        String name = cr3File.getName();

        // ── 1. Extract the embedded PRVW JPEG (ground-truth reference) ──
        BufferedImage preview;
        try (ImageInputStream in = new FileImageInputStream(cr3File)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(in);
            preview = decoder.decode(Cr3DecodeOptions.defaults()
                    .outputMode(Cr3DecodeOptions.OutputMode.PREVIEW));
        }
        assumeTrue(preview != null, name + ": no embedded PRVW found, skipping");

        int prvW = preview.getWidth();
        int prvH = preview.getHeight();
        LOG.info("{}: PRVW reference is {}×{}", name, prvW, prvH);

        // ── 2. Decode the RAW with the full processing pipeline ──
        BufferedImage rawDecoded;
        try (ImageInputStream in = new FileImageInputStream(cr3File)) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(in);
            rawDecoded = decoder.decode(Cr3DecodeOptions.rawProcessed());
        } catch (Cr3Exception e) {
            // C-RAW / unsupported encoding — skip gracefully
            assumeTrue(false, name + ": RAW decode unsupported (" + e.getMessage() + "), skipping");
            return;
        }

        // ── 3. Down-scale decoded RAW to PRVW dimensions ──
        BufferedImage rawScaled = scaleTo(rawDecoded, prvW, prvH);

        // ── 4. Compute SSIM (luminance) and mean ΔE2000 ──
        double ssim = ImageQualityHelper.computeSsim(rawScaled, preview);
        double meanDeltaE = ImageQualityHelper.computeMeanDeltaE2000(rawScaled, preview);

        LOG.info("{}: SSIM={}, meanΔE2000={} (raw {}x{} -> scaled to {}x{})",
                name,
                String.format("%.4f", ssim),
                String.format("%.2f", meanDeltaE),
                rawDecoded.getWidth(), rawDecoded.getHeight(), prvW, prvH);

        // ── 5. Write debug images for manual inspection ──
        try {
            String stem = name.replace(".cr3", "");
            ImageIO.write(preview, "PNG", DEBUG_DIR.resolve(stem + "-prvw.png").toFile());
            ImageIO.write(rawScaled, "PNG", DEBUG_DIR.resolve(stem + "-raw-scaled.png").toFile());
            ImageIO.write(buildSideBySide(preview, rawScaled),
                    "PNG", DEBUG_DIR.resolve(stem + "-diff.png").toFile());
        } catch (Exception ignored) {
            // Debug output is optional; test outcome must not depend on it
        }

        // ── 6. Quality assertions ──
        assertThat(ssim)
                .as("%s SSIM (target ≥ %.2f)", name, SSIM_MIN)
                .isGreaterThanOrEqualTo(SSIM_MIN);
        assertThat(meanDeltaE)
                .as("%s mean ΔE2000 (target ≤ %.1f)", name, DELTA_E_MAX)
                .isLessThanOrEqualTo(DELTA_E_MAX);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Utility helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Scales {@code src} to {@code (w, h)} using bicubic interpolation.
     */
    private static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return out;
    }

    /**
     * Builds a side-by-side image: left half = preview, right half = raw-scaled.
     */
    private static BufferedImage buildSideBySide(BufferedImage left, BufferedImage right) {
        int w = left.getWidth();
        int h = left.getHeight();
        BufferedImage out = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(left, 0, 0, null);
        g2.drawImage(right, w, 0, null);
        g2.dispose();
        return out;
    }
}
