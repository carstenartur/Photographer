package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.processing.*;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnostic test that saves the EOS R image at EACH stage of the RAW pipeline
 * to identify exactly where the corruption is introduced.
 *
 * <p>Stages saved:
 * <ol>
 *   <li>{@code -stage0-bayer.png} — raw Bayer values after CRX decode, as grayscale</li>
 *   <li>{@code -stage1-blacklevel.png} — after black level subtraction</li>
 *   <li>{@code -stage2-wb.png} — after white balance</li>
 *   <li>{@code -stage3-demosaic.png} — after demosaicing (first RGB stage)</li>
 *   <li>{@code -stage4-colormatrix.png} — after color matrix</li>
 *   <li>{@code -stage5-srgb.png} — after sRGB gamma (final)</li>
 * </ol>
 */
class EosRPipelineDiagnosticTest {

    private static final Logger log = LoggerFactory.getLogger(EosRPipelineDiagnosticTest.class);
    private static final Path OUTPUT_DIR = Path.of("target", "cr3-validation");

    @Test
    void diagnosePipelineStages() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<File> samples = discoverEosRSamples();
        assumeTrue(!samples.isEmpty(), "No eosr-*.cr3 samples found");

        // Use just the first sample for diagnosis
        File cr3File = samples.getFirst();
        String name = cr3File.getName().replace(".cr3", "");
        log.info("=== Diagnosing pipeline for {} ===", cr3File.getName());

        ImageInputStream input = new FileImageInputStream(cr3File);
        Cr3Container container = Cr3Parser.parse(input);
        Cr3Metadata metadata = Cr3MetadataReader.read(input, container);

        log.info("Camera: {}, Sensor: {}x{}, BitDepth: {}", 
                metadata.cameraModel(), metadata.sensorWidth(), metadata.sensorHeight(), metadata.bitDepth());
        log.info("Active area: L={} T={} R={} B={}", 
                metadata.activeAreaLeft(), metadata.activeAreaTop(),
                metadata.activeAreaRight(), metadata.activeAreaBottom());
        log.info("Visible: {}x{}", metadata.visibleWidth(), metadata.visibleHeight());
        if (metadata.perChannelBlackLevels() != null) {
            int[] bl = metadata.perChannelBlackLevels();
            log.info("Black levels: R={} Gr={} Gb={} B={}", bl[0], bl[1], bl[2], bl[3]);
        }
        if (metadata.whiteBalanceMultipliers() != null) {
            double[] wb = metadata.whiteBalanceMultipliers();
            log.info("WB multipliers: R={} G={} B={}", wb[0], wb[1], wb[2]);
        }

