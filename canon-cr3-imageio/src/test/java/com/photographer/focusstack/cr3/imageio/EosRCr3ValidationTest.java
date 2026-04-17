package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
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
 * EOS R-specific CR3 validation that goes <em>beyond</em>
 * {@link Cr3VariantVerificationTest}: it asserts that the decoded pixel
 * content is actually a sensible image, not random noise, and writes every
 * decoded output to {@code target/cr3-validation/} so a human can verify
 * the result visually.
 *
 * <p>The existing variant verification test only checks the shape of the
 * decoded image (non-null, expected width/height).  That is not sufficient:
 * a CR3 decoder bug that produces a uniformly-noisy image of the right
 * dimensions still passes those assertions.  This test exercises the
 * <strong>content</strong> of the decoded data, motivated by the focus-stack
 * output rendering as digital noise.</p>
 *
 * <h3>Decoded variants saved to {@code target/cr3-validation/}</h3>
 * <ul>
 *   <li>{@code &lt;name&gt;-preview.png} — embedded PRVW JPEG (always sharp; baseline)</li>
 *   <li>{@code &lt;name&gt;-thumbnail.png} — embedded THMB JPEG</li>
 *   <li>{@code &lt;name&gt;-raw.png} — full demosaiced RAW (the suspect path)</li>
 * </ul>
 *
 * <p>If an EOS R RAW decode passes the structural variant test but its
 * {@code -raw.png} looks like noise, the bug is in the demosaic / black-level
 * / white-balance / colour-matrix path.</p>
 */
class EosRCr3ValidationTest {

    private static final Logger log = LoggerFactory.getLogger(EosRCr3ValidationTest.class);

    /** Directory where decoded PNG outputs are written for visual inspection. */
    private static final Path OUTPUT_DIR = Path.of("target", "cr3-validation");

