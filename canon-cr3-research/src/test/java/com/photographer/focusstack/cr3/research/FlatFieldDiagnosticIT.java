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
import java.util.List;
import java.util.stream.Stream;

/**
 * Phase A — Work-package 3: flat-field diagnostic harness.
 *
 * <p>A "flat" fixture is a uniformly illuminated, defocused, in-the-mid-range
 * exposure. Under any physically correct decode, a flat-field plane must
 * exhibit:
 * <ul>
 *   <li>low spatial variance — the coefficient of variation
 *       (stddev / mean) sits at the photon shot-noise floor plus PRNU,
 *       typically &lt; 5 % on a base-ISO low-noise capture;</li>
 *   <li>flat row and column mean profiles — neither axis should show a
 *       periodic or monotonically drifting pattern. Periodic banding is the
 *       single most diagnostic signature of a wrong predictor or a wrong
 *       Rice-state evolution;</li>
 *   <li>a single-mode histogram — a multi-modal histogram on a flat field
 *       implies a decoder that maps disjoint scene values to overlapping
 *       output bins, i.e. a bit-budget or sign-mapping bug;</li>
 *   <li>essentially no saturation and essentially no pedestal-clipped
 *       pixels — the operator's job is to expose the flat into the
 *       middle of the dynamic range, so any clipping at either end is a
 *       decoder artefact, not a scene effect.</li>
 * </ul>
 *
 * <p>Together these four checks form a tight physical falsifier: a decoder
 * that scores 0.55 Pearson r vs. the embedded preview but produces 12 %
 * coefficient of variation, 800 LSB peak-to-peak row banding, a bimodal
 * histogram, and 4 % saturated pixels is wrong, regardless of the bulk
 * preview correlation. This is the data-driven black-box anchor for
 * Phase A.</p>
 *
 * <p>The IT walks the corpus, picks anything tagged {@code -flat-} or
 * starting with {@code flat-}, runs the <em>existing</em>
 * {@link CrxLosslessRawDecoder} as a measurement probe, and writes one
 * row per (file, plane) to {@code target/flat-field/flat.tsv}. C-RAW flat
 * fixtures are tagged but not decoded.</p>
 */
class FlatFieldDiagnosticIT {

    private static final Logger log = LoggerFactory.getLogger(FlatFieldDiagnosticIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "flat-field");

