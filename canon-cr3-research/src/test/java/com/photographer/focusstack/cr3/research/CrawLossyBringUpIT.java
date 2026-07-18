package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLossyDecoder;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-RAW (lossy) bring-up scaffolding for the cleanroom decoder.
 *
 * <p>Originally part (b) of the decoder-progress plan was meant to use the
 * operator-shot <em>flat</em> fixtures for IDWT bring-up. Empirical
 * inspection of those captures showed the G5 X Mark II actually emits
 * <em>lossless</em> CRX even at ISO 1600 with the standard RAW format
 * setting (CMP1.{@code waveletLevels}=0); see
 * {@code FlatFixtureLosslessDecoderIT} for the lossless follow-through.</p>
 *
 * <p>This IT is the scaffolding that activates as soon as a genuine C-RAW
 * fixture appears anywhere under {@code src/test/resources/samples/}
 * (recursive). Today it scans every bundled and operator-shot CR3, and:</p>
 * <ul>
 *   <li>If <b>no lossy fixture</b> is found, logs an informational
 *       message documenting how to obtain one and exits cleanly.</li>
 *   <li>If <b>at least one lossy fixture</b> is found, asserts the
 *       physical bring-up invariants on it (subband structure, parser
 *       sanity) and pins the production {@link CrxLossyDecoder} entry
 *       point at its current "blocked" state — so the day a real C-RAW
 *       decoder ships, this IT is the one place that has to change.</li>
 * </ul>
 *
 * <h3>How to obtain a real C-RAW fixture for the G5X II</h3>
 * <ol>
 *   <li>On the camera body: <b>MENU → IMAGE QUALITY → RAW → CRAW</b>.</li>
 *   <li>Mode dial → M, set ISO ≥ 800, shutter ≥ 1/30 s.</li>
 *   <li>Run the capture IT with {@code -Dfixture.scene=craw}:
 *       {@code mvn -B verify -pl camera-ptp -am -Dptp.test.usb=true
 *       -DexcludedGroups= -Djava.awt.headless=true
 *       -Dit.test=CleanroomCr3FixtureCaptureIT -Dfixture.scene=craw}</li>
 *   <li>Re-run this IT — it will pick the new file up automatically.</li>
 * </ol>
 *
 * <h3>Forward-compatible upgrade path (after the IDWT bring-up)</h3>
 * <ol>
 *   <li>Drop the {@code assertThatThrownBy} block.</li>
 *   <li>Call {@link CrxLossyDecoder#decode} for real and assert that the
 *       reconstructed {@code Cr3RawImage} has a per-channel standard
 *       deviation below ~5 % of the mean for a flat-field input — the
 *       strongest physical signal that the IDWT is correct.</li>
 *   <li>Add an LL-only reconstruction (zero the detail bands) and assert
 *       it agrees with the full reconstruction within a few LSB on a
 *       genuinely flat scene.</li>
 * </ol>
 */
class CrawLossyBringUpIT {

    private static final Logger log = LoggerFactory.getLogger(CrawLossyBringUpIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");

    @Test
    @DisplayName("If any lossy CRX fixture exists, its structure is well-formed and the decoder boundary is pinned")
    void anyLossyFixtureIsParseableAndDecoderBoundaryPinned() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples directory at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }

        List<Path> allCr3;
        try (Stream<Path> s = Files.walk(SAMPLES_DIR)) {
            allCr3 = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        log.info("Scanning {} CR3 files for any lossy (C-RAW) fixture…", allCr3.size());

        List<LossyMeasurement> lossy = new ArrayList<>();
        for (Path p : allCr3) {
            LossyMeasurement m = measureIfLossy(p.toFile());
            if (m != null) lossy.add(m);
        }

        if (lossy.isEmpty()) {
            log.info("No lossy CRX fixture present yet. The operator-shot 'flat' fixtures are "
                    + "lossless because the G5X II emits CRX-lossless even at ISO 1600 unless "
                    + "the body is set to CRAW (Image Quality menu). To unblock the lossy "
                    + "decoder bring-up, capture a CRAW frame and the assertion path below "
                    + "will engage automatically — see this class's javadoc.");
            return;
        }

        log.info("═══ Found {} lossy CRX fixture(s) ═══", lossy.size());
        for (LossyMeasurement m : lossy) {
            log.info("  {}", m.path);
            log.info("      waveletLevels={}  encodingType={}  codecVersion={}",
                    m.waveletLevels, m.encodingType, m.codecVersion);
            log.info("      tiles={}  planes/tile={}  subbands={}  bitDepth={}",
                    m.tileCount, m.planeCount, m.subbandCount, m.bitDepth);
            log.info("      payload total={} B  largestSubband={} B  smallestSubband={} B",
                    m.totalBytes, m.largestSubbandBytes, m.smallestSubbandBytes);
            log.info("      perPlane subband sizes: {}", m.perPlaneSubbandSizes);
        }

        // Bring-up invariants on the first lossy fixture
        LossyMeasurement probe = lossy.get(0);
        assertThat(probe.waveletLevels).as("waveletLevels > 0 on a lossy fixture").isPositive();
        assertThat(probe.subbandCount).as("at least one subband").isPositive();
        long meanPerSubband = probe.totalBytes / Math.max(1, probe.subbandCount);
        double dominationRatio = (double) probe.largestSubbandBytes / Math.max(1L, meanPerSubband);
        assertThat(dominationRatio)
                .as("%s: largest subband should dominate per-subband mean (LL band on a "
                        + "real-world scene). ratio=%.2f", probe.path, dominationRatio)
                .isGreaterThan(1.10); // permissive: any LL/HF asymmetry passes

        // Decoder boundary — flips when the lossy decoder is brought up
        assertThatThrownBy(() -> probeLossyDecoder(probe))
                .as("Production CrxLossyDecoder should currently throw with "
                        + "'not yet fully implemented'. When this assertion finally fails, "
                        + "remove the assertThatThrownBy block and replace it with positive "
                        + "reconstruction tests.")
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("not yet fully implemented");
        log.info("  Lossy decoder probe error message (for reference): \"{}\"",
                probe.lossyProbeMessage);
    }

    // ─────────────────────────────────────────────────────── helpers ──

    private static LossyMeasurement measureIfLossy(File cr3) throws Exception {
        Cmp1Box cmp1;
        CrxHeader.CodecVersion codecVersion;
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            cmp1 = c.tracks().stream()
                    .map(Cr3Track::cmp1).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            codecVersion = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());
        }
        if (cmp1 == null || cmp1.isLossless()) return null;

        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(cr3);
        long totalBytes = 0L, largest = 0L, smallest = Long.MAX_VALUE;
        int bitDepth = 0;
        Map<Integer, List<Long>> byPlane = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef r : refs) {
            totalBytes += r.dataSize();
            largest = Math.max(largest, r.dataSize());
            smallest = Math.min(smallest, r.dataSize());
            byPlane.computeIfAbsent(r.planeIndex(), k -> new ArrayList<>()).add(r.dataSize());
            if (bitDepth == 0) bitDepth = r.bitDepth();
        }
        if (refs.isEmpty()) smallest = 0;
        long tileCount = refs.stream().map(SubbandExtractor.SubbandRef::tileSequence)
                .distinct().count();

        Map<Integer, List<Long>> sortedByPlane = new LinkedHashMap<>();
        byPlane.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(e -> {
                    List<Long> v = new ArrayList<>(e.getValue());
                    v.sort(Comparator.reverseOrder());
                    sortedByPlane.put(e.getKey(), v);
                });

        return new LossyMeasurement(cr3.getAbsoluteFile(), cr3.getName(),
                cmp1.waveletLevels(), cmp1.encodingType(), codecVersion,
                (int) tileCount, byPlane.size(), refs.size(), bitDepth,
                totalBytes, largest, smallest, sortedByPlane, null, cmp1);
    }

    private static void probeLossyDecoder(LossyMeasurement m) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(m.absolutePath)) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElseThrow();
            byte[] crx = new byte[(int) raw.dataSize()];
            in.seek(raw.dataOffset());
            in.readFully(crx);
            Cmp1Box cmp1 = m.cmp1 != null ? m.cmp1 : raw.cmp1();
            int w = cmp1 != null && cmp1.imageWidth() > 0 ? cmp1.imageWidth() : raw.width();
            int h = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : raw.height();
            int bd = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            try {
                CrxLossyDecoder.decode(crx, w, h, bd, m.codecVersion, cmp1);
            } catch (Cr3Exception ex) {
                m.lossyProbeMessage = ex.getMessage();
                throw ex;
            }
        }
    }

    private static final class LossyMeasurement {
        final File absolutePath;
        final String path;
        final int waveletLevels;
        final int encodingType;
        final CrxHeader.CodecVersion codecVersion;
        final int tileCount;
        final int planeCount;
        final int subbandCount;
        final int bitDepth;
        final long totalBytes;
        final long largestSubbandBytes;
        final long smallestSubbandBytes;
        final Map<Integer, List<Long>> perPlaneSubbandSizes;
        String lossyProbeMessage;
        final Cmp1Box cmp1;

        LossyMeasurement(File absolutePath, String path, int waveletLevels, int encodingType,
                         CrxHeader.CodecVersion codecVersion, int tileCount, int planeCount,
                         int subbandCount, int bitDepth, long totalBytes,
                         long largestSubbandBytes, long smallestSubbandBytes,
                         Map<Integer, List<Long>> perPlaneSubbandSizes,
                         String lossyProbeMessage, Cmp1Box cmp1) {
            this.absolutePath = absolutePath;
            this.path = path;
            this.waveletLevels = waveletLevels;
            this.encodingType = encodingType;
            this.codecVersion = codecVersion;
            this.tileCount = tileCount;
            this.planeCount = planeCount;
            this.subbandCount = subbandCount;
            this.bitDepth = bitDepth;
            this.totalBytes = totalBytes;
            this.largestSubbandBytes = largestSubbandBytes;
            this.smallestSubbandBytes = smallestSubbandBytes;
            this.perPlaneSubbandSizes = perPlaneSubbandSizes;
            this.lossyProbeMessage = lossyProbeMessage;
            this.cmp1 = cmp1;
        }
    }
}
