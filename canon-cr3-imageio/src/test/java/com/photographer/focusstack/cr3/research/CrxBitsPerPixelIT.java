package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bits-per-pixel diagnostic for every CRX subband across every bundled CR3 fixture.
 *
 * <p>Two measurements are reported per subband:</p>
 * <ol>
 *   <li><b>Container bits/pixel</b> — {@code (payload_bytes × 8) / (planeWidth × planeHeight)}.
 *       Treats the subband payload as a raw bit budget and divides by the pixel grid.
 *       Useful as a sanity bound: any candidate decoder whose mean code length differs
 *       grossly from this value cannot decode the subband cleanly.</li>
 *   <li><b>Empirical Rice k per subband</b> — the smallest fixed-k value for which a
 *       pure Golomb-Rice decoder consumes <em>at least</em> all {@code planeWidth × planeHeight}
 *       symbols from the payload, plus the residual byte count after exactly that many
 *       symbols. The k for which leftover bytes is closest to zero is the strongest
 *       candidate for the per-subband Rice parameter (or its arithmetic mean for adaptive
 *       schemes).</li>
 * </ol>
 *
 * <p>This test is informational and does not assert anything about specific
 * numerical results — its value is the table printed to stdout, captured in
 * {@code SubbandFixtures.md}.</p>
 */
class CrxBitsPerPixelIT {

    private static final String SAMPLES_DIR = "src/test/resources/samples";
    private static final int K_MAX = 14;

    @Test
    void mapBitsPerPixelAcrossAllFixtures() throws Exception {
        File dir = new File(SAMPLES_DIR);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".cr3"));
        assumeTrue(files != null && files.length > 0, "No CR3 fixtures present");

        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "%n=== CRX bits/pixel diagnostic across all bundled fixtures ===%n"));
        report.append(String.format(
                "%-26s %-3s %-3s %-3s %7s %7s %12s %8s %3s %12s%n",
                "fixture", "tlS", "pl", "sbI", "W", "H",
                "payloadBytes", "bits/px", "qV", "bestK[leftover]"));

        List<String[]> rows = new ArrayList<>();

        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            List<SubbandExtractor.SubbandRef> subs;
            try {
                subs = SubbandExtractor.list(f);
            } catch (Exception e) {
                report.append(String.format("%s: list failed: %s%n", f.getName(), e.getMessage()));
                continue;
            }
            for (SubbandExtractor.SubbandRef s : subs) {
                long pixels = (long) s.planeWidth() * s.planeHeight();
                if (pixels <= 0 || s.dataSize() <= 0) {
                    continue;
                }
                double bitsPerPx = (s.dataSize() * 8.0) / pixels;

                byte[] payload;
                try {
                    payload = SubbandExtractor.read(f, s);
                } catch (Exception e) {
                    continue;
                }

                BestK best = findBestK(payload, (int) pixels);

                String row = String.format(
                        "%-26s %-3d %-3d %-3d %7d %7d %12d %8.4f %3d %3d[%+d]%n",
                        f.getName(), s.tileSequence(), s.planeIndex(), s.subbandIndex(),
                        s.planeWidth(), s.planeHeight(), s.dataSize(), bitsPerPx,
                        s.quantValue(), best.k, best.leftoverBytes);
                report.append(row);
                rows.add(new String[]{f.getName(), String.valueOf(best.k),
                        String.valueOf(s.quantValue()), String.format("%.3f", bitsPerPx)});
            }
        }
        // Concise per-fixture summary at the end so the relationship pops out.
        report.append("\n--- Summary: bestK distribution per fixture ---\n");
        String prev = "";
        StringBuilder kList = new StringBuilder();
        for (String[] r : rows) {
            if (!r[0].equals(prev)) {
                if (!prev.isEmpty()) {
                    report.append(prev).append(": k=[").append(kList).append("]\n");
                }
                prev = r[0];
                kList.setLength(0);
            }
            if (kList.length() > 0) kList.append(",");
            kList.append(r[1]);
        }
        if (!prev.isEmpty()) {
            report.append(prev).append(": k=[").append(kList).append("]\n");
        }

        System.out.println(report);
        assertThat(rows).as("at least one subband measured").isNotEmpty();
    }

    /**
     * Find the fixed-k Rice parameter that, when used to decode {@code pixels}
     * symbols from {@code payload}, leaves the smallest absolute byte gap relative
     * to the payload boundary. Negative leftover means "ran out of bits before
     * decoding all pixels"; positive means "had bytes left over after pixels".
     */
    private static BestK findBestK(byte[] payload, int pixels) {
        BestK best = new BestK(-1, Integer.MAX_VALUE);
        for (int k = 0; k <= K_MAX; k++) {
            int leftover = simulateBytesLeftover(payload, pixels, k);
            if (Math.abs(leftover) < Math.abs(best.leftoverBytes)) {
                best = new BestK(k, leftover);
            }
        }
        return best;
    }

    /**
     * Simulate decoding {@code pixels} pure Rice-Golomb residuals with parameter
     * {@code k} from {@code payload}. Returns positive number of bytes left in
     * the payload after decoding all pixels (under-utilised), negative if the
     * payload was exhausted before decoding all pixels (over-utilised), or
     * Integer.MAX_VALUE if the simulation failed.
     */
    private static int simulateBytesLeftover(byte[] payload, int pixels, int k) {
        try {
            BitReader r = new BitReader(payload, 0, payload.length);
            long bitsConsumed = 0;
            for (int p = 0; p < pixels; p++) {
                if (!r.hasMore()) {
                    // Ran out of bits — convert the deficit to a negative leftover-byte count.
                    long needed = (long) (pixels - p) * (k + 1);
                    return -(int) Math.min(Integer.MAX_VALUE, (needed + 7) / 8);
                }
                // Unary q
                int q = 0;
                while (r.hasMore()) {
                    bitsConsumed++;
                    if (r.readBit() != 0) break;
                    q++;
                    if (q > 64) return Integer.MAX_VALUE; // garbage
                }
                // k-bit remainder
                for (int i = 0; i < k; i++) {
                    if (!r.hasMore()) {
                        long needed = (long) (pixels - p - 1) * (k + 1) + (k - i);
                        return -(int) Math.min(Integer.MAX_VALUE, (needed + 7) / 8);
                    }
                    r.readBit();
                    bitsConsumed++;
                }
            }
            long leftoverBits = (long) payload.length * 8 - bitsConsumed;
            return (int) (leftoverBits / 8);
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
    }

    private record BestK(int k, int leftoverBytes) {}
}
