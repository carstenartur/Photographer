package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
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
 * Phase A — Work-package 2: null-fixture diagnostic harness.
 *
 * <p>A "null fixture" is an exposure of a near-information-free scene (lens
 * cap on, pitch dark, fastest shutter). The pixel field of a null fixture
 * collapses, on a physically correct decoder, to:
 * <ul>
 *   <li>per-plane median ≈ camera pedestal (typ. 256–4096 LSB at 14 bit);</li>
 *   <li>per-plane stddev ≈ read-noise floor (typ. ≤ 30 LSB at base ISO,
 *       scales with ISO);</li>
 *   <li>fraction of pixels within ±256 LSB of the per-plane mode ≥ 0.95;</li>
 *   <li>row/column profiles flat to ≤ 4 LSB peak-to-peak.</li>
 * </ul>
 *
 * <p>Any structured deviation (banding, hot rows, multimodal histograms)
 * on a null fixture is by physics not in the data — it is in the decoder
 * state. This makes null fixtures the strongest available diagnostic.</p>
 *
 * <p>This IT is fully data-driven. It walks {@code samples/} and
 * {@code samples/research/}, picks any file whose name matches the dark
 * tag (currently {@code -dark-} or starts with {@code dark-}), and emits
 * one TSV row per (file, plane). New dark fixtures are picked up
 * automatically — no IT change is required.</p>
 *
 * <p>The IT uses the <em>existing</em> {@link CrxLosslessRawDecoder} only
 * as a measurement probe; no new decoder hypotheses are tried, no broad
 * sweeps are performed. C-RAW dark fixtures are tagged but not decoded
 * (the production lossless decoder cannot handle them); this is itself
 * recorded so a later C-RAW path is automatically exercised once added.</p>
 *
 * <p>Output: {@code target/null-fixture/null.tsv}.</p>
 */
class NullFixtureDiagnosticIT {

    private static final Logger log = LoggerFactory.getLogger(NullFixtureDiagnosticIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "null-fixture");

    private static final int HIST_BUCKET = 64;

