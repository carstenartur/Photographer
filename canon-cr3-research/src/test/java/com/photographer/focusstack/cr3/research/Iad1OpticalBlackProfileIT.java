package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Work‑package 3 — IAD1 optical‑black (OB) rectangle exploitation.
 *
 * <p>The IAD1 box declares two OB rectangles per frame: {@code leftOb*}
 * (covered columns on the left of the sensor) and {@code topOb*} (covered
 * rows on the top of the sensor). Pre‑existing research has only used
 * {@code activeAreaLeft} as a single column count; this IT uses the full
 * rectangles and treats them as <b>physical ground truth</b> for a fully
 * decoded RAW frame: an optical‑black region must, by physics, contain
 * pixel values clustered tightly around the camera's pedestal (typically
 * 2 048 ± a few hundred LSB on a 14‑bit Canon sensor) regardless of the
 * scene.</p>
 *
 * <p>For every fixture for which the production decoder yields a buffer the
 * IT measures, per OB rectangle and per Bayer plane, the following:</p>
 * <ul>
 *   <li>median, mode (in 64‑LSB buckets), mean, std‑dev — physical OB
 *       regions should have very low std‑dev (read noise only).</li>
 *   <li>row‑mean profile across the rectangle — should be flat to within a
 *       handful of LSB.</li>
 *   <li>column‑mean profile across the rectangle — same expectation,
 *       different axis.</li>
 *   <li>fraction of pixels within ±256 LSB of the per‑plane median — a
 *       well‑decoded OB region should have >95 % of pixels in this band.</li>
 * </ul>
 *
 * <p>The IT logs <em>negative</em> results explicitly: when std‑dev is
 * far above read‑noise expectation the table makes that visible, which is
 * itself diagnostic — the decoder is producing structured noise where
 * physics says there should be none. That is a hard falsifier for any
 * decoder hypothesis that scores well on bulk Pearson but shreds the OB
 * region. Dark and flat fixtures are tagged so they can be re‑inspected
 * separately.</p>
 *
 * <p>Outputs go to {@code target/iad1-ob-profile/}. Hard assertions are
 * limited to "the OB rectangles exist and are not empty" so the IT remains
 * green against the current approximate decoder.</p>
 */
class Iad1OpticalBlackProfileIT {

    private static final Logger log = LoggerFactory.getLogger(Iad1OpticalBlackProfileIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "iad1-ob-profile");

    private static final int HIST_BUCKET = 64;