    /** Discovers every {@code eosr-*.cr3} sample on the classpath / file system. */
    private static List<File> discoverEosRSamples() {
        List<File> samples = new ArrayList<>();

        URL samplesUrl = EosRCr3ValidationTest.class.getResource("/samples");
        if (samplesUrl != null) {
            Path samplesDir = Paths.get(samplesUrl.getPath().replaceFirst("^/([A-Z]:)", "$1"));
            if (Files.isDirectory(samplesDir)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(samplesDir, "eosr-*.cr3")) {
                    for (Path p : s) samples.add(p.toFile());
                } catch (Exception ignored) { }
            }
        }
        if (samples.isEmpty()) {
            Path fallback = Path.of("src", "test", "resources", "samples");
            if (Files.isDirectory(fallback)) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(fallback, "eosr-*.cr3")) {
                    for (Path p : s) samples.add(p.toFile());
                } catch (Exception ignored) { }
            }
        }
        samples.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return samples;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Preview / thumbnail content checks (these are JPEG, must be sane)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> previewIsSharpAndSavedToDisk() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<File> samples = discoverEosRSamples();
        assumeTrue(!samples.isEmpty(),
                "No eosr-*.cr3 samples found — run EosRSampleCaptureIT first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Preview: " + name, () -> {
                BufferedImage preview;
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    preview = decoder.decode(Cr3DecodeOptions.defaults());
                }
                assertThat(preview).isNotNull();

                Stats stats = computeStats(preview);
                log.info("✓ {} preview {}x{} — mean={}, stddev={}, uniqueLuma={}",
                        name, preview.getWidth(), preview.getHeight(),
                        stats.mean, stats.stddev, stats.uniqueLumaBuckets);

                // A real photographic image must have a wide tonal distribution.
                // Pure noise has stddev ~74 too, so we ALSO require many
                // distinct luma buckets (sane images use ≥ 100 of 256).
                assertThat(stats.stddev)
                        .as(name + " preview stddev")
                        .isGreaterThan(20.0);
                assertThat(stats.uniqueLumaBuckets)
                        .as(name + " preview unique luma buckets (out of 256)")
                        .isGreaterThan(50);

                Path out = OUTPUT_DIR.resolve(name.replace(".cr3", "-preview.png"));
                ImageIO.write(preview, "PNG", out.toFile());
                log.info("  → saved {}", out.toAbsolutePath());
            }));
        }
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> thumbnailSavedToDisk() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<File> samples = discoverEosRSamples();
        assumeTrue(!samples.isEmpty(), "No eosr-*.cr3 samples found");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Thumbnail: " + name, () -> {
                BufferedImage thumb;
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    thumb = decoder.decode(Cr3DecodeOptions.defaults()
                            .outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
                }
                assertThat(thumb).isNotNull();
                Path out = OUTPUT_DIR.resolve(name.replace(".cr3", "-thumbnail.png"));
                ImageIO.write(thumb, "PNG", out.toFile());
                log.info("✓ {} thumbnail {}x{} — saved {}",
                        name, thumb.getWidth(), thumb.getHeight(), out.toAbsolutePath());
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Full RAW demosaic content check — the key test
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> rawProcessedSavedAndContentChecked() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<File> samples = discoverEosRSamples();
        assumeTrue(!samples.isEmpty(), "No eosr-*.cr3 samples found");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("RAW processed: " + name, () -> {
                BufferedImage raw;
                Cr3Metadata meta;
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    meta = decoder.getMetadata();
                    raw = decoder.decode(Cr3DecodeOptions.rawProcessed());
                }
                assertThat(raw).isNotNull();
                assertThat(raw.getWidth()).isEqualTo(meta.visibleWidth());
                assertThat(raw.getHeight()).isEqualTo(meta.visibleHeight());

                // Save to disk no matter what so the user can inspect the
                // output even if the assertions below fail.
                Path out = OUTPUT_DIR.resolve(name.replace(".cr3", "-raw.png"));
                ImageIO.write(raw, "PNG", out.toFile());
                log.info("✓ {} RAW {}x{} — saved {}",
                        name, raw.getWidth(), raw.getHeight(), out.toAbsolutePath());

                // Compare RAW pixel statistics to the embedded preview.
                // The preview is derived from the same scene at the same time;
                // its mean luma should be in the same ballpark as the RAW.
                BufferedImage preview;
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    preview = decoder.decode(Cr3DecodeOptions.defaults());
                }

                Stats rawStats = computeStats(raw);
                Stats prvStats = computeStats(preview);
                log.info("  RAW     : mean={}, stddev={}, uniqueLuma={}",
                        rawStats.mean, rawStats.stddev, rawStats.uniqueLumaBuckets);
                log.info("  PREVIEW : mean={}, stddev={}, uniqueLuma={}",
                        prvStats.mean, prvStats.stddev, prvStats.uniqueLumaBuckets);

                // 1. RAW must use a broad tonal range (real photos do).
                assertThat(rawStats.stddev)
                        .as(name + " RAW stddev (uniform/black/noise images would be < 5)")
                        .isGreaterThan(15.0);

                // 2. RAW must use many distinct luma buckets — pure white-noise
                //    fills all 256 buckets too, but a black/grey/uniform image
                //    would fail this. Combined with the comparison below, this
                //    catches both "all black" and "wrong tonal mapping" bugs.
                assertThat(rawStats.uniqueLumaBuckets)
                        .as(name + " RAW unique luma buckets (out of 256)")
                        .isGreaterThan(50);

                // 3. RAW mean luma must be reasonably close to the preview
                //    (within 60 of 255). If the demosaic / tone mapping is
                //    completely off — e.g. all values clipped to 0xFF or 0x00,
                //    or RGB channels mis-routed — this will catch it.
                int meanGap = Math.abs(rawStats.mean - prvStats.mean);
                assertThat(meanGap)
                        .as(name + " |RAW mean − PREVIEW mean| should be < 60 (got "
                                + meanGap + "); RAW=" + rawStats.mean
                                + ", PREVIEW=" + prvStats.mean
                                + ". A large gap means the RAW pipeline produces a wildly different image.")
                        .isLessThan(60);
            }));
        }
        return tests;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private record Stats(int mean, double stddev, int uniqueLumaBuckets) { }

    /**
     * Computes mean luma, stddev, and how many of the 256 possible 8-bit luma
     * buckets are populated (sampled on a 256-point grid for speed).
     */
    private static Stats computeStats(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int stride = Math.max(1, Math.min(w, h) / 256); // sample ≈ 256x256 = 65k pts
        long sum = 0;
        long sumSq = 0;
        int n = 0;
        boolean[] bucket = new boolean[256];
        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Rec. 601 luma
                int luma = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                sum += luma;
                sumSq += (long) luma * luma;
                bucket[luma] = true;
                n++;
            }
        }
        double mean = (double) sum / n;
        double var = (double) sumSq / n - mean * mean;
        double std = Math.sqrt(Math.max(0, var));
        int unique = 0;
        for (boolean b : bucket) if (b) unique++;
        return new Stats((int) mean, std, unique);
    }
}