        // Find CRX track and decode raw Bayer
        Cr3Track rawTrack = null;
        for (var track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_BIG || track.type() == Cr3Track.TrackType.CRX_RAW) {
                rawTrack = track;
                break;
            }
        }
        assumeTrue(rawTrack != null, "No CRX RAW track");

        int rawWidth = rawTrack.width();
        int rawHeight = rawTrack.height();
        int bitDepth = metadata.bitDepth();
        Cmp1Box cmp1 = rawTrack.cmp1();
        if (cmp1 != null) {
            if (cmp1.imageWidth() > 0) rawWidth = cmp1.imageWidth();
            if (cmp1.imageHeight() > 0) rawHeight = cmp1.imageHeight();
            if (cmp1.bitsPerSample() > 0) bitDepth = cmp1.bitsPerSample();
        }

        byte[] crxData = new byte[(int) rawTrack.dataSize()];
        input.seek(rawTrack.dataOffset());
        input.readFully(crxData);

        CrxHeader.CodecVersion codecVersion = CrxHeader.CodecVersion.V1;
        if (container.cncvBox() != null) {
            codecVersion = CrxHeader.detectVersion(container.cncvBox().versionString());
        }

        Cr3RawImage rawImage = CrxLosslessRawDecoder.decode(crxData, rawWidth, rawHeight, bitDepth, codecVersion, cmp1);

        // ── STAGE 0: Raw Bayer as grayscale ──
        logRawStats("Stage 0 (raw Bayer)", rawImage);
        saveBayerAsGrayscale(rawImage, OUTPUT_DIR.resolve(name + "-stage0-bayer.png"));

        // Crop to active area
        rawImage = cropToActiveArea(rawImage, metadata);
        log.info("After crop: {}x{}", rawImage.width(), rawImage.height());

        // ── STAGE 1: Black level ──
        BlackLevel blackLevel = resolveBlackLevel(rawImage, metadata);
        log.info("BlackLevel: {}", blackLevel);
        BlackLevelProcessor.process(rawImage, blackLevel);
        logRawStats("Stage 1 (after black level)", rawImage);
        saveBayerAsGrayscale(rawImage, OUTPUT_DIR.resolve(name + "-stage1-blacklevel.png"));

        // ── STAGE 2: White balance ──
        double[] wbMult = metadata.whiteBalanceMultipliers() != null 
                ? metadata.whiteBalanceMultipliers() 
                : WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS;
        log.info("WB multipliers: [{}, {}, {}]", wbMult[0], wbMult[1], wbMult[2]);
        WhiteBalanceProcessor.process(rawImage, wbMult);
        logRawStats("Stage 2 (after WB)", rawImage);
        saveBayerAsGrayscale(rawImage, OUTPUT_DIR.resolve(name + "-stage2-wb.png"));

        // ── STAGE 3: Demosaic ──
        BufferedImage rgb = BayerDemosaicer.demosaic(rawImage);
        logRgbStats("Stage 3 (after demosaic)", rgb);
        saveImage(rgb, OUTPUT_DIR.resolve(name + "-stage3-demosaic.png"));

        // ── STAGE 4: Color matrix ──
        double[][] colorMatrix = CameraColorMatrixRegistry.getMatrix(metadata.cameraModel());
        log.info("Color matrix for '{}':", metadata.cameraModel());
        for (double[] row : colorMatrix) {
            log.info("  [{}, {}, {}]", row[0], row[1], row[2]);
        }
        ColorMatrixProcessor.process(rgb, colorMatrix);
        logRgbStats("Stage 4 (after color matrix)", rgb);
        saveImage(rgb, OUTPUT_DIR.resolve(name + "-stage4-colormatrix.png"));

        // ── STAGE 5: sRGB gamma ──
        SrgbConverter.process(rgb);
        logRgbStats("Stage 5 (after sRGB)", rgb);
        saveImage(rgb, OUTPUT_DIR.resolve(name + "-stage5-srgb.png"));

        input.close();
        log.info("=== Pipeline diagnosis complete. Check target/cr3-validation/ ===");
    }

    // ── Also diagnose G5X II for comparison ──
    @Test
    void diagnoseG5xIIPipelineStages() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<File> samples = new ArrayList<>();
        Path samplesDir = Path.of("src", "test", "resources", "samples");
        if (Files.isDirectory(samplesDir)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(samplesDir, "g5x*.cr3")) {
                for (Path p : s) samples.add(p.toFile());
            }
        }
        assumeTrue(!samples.isEmpty(), "No g5x*.cr3 samples found");

        File cr3File = samples.getFirst();
        String name = cr3File.getName().replace(".cr3", "");
        log.info("=== Diagnosing G5X II pipeline for {} (reference) ===", cr3File.getName());

        ImageInputStream input = new FileImageInputStream(cr3File);
        Cr3Container container = Cr3Parser.parse(input);
        Cr3Metadata metadata = Cr3MetadataReader.read(input, container);

        log.info("Camera: {}, Sensor: {}x{}, BitDepth: {}",
                metadata.cameraModel(), metadata.sensorWidth(), metadata.sensorHeight(), metadata.bitDepth());
        log.info("Active area: L={} T={} R={} B={}",
                metadata.activeAreaLeft(), metadata.activeAreaTop(),
                metadata.activeAreaRight(), metadata.activeAreaBottom());
        if (metadata.perChannelBlackLevels() != null) {
            int[] bl = metadata.perChannelBlackLevels();
            log.info("Black levels: R={} Gr={} Gb={} B={}", bl[0], bl[1], bl[2], bl[3]);
        }
        if (metadata.whiteBalanceMultipliers() != null) {
            double[] wb = metadata.whiteBalanceMultipliers();
            log.info("WB multipliers: R={} G={} B={}", wb[0], wb[1], wb[2]);
        }

        // Decode using the normal pipeline for comparison
        Cr3Decoder decoder = new Cr3Decoder();
        input.seek(0);
        decoder.setInput(input);
        BufferedImage result = decoder.decode(Cr3DecodeOptions.rawProcessed());
        logRgbStats("G5X II final output", result);
        saveImage(result, OUTPUT_DIR.resolve(name + "-final.png"));

        input.close();
        log.info("=== G5X II diagnosis complete ===");
    }

    // ── Helpers ──

    private static List<File> discoverEosRSamples() {
        List<File> samples = new ArrayList<>();
        Path dir = Path.of("src", "test", "resources", "samples");
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "eosr-*.cr3")) {
                for (Path p : s) samples.add(p.toFile());
            } catch (Exception ignored) {}
        }
        samples.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return samples;
    }

    private void logRawStats(String stage, Cr3RawImage raw) {
        int[] data = raw.data();
        long sum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int zeroCount = 0;
        for (int v : data) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            if (v == 0) zeroCount++;
        }
        double mean = (double) sum / data.length;
        int maxPossible = (1 << raw.bitDepth()) - 1;
        log.info("{}: {}x{}, {}-bit, min={}, max={} (of {}), mean={}, zeros={}/{} ({}%)",
                stage, raw.width(), raw.height(), raw.bitDepth(),
                min, max, maxPossible, String.format("%.1f", mean),
                zeroCount, data.length, String.format("%.1f", 100.0 * zeroCount / data.length));

        // Spatial autocorrelation: compute correlation between adjacent horizontal pixels
        // Real images: > 0.9. Random noise: ~0.0
        int w = raw.width();
        long sumXY = 0, sumX = 0, sumY = 0, sumX2 = 0, sumY2 = 0;
        int n = 0;
        int stride = Math.max(1, raw.height() / 500);
        for (int y = 0; y < raw.height(); y += stride) {
            for (int x = 0; x < w - 2; x += 2) { // step by 2 to compare same-color Bayer pixels
                int a = data[y * w + x];
                int b = data[y * w + x + 2];
                sumXY += (long) a * b;
                sumX += a;
                sumY += b;
                sumX2 += (long) a * a;
                sumY2 += (long) b * b;
                n++;
            }
        }
        double corr = 0;
        if (n > 1) {
            double num = (double) n * sumXY - (double) sumX * sumY;
            double den = Math.sqrt(((double) n * sumX2 - (double) sumX * sumX)
                    * ((double) n * sumY2 - (double) sumY * sumY));
            corr = den > 0 ? num / den : 0;
        }
        log.info("  Spatial autocorrelation (same-color neighbors): {} — {}",
                String.format("%.4f", corr), corr > 0.8 ? "REAL IMAGE" : "LIKELY NOISE");
    }

    private void logRgbStats(String stage, BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        long sumR = 0, sumG = 0, sumB = 0;
        int n = 0;
        int stride = Math.max(1, Math.min(w, h) / 256);
        for (int y = 0; y < h; y += stride) {
            for (int x = 0; x < w; x += stride) {
                int rgb = img.getRGB(x, y);
                sumR += (rgb >> 16) & 0xFF;
                sumG += (rgb >> 8) & 0xFF;
                sumB += rgb & 0xFF;
                n++;
            }
        }
        log.info("{}: {}x{}, meanR={}, meanG={}, meanB={}",
                stage, w, h,
                String.format("%.1f", (double) sumR / n),
                String.format("%.1f", (double) sumG / n),
                String.format("%.1f", (double) sumB / n));
    }

    private void saveBayerAsGrayscale(Cr3RawImage raw, Path path) throws Exception {
        int w = raw.width(), h = raw.height();
        int maxVal = raw.maxValue();
        // Scale down for reasonable file size
        int scale = Math.max(1, Math.max(w, h) / 2048);
        int sw = w / scale, sh = h / scale;
        BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_USHORT_GRAY);
        short[] out = ((DataBufferUShort) img.getRaster().getDataBuffer()).getData();
        int[] data = raw.data();
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int srcIdx = (y * scale) * w + (x * scale);
                int val = data[srcIdx];
                out[y * sw + x] = (short) Math.min(65535, (int) ((long) val * 65535 / Math.max(1, maxVal)));
            }
        }
        ImageIO.write(img, "PNG", path.toFile());
        log.info("  Saved {} ({}x{})", path.getFileName(), sw, sh);
    }

    private void saveImage(BufferedImage img, Path path) throws Exception {
        // Scale down for reasonable file size
        int w = img.getWidth(), h = img.getHeight();
        int scale = Math.max(1, Math.max(w, h) / 2048);
        if (scale > 1) {
            int sw = w / scale, sh = h / scale;
            BufferedImage small = new BufferedImage(sw, sh, img.getType());
            var g = small.createGraphics();
            g.drawImage(img, 0, 0, sw, sh, null);
            g.dispose();
            ImageIO.write(small, "PNG", path.toFile());
            log.info("  Saved {} ({}x{}, scaled from {}x{})", path.getFileName(), sw, sh, w, h);
        } else {
            ImageIO.write(img, "PNG", path.toFile());
            log.info("  Saved {} ({}x{})", path.getFileName(), w, h);
        }
    }

    private Cr3RawImage cropToActiveArea(Cr3RawImage image, Cr3Metadata meta) {
        if (meta == null) return image;
        int left = Math.max(0, Math.min(meta.activeAreaLeft(), image.width()));
        int top = Math.max(0, Math.min(meta.activeAreaTop(), image.height()));
        int right = Math.max(0, Math.min(meta.activeAreaRight(), image.width()));
        int bottom = Math.max(0, Math.min(meta.activeAreaBottom(), image.height()));
        if (right <= left || bottom <= top) return image;
        int cw = right - left, ch = bottom - top;
        if (cw == image.width() && ch == image.height()) return image;
        int[] src = image.data();
        int[] cropped = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(src, (top + y) * image.width() + left, cropped, y * cw, cw);
        }
        return new Cr3RawImage(cw, ch, cropped, image.bitDepth(), image.bayerPattern());
    }

    private BlackLevel resolveBlackLevel(Cr3RawImage rawImage, Cr3Metadata metadata) {
        if (metadata != null && metadata.activeAreaLeft() >= ObBlackLevelEstimator.MIN_OB_COLUMNS) {
            BlackLevel obLevel = ObBlackLevelEstimator.estimate(rawImage, metadata.activeAreaLeft());
            if (obLevel != null) return obLevel;
        }
        if (metadata != null && metadata.perChannelBlackLevels() != null) {
            return BlackLevel.perChannel(metadata.perChannelBlackLevels());
        }
        return BlackLevel.defaultLevel();
    }
}
