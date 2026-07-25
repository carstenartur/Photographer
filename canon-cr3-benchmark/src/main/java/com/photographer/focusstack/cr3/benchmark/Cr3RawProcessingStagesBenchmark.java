package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.processing.BlackLevelProcessor;
import com.photographer.focusstack.cr3.processing.ColorMatrixProcessor;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Isolates the major post-entropy stages of the RAW-to-display pipeline.
 *
 * <p>Real decoded Bayer data is prepared once per trial. Mutable stage inputs
 * are restored in invocation setup, outside the timed region, so each score
 * represents the named algorithm rather than array/image cloning or the
 * preceding pipeline stages.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@State(Scope.Thread)
public class Cr3RawProcessingStagesBenchmark {

    @Param({"sample.cr3", "eosr-iso-low.cr3"})
    public String sample;

    private BlackLevel blackLevel;
    private int effectiveWhiteLevel;

    private Cr3RawImage rawBaseline;
    private Cr3RawImage blackAdjustedBaseline;
    private Cr3RawImage whiteBalancedBaseline;
    private BufferedImage demosaicedBaseline;
    private BufferedImage colorCorrectedBaseline;

    private Cr3RawImage blackLevelInput;
    private Cr3RawImage whiteBalanceInput;
    private Cr3RawImage demosaicInput;
    private BufferedImage colorMatrixInput;
    private BufferedImage srgbInput;

    @Setup(Level.Trial)
    public void setupTrial() throws Exception {
        rawBaseline = decodeRawFixture(sample);
        blackLevel = BlackLevel.defaultLevel();
        effectiveWhiteLevel = Math.max(1, rawBaseline.maxValue() - blackLevel.globalLevel());

        blackAdjustedBaseline = copyRaw(rawBaseline);
        BlackLevelProcessor.process(blackAdjustedBaseline, blackLevel);

        whiteBalancedBaseline = copyRaw(blackAdjustedBaseline);
        WhiteBalanceProcessor.process(
                whiteBalancedBaseline,
                WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS);

        demosaicedBaseline = BayerDemosaicer.demosaic(
                whiteBalancedBaseline,
                effectiveWhiteLevel);

        colorCorrectedBaseline = copyImage(demosaicedBaseline);
        ColorMatrixProcessor.process(
                colorCorrectedBaseline,
                ColorMatrixProcessor.DEFAULT_COLOR_MATRIX);
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        blackLevelInput = copyRaw(rawBaseline);
        whiteBalanceInput = copyRaw(blackAdjustedBaseline);
        demosaicInput = copyRaw(whiteBalancedBaseline);
        colorMatrixInput = copyImage(demosaicedBaseline);
        srgbInput = copyImage(colorCorrectedBaseline);
    }

    @Benchmark
    public int subtractBlackLevel() {
        BlackLevelProcessor.process(blackLevelInput, blackLevel);
        return blackLevelInput.data()[blackLevelInput.data().length - 1];
    }

    @Benchmark
    public int applyWhiteBalance() {
        WhiteBalanceProcessor.process(
                whiteBalanceInput,
                WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS);
        return whiteBalanceInput.data()[whiteBalanceInput.data().length - 1];
    }

    @Benchmark
    public BufferedImage demosaicBayer() {
        return BayerDemosaicer.demosaic(demosaicInput, effectiveWhiteLevel);
    }

    @Benchmark
    public int applyColorMatrix() {
        ColorMatrixProcessor.process(
                colorMatrixInput,
                ColorMatrixProcessor.DEFAULT_COLOR_MATRIX);
        return colorMatrixInput.getRGB(colorMatrixInput.getWidth() - 1, colorMatrixInput.getHeight() - 1);
    }

    @Benchmark
    public int convertToSrgb() {
        SrgbConverter.process(srgbInput);
        return srgbInput.getRGB(srgbInput.getWidth() - 1, srgbInput.getHeight() - 1);
    }

    private static Cr3RawImage decodeRawFixture(String sampleName) throws Exception {
        byte[] bytes = SampleFiles.readAllBytes(sampleName);
        Cr3Container container;
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            container = Cr3Parser.parse(input);
        }

        Cr3Track rawTrack = container.tracks().stream()
                .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                        || track.type() == Cr3Track.TrackType.CRX_RAW)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No CRX RAW track in " + sampleName));

        Cmp1Box cmp1 = rawTrack.cmp1();
        int width = cmp1 != null && cmp1.imageWidth() > 0
                ? cmp1.imageWidth() : rawTrack.width();
        int height = cmp1 != null && cmp1.imageHeight() > 0
                ? cmp1.imageHeight() : rawTrack.height();
        int bitDepth = cmp1 != null && cmp1.bitsPerSample() > 0
                ? cmp1.bitsPerSample() : 14;
        CrxHeader.CodecVersion codecVersion = container.cncvBox() != null
                ? CrxHeader.detectVersion(container.cncvBox().versionString())
                : CrxHeader.CodecVersion.V1;

        long offset = rawTrack.dataOffset();
        long size = rawTrack.dataSize();
        if (offset < 0 || size <= 0 || size > Integer.MAX_VALUE || offset + size > bytes.length) {
            throw new IllegalStateException("Invalid CRX track range in " + sampleName);
        }
        byte[] crxData = Arrays.copyOfRange(
                bytes,
                Math.toIntExact(offset),
                Math.toIntExact(offset + size));
        return CrxLosslessRawDecoder.decode(
                crxData,
                width,
                height,
                bitDepth,
                codecVersion,
                cmp1);
    }

    private static Cr3RawImage copyRaw(Cr3RawImage source) {
        return new Cr3RawImage(
                source.width(),
                source.height(),
                Arrays.copyOf(source.data(), source.data().length),
                source.bitDepth(),
                source.bayerPattern());
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }
}
