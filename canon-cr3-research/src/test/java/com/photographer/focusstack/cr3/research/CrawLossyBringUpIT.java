package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
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

/** Checks the structure and public native import of local C-RAW captures.
 * The opt-in CrawSensorIT compares every pixel on hash-pinned camera files.
 */
class CrawLossyBringUpIT {

    private static final Logger log = LoggerFactory.getLogger(CrawLossyBringUpIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");

    @Test
    @DisplayName("Local C-RAW fixtures have wavelet subbands and full-sensor output")
    void localCrawFixturesHaveSubbandsAndFullSensorOutput() throws Exception {
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
            log.info("No C-RAW capture under {}", SAMPLES_DIR);
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

        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(probe.absolutePath.toPath());
            var sensor = decoder.decode(Cr3DecodeOptions.rawLinear().applyBlackLevel(false).cropToActiveArea(false));
            assertThat(sensor.getWidth()).isEqualTo(probe.cmp1.imageWidth());
            assertThat(sensor.getHeight()).isEqualTo(probe.cmp1.imageHeight());
            assertThat(sensor.getType()).isEqualTo(java.awt.image.BufferedImage.TYPE_USHORT_GRAY);
        }
    }

    // ─────────────────────────────────────────────────────── helpers ──

    private static LossyMeasurement measureIfLossy(File cr3) throws Exception {
        Cmp1Box cmp1;
        CrxHeader.CodecVersion codecVersion;
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            cmp1 = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG || t.type() == Cr3Track.TrackType.CRX_RAW)
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
                totalBytes, largest, smallest, sortedByPlane, cmp1);
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
        final Cmp1Box cmp1;

        LossyMeasurement(File absolutePath, String path, int waveletLevels, int encodingType,
                         CrxHeader.CodecVersion codecVersion, int tileCount, int planeCount,
                         int subbandCount, int bitDepth, long totalBytes,
                         long largestSubbandBytes, long smallestSubbandBytes,
                         Map<Integer, List<Long>> perPlaneSubbandSizes,
                         Cmp1Box cmp1) {
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
            this.cmp1 = cmp1;
        }
    }
}