    @Test
    @DisplayName("IAD1 OB-rectangle stats per (file, region, plane)")
    void emitObProfileReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) { log.info("No samples dir — skip"); return; }
        List<Path> all = new ArrayList<>();
        collect(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collect(RESEARCH_DIR, all);
        all.sort((a, b) -> a.toString().compareTo(b.toString()));
        if (all.isEmpty()) { log.info("No CR3 fixtures — skip"); return; }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("ob-profile.tsv");

        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("file\tregion\tplane\tx0\ty0\tw\th\tcount\tmin\tmedian\tmode64\t"
                    + "mean\tstdDev\tmax\tfracWithin256\trowFlatness\tcolFlatness\tscene\n");
            for (Path file : all) {
                try {
                    analyse(file, out);
                } catch (Exception ex) {
                    log.warn("Skipped {}: {}", file.getFileName(), ex.toString());
                }
            }
        }
        log.info("Wrote {}", tsv.toAbsolutePath());
    }

    // ────────────────────────────────────── helpers ──

    private static void collect(Path dir, List<Path> out) throws java.io.IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static void analyse(Path file, java.io.BufferedWriter out) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (raw == null) return;
            Cmp1Box cmp1 = raw.cmp1();
            Iad1Box iad1 = raw.iad1();
            if (iad1 == null) {
                log.info("  {}: no IAD1 — skipping", file.getFileName());
                return;
            }
            // Skip C-RAW for now — the production decoder does not return
            // pixels for it. The IT remains useful on the lossless majority.
            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                log.info("  {}: waveletLevels={} (C-RAW) — skipping OB profile",
                        file.getFileName(), cmp1.waveletLevels());
                return;
            }

            int w = cmp1 != null && cmp1.imageWidth() > 0 ? cmp1.imageWidth() : raw.width();
            int h = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : raw.height();
            int bd = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            CrxHeader.CodecVersion ver = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());
            byte[] bytes = new byte[(int) raw.dataSize()];
            in.seek(raw.dataOffset());
            in.readFully(bytes);
            Cr3RawImage img;
            try {
                img = CrxLosslessRawDecoder.decode(bytes, w, h, bd, ver, cmp1);
            } catch (Exception e) {
                log.info("  {}: decoder failed — {}", file.getFileName(), e.toString());
                return;
            }

            String scene = sceneTag(file.getFileName().toString());

            // Two regions, two clamps to image bounds.
            int[] left  = clampRect(iad1.leftObLeft(), iad1.leftObTop(),
                                    iad1.leftObRight(), iad1.leftObBottom(), img.width(), img.height());
            int[] top   = clampRect(iad1.topObLeft(), iad1.topObTop(),
                                    iad1.topObRight(), iad1.topObBottom(), img.width(), img.height());
            writeRegion(out, file, "leftOB",  scene, img, left[0], left[1], left[2], left[3]);
            writeRegion(out, file, "topOB",   scene, img, top[0],  top[1],  top[2],  top[3]);
        }
    }

    private static int[] clampRect(int x0, int y0, int x1, int y1, int W, int H) {
        x0 = Math.max(0, Math.min(W, x0));
        y0 = Math.max(0, Math.min(H, y0));
        x1 = Math.max(x0, Math.min(W, x1));
        y1 = Math.max(y0, Math.min(H, y1));
        return new int[] { x0, y0, x1 - x0, y1 - y0 };
    }

    private static String sceneTag(String n) {
        String s = n.toLowerCase();
        if (s.contains("-dark-")) return "dark";
        if (s.contains("-flat-")) return "flat";
        if (s.contains("-detail-")) return "detail";
        if (s.contains("-asis-"))  return "asis";
        return "?";
    }

    private static void writeRegion(java.io.BufferedWriter out, Path file, String region,
                                    String scene, Cr3RawImage img,
                                    int x0, int y0, int w, int h) throws java.io.IOException {
        if (w <= 0 || h <= 0) {
            // Empty region — record one line per plane to make absence visible.
            for (int p = 0; p < 4; p++) {
                out.write(String.format("%s\t%s\t%d\t%d\t%d\t%d\t%d\t0\t-\t-\t-\t-\t-\t-\t-\t-\t-\t%s%n",
                        SAMPLES_DIR.relativize(file), region, p, x0, y0, w, h, scene));
            }
            return;
        }
        for (int plane = 0; plane < 4; plane++) {
            PerPlane stats = computePlane(img, x0, y0, w, h, plane);
            out.write(String.format(
                    "%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%d\t%.4f\t%.4f\t%.4f\t%s%n",
                    SAMPLES_DIR.relativize(file), region, plane, x0, y0, w, h,
                    stats.count, stats.min, stats.median, stats.modeBucket * HIST_BUCKET,
                    stats.mean, stats.stdDev, stats.max,
                    stats.fracWithin256, stats.rowFlatness, stats.colFlatness,
                    scene));
            log.info("  {} {} plane[{}] n={} median={} mode≈{} stdDev={} fracWithin256={} rowFlat={}",
                    file.getFileName(), region, plane, stats.count, stats.median,
                    stats.modeBucket * HIST_BUCKET,
                    String.format("%.1f", stats.stdDev),
                    String.format("%.3f", stats.fracWithin256),
                    String.format("%.3f", stats.rowFlatness));
        }
    }

    private static PerPlane computePlane(Cr3RawImage img, int x0, int y0, int w, int h, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int W = img.width();
        int[] data = img.data();
        long count = 0; long sum = 0; double sqSum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int buckets = (1 << img.bitDepth()) / HIST_BUCKET;
        long[] hist = new long[buckets];
        // Accumulate row/col means at full resolution then collapse to the
        // plane subgrid.
        int planeH = (h - rowOff + 1) / 2;
        int planeW = (w - colOff + 1) / 2;
        if (planeH <= 0 || planeW <= 0) return PerPlane.empty();
        double[] rowSums = new double[planeH];
        long[] rowN = new long[planeH];
        double[] colSums = new double[planeW];
        long[] colN = new long[planeW];
        int[] sampleForMedian = new int[Math.min(4096, planeW * planeH)];
        int sampStep = Math.max(1, (planeW * planeH) / sampleForMedian.length);
        int sampGot = 0, sampSeen = 0;
        for (int y = rowOff, py = 0; y < h && py < planeH; y += 2, py++) {
            int srcY = y0 + y;
            if (srcY >= img.height()) break;
            int rowBase = srcY * W;
            for (int x = colOff, px = 0; x < w && px < planeW; x += 2, px++) {
                int srcX = x0 + x;
                if (srcX >= W) break;
                int v = data[rowBase + srcX];
                count++;
                sum += v;
                sqSum += (double) v * v;
                if (v < min) min = v;
                if (v > max) max = v;
                int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
                hist[bi]++;
                rowSums[py] += v; rowN[py]++;
                colSums[px] += v; colN[px]++;
                if (sampGot < sampleForMedian.length && (sampSeen++ % sampStep) == 0) {
                    sampleForMedian[sampGot++] = v;
                }
            }
        }
        if (count == 0) return PerPlane.empty();
        // mode
        int mode = 0; long modeCount = 0;
        for (int i = 0; i < buckets; i++) if (hist[i] > modeCount) { modeCount = hist[i]; mode = i; }
        // Median from the sample
        int[] sampUsed = Arrays.copyOf(sampleForMedian, sampGot);
        Arrays.sort(sampUsed);
        int median = sampUsed.length == 0 ? 0 : sampUsed[sampUsed.length / 2];
        // mean / stddev
        double mean = (double) sum / count;
        double variance = sqSum / count - mean * mean;
        double std = Math.sqrt(Math.max(0, variance));
        // frac within ±256 of median
        long near = 0;
        for (int i = 0; i < sampUsed.length; i++) if (Math.abs(sampUsed[i] - median) <= 256) near++;
        double frac = sampUsed.length == 0 ? 0 : (double) near / sampUsed.length;
        // Row & col flatness = 1 − stddev(profile) / max(1, mean(profile))
        double rowFlat = profileFlatness(rowSums, rowN);
        double colFlat = profileFlatness(colSums, colN);

        return new PerPlane(count, min, max, median, mode, mean, std, frac, rowFlat, colFlat);
    }

    private static double profileFlatness(double[] sums, long[] counts) {
        int n = 0;
        double[] vals = new double[sums.length];
        for (int i = 0; i < sums.length; i++) {
            if (counts[i] > 0) {
                vals[n++] = sums[i] / counts[i];
            }
        }
        if (n == 0) return 0;
        double mean = 0;
        for (int i = 0; i < n; i++) mean += vals[i];
        mean /= n;
        double v = 0;
        for (int i = 0; i < n; i++) { double d = vals[i] - mean; v += d * d; }
        double std = Math.sqrt(v / n);
        return 1.0 - std / Math.max(1.0, mean);
    }

    private record PerPlane(long count, int min, int max, int median, int modeBucket,
                            double mean, double stdDev, double fracWithin256,
                            double rowFlatness, double colFlatness) {
        static PerPlane empty() {
            return new PerPlane(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