    @Test
    @DisplayName("Per-plane flat-field statistics + plausibility verdict for every flat CR3 in the corpus")
    void emitFlatFieldReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }
        List<Path> all = new ArrayList<>();
        collect(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collect(RESEARCH_DIR, all);
        all.sort((a, b) -> a.toString().compareTo(b.toString()));

        List<Path> flats = all.stream()
                .filter(FlatFieldDiagnosticIT::isFlatFixture)
                .toList();
        if (flats.isEmpty()) {
            log.info("No flat fixtures found — skipping. Drop a *-flat-*.cr3 into samples/research/ to populate this report.");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("flat.tsv");
        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("file\tcamera\tcompression\tplane\tcount\tmean\tstdDev\tcoeffVar\t"
                    + "rowFlatPp\trowFlatStd\tcolFlatPp\tcolFlatStd\thistModes\thistGapMaxLSB\t"
                    + "fracSaturated\tfracPedestal\tplausible\n");
            for (Path file : flats) {
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

    private static boolean isFlatFixture(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.contains("-flat-") || n.startsWith("flat-");
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

            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                for (int p = 0; p < 4; p++) {
                    out.write(String.format(
                            "%s\t%s\t%s\t%d\t0\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\tCRAW_NOT_DECODED%n",
                            SAMPLES_DIR.relativize(file), camera, compression, p));
                }
                log.info("  {}: C-RAW flat — placeholder only", file.getFileName());
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
                FlatStats s = computePlane(img, plane);
                String verdict = classify(s, bd);
                out.write(String.format(
                        "%s\t%s\t%s\t%d\t%d\t%.2f\t%.2f\t%.4f\t%d\t%.2f\t%d\t%.2f\t%d\t%d\t%.4f\t%.4f\t%s%n",
                        SAMPLES_DIR.relativize(file), camera, compression, plane,
                        s.count, s.mean, s.stdDev, s.coeffVar,
                        s.rowPp, s.rowStd, s.colPp, s.colStd,
                        s.histModes, s.histGapLsb,
                        s.fracSat, s.fracPedestal, verdict));
                log.info("  {} p[{}] mean={} cv={} rowPp={} colPp={} modes={} gap={} sat={} ped={} → {}",
                        file.getFileName(), plane,
                        String.format("%.0f", s.mean),
                        String.format("%.3f", s.coeffVar),
                        s.rowPp, s.colPp, s.histModes, s.histGapLsb,
                        String.format("%.3f", s.fracSat),
                        String.format("%.3f", s.fracPedestal),
                        verdict);
            }
        }
    }

    /**
     * A flat-field decode is "PLAUSIBLE" iff
     * <ul>
     *   <li>coefficient of variation ≤ 0.10 (10 %); the photon-noise + PRNU
     *       budget is much tighter than this, but we are deliberately loose
     *       here — anything &gt; 10 % CV on a flat is structurally wrong;</li>
     *   <li>row and column peak-to-peak ≤ 5 % of mean (no banding);</li>
     *   <li>histogram has at most 1 strong mode (no bimodal artefacts);</li>
     *   <li>fraction saturated &lt; 1 % AND fraction at pedestal &lt; 1 %.</li>
     * </ul>
     */
    private static String classify(FlatStats s, int bd) {
        boolean cvOk = s.coeffVar <= 0.10;
        boolean rowOk = s.mean > 0 && s.rowPp <= s.mean * 0.05;
        boolean colOk = s.mean > 0 && s.colPp <= s.mean * 0.05;
        boolean modeOk = s.histModes <= 1;
        boolean satOk = s.fracSat < 0.01 && s.fracPedestal < 0.01;
        if (cvOk && rowOk && colOk && modeOk && satOk) return "PLAUSIBLE";
        StringBuilder why = new StringBuilder("IMPLAUSIBLE[");
        if (!cvOk) why.append("cv,");
        if (!rowOk) why.append("rowBand,");
        if (!colOk) why.append("colBand,");
        if (!modeOk) why.append("multiMode,");
        if (!satOk) why.append("clip,");
        if (why.charAt(why.length() - 1) == ',') why.setLength(why.length() - 1);
        why.append(']');
        return why.toString();
    }

    private static FlatStats computePlane(Cr3RawImage img, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int W = img.width();
        int H = img.height();
        int[] data = img.data();
        long count = 0; long sum = 0; double sqSum = 0;
        int planeH = (H - rowOff + 1) / 2;
        int planeW = (W - colOff + 1) / 2;
        if (planeH <= 0 || planeW <= 0) return FlatStats.empty();
        double[] rowMean = new double[planeH];
        long[] rowN = new long[planeH];
        double[] colMean = new double[planeW];
        long[] colN = new long[planeW];
        // Histogram in 64-LSB buckets for mode counting + gap finding.
        int bucketSize = 64;
        int buckets = (1 << img.bitDepth()) / bucketSize;
        long[] hist = new long[buckets];
        long satCount = 0, pedCount = 0;
        int satThresh = (1 << img.bitDepth()) - 32; // top 32 LSB = saturated
        int pedThresh = 32;                          // bottom 32 LSB = pedestal-clipped

        for (int y = rowOff, py = 0; y < H && py < planeH; y += 2, py++) {
            int rowBase = y * W;
            for (int x = colOff, px = 0; x < W && px < planeW; x += 2, px++) {
                int v = data[rowBase + x];
                count++; sum += v; sqSum += (double) v * v;
                rowMean[py] += v; rowN[py]++;
                colMean[px] += v; colN[px]++;
                int bi = Math.min(buckets - 1, Math.max(0, v / bucketSize));
                hist[bi]++;
                if (v >= satThresh) satCount++;
                if (v <= pedThresh) pedCount++;
            }
        }
        if (count == 0) return FlatStats.empty();
        double mean = (double) sum / count;
        double var = sqSum / count - mean * mean;
        double std = Math.sqrt(Math.max(0, var));
        double cv = mean > 0 ? std / mean : 0;
        // Row / column statistics
        for (int i = 0; i < planeH; i++) if (rowN[i] > 0) rowMean[i] /= rowN[i];
        for (int i = 0; i < planeW; i++) if (colN[i] > 0) colMean[i] /= colN[i];
        int[] rowPpStd = ppAndStd(rowMean, rowN);
        int[] colPpStd = ppAndStd(colMean, colN);
        // Histogram modes + biggest gap inside the 5 %–95 % occupancy window
        int modes = countModes(hist);
        int gap = biggestInternalGapLsb(hist, bucketSize);

        return new FlatStats(count, mean, std, cv,
                rowPpStd[0], rowPpStd[1] / 100.0,
                colPpStd[0], colPpStd[1] / 100.0,
                modes, gap,
                (double) satCount / count, (double) pedCount / count);
    }

    private static int[] ppAndStd(double[] mean, long[] n) {
        double pmin = Double.POSITIVE_INFINITY, pmax = Double.NEGATIVE_INFINITY;
        double sum = 0; int k = 0;
        for (int i = 0; i < mean.length; i++) {
            if (n[i] > 0) {
                double v = mean[i];
                if (v < pmin) pmin = v;
                if (v > pmax) pmax = v;
                sum += v; k++;
            }
        }
        if (k == 0) return new int[] { 0, 0 };
        double m = sum / k;
        double v = 0;
        for (int i = 0; i < mean.length; i++) if (n[i] > 0) v += (mean[i] - m) * (mean[i] - m);
        double std = Math.sqrt(v / k);
        int pp = (int) Math.round(pmax - pmin);
        return new int[] { pp, (int) Math.round(std * 100) };
    }

    /** Count clearly-separated modes: peaks ≥ 0.5 × global max, separated by a valley. */
    private static int countModes(long[] hist) {
        long peak = 0;
        for (long v : hist) if (v > peak) peak = v;
        long thresh = (long) (peak * 0.5);
        int modes = 0;
        boolean inMode = false;
        for (long v : hist) {
            if (v >= thresh && !inMode) { modes++; inMode = true; }
            else if (v < thresh * 0.5) inMode = false;
        }
        return modes;
    }

    /** Largest internal zero-run inside the populated portion of the histogram, in LSB. */
    private static int biggestInternalGapLsb(long[] hist, int bucket) {
        // Find the populated 5 %–95 % window then count the longest zero run.
        long total = 0;
        for (long v : hist) total += v;
        if (total == 0) return 0;
        long cum = 0; int lo = 0; int hi = hist.length - 1;
        for (int i = 0; i < hist.length; i++) { cum += hist[i]; if (cum >= total / 20) { lo = i; break; } }
        cum = 0;
        for (int i = hist.length - 1; i >= 0; i--) { cum += hist[i]; if (cum >= total / 20) { hi = i; break; } }
        int run = 0, best = 0;
        for (int i = lo; i <= hi; i++) {
            if (hist[i] == 0) { run++; if (run > best) best = run; }
            else run = 0;
        }
        return best * bucket;
    }

    private record FlatStats(long count, double mean, double stdDev, double coeffVar,
                             int rowPp, double rowStd, int colPp, double colStd,
                             int histModes, int histGapLsb,
                             double fracSat, double fracPedestal) {
        static FlatStats empty() {
            return new FlatStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