    @Test
    @DisplayName("Per-plane null-fixture statistics for every dark/null CR3 in the corpus")
    void emitNullFixtureReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }
        List<Path> all = new ArrayList<>();
        collect(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collect(RESEARCH_DIR, all);
        all.sort((a, b) -> a.toString().compareTo(b.toString()));

        List<Path> nullFixtures = all.stream()
                .filter(NullFixtureDiagnosticIT::isNullFixture)
                .toList();
        if (nullFixtures.isEmpty()) {
            log.info("No null/dark fixtures found in corpus — skipping. "
                    + "Drop a *-dark-*.cr3 into samples/research/ to populate this report.");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("null.tsv");
        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("file\tcamera\tcompression\tplane\tcount\tmin\tmedian\tmode64\t"
                    + "mean\tstdDev\tmax\tfracWithin256OfMode\tfracWithin64OfMode\t"
                    + "rowFlatPp\tcolFlatPp\tneighbourCorr\tplausible\n");
            for (Path file : nullFixtures) {
                try {
                    analyse(file, out);
                } catch (Exception ex) {
                    log.warn("Skipped {}: {}", file.getFileName(), ex.toString());
                }
            }
        }
        log.info("Wrote {}", tsv.toAbsolutePath());
    }

    // ---------- helpers ----------

    private static boolean isNullFixture(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.contains("-dark-") || n.startsWith("dark-") || n.contains("-null-");
    }

    private static void collect(Path dir, List<Path> out) throws java.io.IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static String camera(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        if (n.startsWith("g5xm2") || n.startsWith("sample")) return "g5xm2";
        if (n.startsWith("eosr")) return "eosr";
        int dash = n.indexOf('-');
        return dash > 0 ? n.substring(0, dash) : "?";
    }

    private static void analyse(Path file, java.io.BufferedWriter out) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (raw == null) {
                log.info("  {}: no CRX_BIG track — skipping", file.getFileName());
                return;
            }
            Cmp1Box cmp1 = raw.cmp1();
            String camera = camera(file);
            String compression = String.valueOf(raw.compressionMode());

            // C-RAW: emit a single tag row per plane so the absence is visible
            // and the IT remains green; do not run the lossless decoder on it.
            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                for (int p = 0; p < 4; p++) {
                    out.write(String.format(
                            "%s\t%s\t%s\t%d\t0\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\tCRAW_NOT_DECODED%n",
                            SAMPLES_DIR.relativize(file), camera, compression, p));
                }
                log.info("  {}: C-RAW dark — recording placeholder (no lossless decode)",
                        file.getFileName());
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

            for (int plane = 0; plane < 4; plane++) {
                NullStats s = computePlane(img, plane);
                String plausible = classify(s, bd);
                out.write(String.format(
                        "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%d\t%.4f\t%.4f\t%d\t%d\t%.4f\t%s%n",
                        SAMPLES_DIR.relativize(file), camera, compression, plane,
                        s.count, s.min, s.median, s.modeBucket * HIST_BUCKET,
                        s.mean, s.stdDev, s.max, s.fracWithin256, s.fracWithin64,
                        s.rowPp, s.colPp, s.neighbourCorr, plausible));
                log.info("  {} p[{}] median={} mode≈{} std={} fracWithin256={} rowPp={} colPp={} corr={} → {}",
                        file.getFileName(), plane, s.median, s.modeBucket * HIST_BUCKET,
                        String.format("%.1f", s.stdDev),
                        String.format("%.3f", s.fracWithin256),
                        s.rowPp, s.colPp, String.format("%.3f", s.neighbourCorr),
                        plausible);
            }
        }
    }

    /**
     * Verdict against the physical expectations of a null fixture. Boundaries
     * are deliberately loose: the goal is to make obvious decoder failures
     * stand out, not to enforce a calibration. A "PLAUSIBLE" verdict requires
     * <ul>
     *   <li>stdDev ≤ {@code 1 % of full scale} (typ. 160 LSB at 14 bit);</li>
     *   <li>fraction within ±256 LSB of the mode ≥ 0.85;</li>
     *   <li>row/column peak-to-peak profile spread ≤ 256 LSB;</li>
     *   <li>nearest-neighbour Pearson r ≤ 0.5 (read noise is uncorrelated).</li>
     * </ul>
     */
    private static String classify(NullStats s, int bd) {
        double fullScale = (double) (1 << bd);
        boolean stdOk = s.stdDev <= fullScale * 0.01;
        boolean modeOk = s.fracWithin256 >= 0.85;
        boolean rowOk = s.rowPp <= 256;
        boolean colOk = s.colPp <= 256;
        boolean corrOk = Math.abs(s.neighbourCorr) <= 0.5;
        if (stdOk && modeOk && rowOk && colOk && corrOk) return "PLAUSIBLE";
        StringBuilder why = new StringBuilder("IMPLAUSIBLE[");
        if (!stdOk) why.append("std,");
        if (!modeOk) why.append("mode,");
        if (!rowOk) why.append("row,");
        if (!colOk) why.append("col,");
        if (!corrOk) why.append("corr,");
        if (why.charAt(why.length() - 1) == ',') why.setLength(why.length() - 1);
        why.append(']');
        return why.toString();
    }

    private static NullStats computePlane(Cr3RawImage img, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int W = img.width();
        int H = img.height();
        int[] data = img.data();
        long count = 0; long sum = 0; double sqSum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int buckets = (1 << img.bitDepth()) / HIST_BUCKET;
        long[] hist = new long[buckets];
        int planeH = (H - rowOff + 1) / 2;
        int planeW = (W - colOff + 1) / 2;
        if (planeH <= 0 || planeW <= 0) return NullStats.empty();
        // row / col mean profiles
        double[] rowSums = new double[planeH];
        long[] rowN = new long[planeH];
        double[] colSums = new double[planeW];
        long[] colN = new long[planeW];
        // Sample for median + neighbour correlation
        int[] sample = new int[Math.min(8192, planeW * planeH)];
        int[] sampleNext = new int[sample.length]; // horizontal next pixel on the same plane
        int sampGot = 0;
        int sampStep = Math.max(1, (planeW * planeH) / sample.length);
        int sampSeen = 0;

        for (int y = rowOff, py = 0; y < H && py < planeH; y += 2, py++) {
            int rowBase = y * W;
            for (int x = colOff, px = 0; x < W && px < planeW; x += 2, px++) {
                int v = data[rowBase + x];
                count++; sum += v; sqSum += (double) v * v;
                if (v < min) min = v;
                if (v > max) max = v;
                int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
                hist[bi]++;
                rowSums[py] += v; rowN[py]++;
                colSums[px] += v; colN[px]++;
                if (sampGot < sample.length && (sampSeen++ % sampStep) == 0) {
                    sample[sampGot] = v;
                    int xn = x + 2;
                    sampleNext[sampGot] = (xn < W) ? data[rowBase + xn] : v;
                    sampGot++;
                }
            }
        }
        if (count == 0) return NullStats.empty();

        // mode bucket
        int mode = 0; long modeCount = 0;
        for (int i = 0; i < buckets; i++) if (hist[i] > modeCount) { modeCount = hist[i]; mode = i; }
        int modeCenter = mode * HIST_BUCKET + HIST_BUCKET / 2;
        // median + frac-within from sample
        int[] used = Arrays.copyOf(sample, sampGot);
        int[] usedNext = Arrays.copyOf(sampleNext, sampGot);
        Arrays.sort(used);
        int median = used.length == 0 ? 0 : used[used.length / 2];
        long n256 = 0, n64 = 0;
        for (int v : used) {
            if (Math.abs(v - modeCenter) <= 256) n256++;
            if (Math.abs(v - modeCenter) <= 64)  n64++;
        }
        double frac256 = used.length == 0 ? 0 : (double) n256 / used.length;
        double frac64  = used.length == 0 ? 0 : (double) n64  / used.length;
        // mean / stddev
        double mean = (double) sum / count;
        double variance = sqSum / count - mean * mean;
        double std = Math.sqrt(Math.max(0, variance));
        // row / col peak-to-peak of the mean profile
        int rowPp = profilePeakToPeak(rowSums, rowN);
        int colPp = profilePeakToPeak(colSums, colN);
        // Nearest-neighbour Pearson on the sample
        double corr = pearson(used, usedNext);
        return new NullStats(count, min, max, median, mode, mean, std,
                             frac256, frac64, rowPp, colPp, corr);
    }

    private static int profilePeakToPeak(double[] sums, long[] counts) {
        double pmin = Double.POSITIVE_INFINITY, pmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < sums.length; i++) {
            if (counts[i] > 0) {
                double v = sums[i] / counts[i];
                if (v < pmin) pmin = v;
                if (v > pmax) pmax = v;
            }
        }
        if (pmin == Double.POSITIVE_INFINITY) return 0;
        return (int) Math.round(pmax - pmin);
    }

    private static double pearson(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 8) return 0;
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; }
        double ma = sa / n, mb = sb / n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        double denom = Math.sqrt(da * db);
        return denom <= 0 ? 0 : num / denom;
    }

    private record NullStats(long count, int min, int max, int median, int modeBucket,
                             double mean, double stdDev, double fracWithin256, double fracWithin64,
                             int rowPp, int colPp, double neighbourCorr) {
        static NullStats empty() {
            return new NullStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
