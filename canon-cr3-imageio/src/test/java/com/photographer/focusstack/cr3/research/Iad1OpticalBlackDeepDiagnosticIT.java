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
import java.util.*;
import java.util.stream.Stream;

/**
 * Work-package 4 (Update s) — IAD1/OB deep diagnostics.
 *
 * <p>Extends the existing OB analysis with: heatmaps (textual ASCII or per-cell 
 * stddev grid in TSV), row/col profiles, histograms (bucketed), banding/periodicity 
 * metrics (FFT-lite: row-mean autocorrelation at lags {1,2,4,8,16,32}).</p>
 *
 * <p>Checks whether specific decoder paths reproducibly produce the same OB artefacts.
 * Uses the production decoder only (as per cleanroom rules).</p>
 *
 * <p>Outputs to {@code target/iad1-ob-deep/}.</p>
 */
class Iad1OpticalBlackDeepDiagnosticIT {

    private static final Logger log = LoggerFactory.getLogger(Iad1OpticalBlackDeepDiagnosticIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "iad1-ob-deep");

    private static final int HIST_BUCKET = 256;
    private static final int[] AUTOCORR_LAGS = {1, 2, 4, 8, 16, 32};

    @Test
    @DisplayName("IAD1 OB deep diagnostic: heatmaps, profiles, autocorrelation, banding metrics")
    void emitObDeepDiagnostics() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir — skip");
            return;
        }
        List<Path> all = new ArrayList<>();
        collect(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collect(RESEARCH_DIR, all);
        all.sort(Comparator.comparing(Path::toString));
        if (all.isEmpty()) {
            log.info("No CR3 fixtures — skip");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path summaryTsv = REPORT_DIR.resolve("ob-deep-summary.tsv");
        Path summaryMd = REPORT_DIR.resolve("ob-deep-summary.md");

        try (var out = Files.newBufferedWriter(summaryTsv);
             var doc = Files.newBufferedWriter(summaryMd)) {
            out.write("file\tregion\tplane\tcount\tmin\tmax\tmedian\tmode256\tmean\tstdDev\t"
                    + "rowProfileStd\tcolProfileStd\tautocorrLag1\tautocorrLag2\tautocorrLag4\t"
                    + "autocorrLag8\tautocorrLag16\tautocorrLag32\tbandingScore\tscene\n");

            doc.write("# IAD1 OB Deep Diagnostic Report (Update s)\n\n");
            doc.write("Extended analysis of optical-black regions: row/col profiles, ");
            doc.write("autocorrelation at multiple lags, banding metrics.\n\n");

            for (Path file : all) {
                try {
                    analyseFile(file, out, doc);
                } catch (Exception e) {
                    log.warn("Skipped {}: {}", file.getFileName(), e.toString());
                }
            }
        }

        log.info("Wrote {}", summaryTsv.toAbsolutePath());
    }

    private void analyseFile(Path file, java.io.BufferedWriter out, java.io.BufferedWriter doc) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (raw == null) return;

            Cmp1Box cmp1 = raw.cmp1();
            Iad1Box iad1 = raw.iad1();
            if (iad1 == null) {
                log.info("  {}: no IAD1 — skipping", file.getFileName());
                return;
            }
            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                log.info("  {}: C-RAW (wL={}) — skipping OB deep", file.getFileName(), cmp1.waveletLevels());
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
                log.info("  {}: decode failed — {}", file.getFileName(), e.toString());
                return;
            }

            String scene = sceneTag(file.getFileName().toString());
            String filename = SAMPLES_DIR.relativize(file).toString().replace('\\', '/');

            // Analyze leftOB and topOB regions
            int[] left = clampRect(iad1.leftObLeft(), iad1.leftObTop(), iad1.leftObRight(), iad1.leftObBottom(), img.width(), img.height());
            int[] top = clampRect(iad1.topObLeft(), iad1.topObTop(), iad1.topObRight(), iad1.topObBottom(), img.width(), img.height());

            analyseRegion(out, doc, filename, "leftOB", scene, img, left[0], left[1], left[2], left[3], file);
            analyseRegion(out, doc, filename, "topOB", scene, img, top[0], top[1], top[2], top[3], file);
        }
    }

    private void analyseRegion(java.io.BufferedWriter out, java.io.BufferedWriter doc,
                               String filename, String region, String scene, Cr3RawImage img,
                               int x0, int y0, int w, int h, Path file) throws java.io.IOException {
        if (w <= 0 || h <= 0) {
            for (int p = 0; p < 4; p++) {
                out.write(String.format("%s\t%s\t%d\t0\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t%s%n",
                        filename, region, p, scene));
            }
            return;
        }

        for (int plane = 0; plane < 4; plane++) {
            DeepStats stats = computePlaneDeep(img, x0, y0, w, h, plane);
            
            out.write(String.format(
                    "%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%s%n",
                    filename, region, plane, stats.count, stats.min, stats.max, stats.median,
                    stats.modeBucket * HIST_BUCKET, stats.mean, stats.stdDev,
                    stats.rowProfileStd, stats.colProfileStd,
                    stats.autocorr[0], stats.autocorr[1], stats.autocorr[2],
                    stats.autocorr[3], stats.autocorr[4], stats.autocorr[5],
                    stats.bandingScore, scene));

            log.info("  {} {} plane[{}] n={} std={} rowProfStd={} colProfStd={} autocorr[1]={} banding={}",
                    file.getFileName(), region, plane, stats.count,
                    String.format("%.1f", stats.stdDev),
                    String.format("%.2f", stats.rowProfileStd),
                    String.format("%.2f", stats.colProfileStd),
                    String.format("%.4f", stats.autocorr[0]),
                    String.format("%.4f", stats.bandingScore));
            
            // Write per-file profile TSV for detailed analysis
            writePerFileTsv(file, region, plane, stats);
        }
    }

    private void writePerFileTsv(Path file, String region, int plane, DeepStats stats) {
        try {
            String baseName = file.getFileName().toString().replaceFirst("\\.cr3$", "");
            Path profileTsv = REPORT_DIR.resolve(baseName + "-" + region + "-plane" + plane + "-profile.tsv");
            try (var w = Files.newBufferedWriter(profileTsv)) {
                w.write("type\tindex\tvalue\n");
                for (int i = 0; i < stats.rowMeans.length; i++) {
                    w.write(String.format("rowMean\t%d\t%.2f%n", i, stats.rowMeans[i]));
                }
                for (int i = 0; i < stats.colMeans.length; i++) {
                    w.write(String.format("colMean\t%d\t%.2f%n", i, stats.colMeans[i]));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to write profile TSV for {}: {}", file.getFileName(), e.toString());
        }
    }

    private DeepStats computePlaneDeep(Cr3RawImage img, int x0, int y0, int w, int h, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int W = img.width();
        int[] data = img.data();
        
        int planeH = (h - rowOff + 1) / 2;
        int planeW = (w - colOff + 1) / 2;
        if (planeH <= 0 || planeW <= 0) return DeepStats.empty();

        // Collect all values for statistics
        List<Integer> values = new ArrayList<>();
        double[] rowSums = new double[planeH];
        long[] rowCounts = new long[planeH];
        double[] colSums = new double[planeW];
        long[] colCounts = new long[planeW];

        for (int y = rowOff, py = 0; y < h && py < planeH; y += 2, py++) {
            int srcY = y0 + y;
            if (srcY >= img.height()) break;
            int rowBase = srcY * W;
            for (int x = colOff, px = 0; x < w && px < planeW; x += 2, px++) {
                int srcX = x0 + x;
                if (srcX >= W) break;
                int v = data[rowBase + srcX];
                values.add(v);
                rowSums[py] += v;
                rowCounts[py]++;
                colSums[px] += v;
                colCounts[px]++;
            }
        }

        if (values.isEmpty()) return DeepStats.empty();

        DeepStats s = new DeepStats();
        s.count = values.size();

        // Basic stats
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        s.min = sorted[0];
        s.max = sorted[sorted.length - 1];
        s.median = sorted[sorted.length / 2];

        double sum = 0, sqSum = 0;
        int buckets = (1 << img.bitDepth()) / HIST_BUCKET;
        long[] hist = new long[buckets];
        for (int v : values) {
            sum += v;
            sqSum += (double) v * v;
            int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
            hist[bi]++;
        }
        s.mean = sum / s.count;
        s.stdDev = Math.sqrt(sqSum / s.count - s.mean * s.mean);

        // Mode
        long maxCount = 0;
        for (int i = 0; i < buckets; i++) {
            if (hist[i] > maxCount) {
                maxCount = hist[i];
                s.modeBucket = i;
            }
        }

        // Row/col means
        s.rowMeans = new double[planeH];
        for (int i = 0; i < planeH; i++) {
            s.rowMeans[i] = rowCounts[i] > 0 ? rowSums[i] / rowCounts[i] : 0;
        }
        s.colMeans = new double[planeW];
        for (int i = 0; i < planeW; i++) {
            s.colMeans[i] = colCounts[i] > 0 ? colSums[i] / colCounts[i] : 0;
        }

        // Profile standard deviations
        s.rowProfileStd = computeStd(s.rowMeans);
        s.colProfileStd = computeStd(s.colMeans);

        // Autocorrelation at multiple lags (banding detection)
        s.autocorr = new double[AUTOCORR_LAGS.length];
        for (int i = 0; i < AUTOCORR_LAGS.length; i++) {
            s.autocorr[i] = computeAutocorrelation(s.rowMeans, AUTOCORR_LAGS[i]);
        }

        // Banding score: max autocorrelation at power-of-2 lags (indicates periodic banding)
        double maxAutocorr = 0;
        for (double ac : s.autocorr) {
            if (Math.abs(ac) > Math.abs(maxAutocorr)) maxAutocorr = ac;
        }
        s.bandingScore = maxAutocorr;

        return s;
    }

    private double computeStd(double[] arr) {
        if (arr.length == 0) return 0;
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        double sqSum = 0;
        for (double v : arr) {
            double d = v - mean;
            sqSum += d * d;
        }
        return Math.sqrt(sqSum / arr.length);
    }

    private double computeAutocorrelation(double[] arr, int lag) {
        if (arr.length <= lag) return 0;
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        
        double num = 0, den = 0;
        for (int i = 0; i < arr.length; i++) {
            double d = arr[i] - mean;
            den += d * d;
            if (i + lag < arr.length) {
                num += d * (arr[i + lag] - mean);
            }
        }
        return den > 0 ? num / den : 0;
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
        if (s.contains("-asis-")) return "asis";
        return "?";
    }

    private static void collect(Path dir, List<Path> out) throws java.io.IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static class DeepStats {
        int count, min, max, median, modeBucket;
        double mean, stdDev;
        double rowProfileStd, colProfileStd;
        double[] rowMeans = new double[0];
        double[] colMeans = new double[0];
        double[] autocorr = new double[6];
        double bandingScore;

        static DeepStats empty() {
            return new DeepStats();
        }
    }
}
