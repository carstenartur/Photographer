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
 * <p>Each benchmark has its own state class. Real decoded Bayer data is
 * prepared once per trial and only the input needed by that particular stage
 * is restored in invocation setup, outside the timed region. This prevents
 * unrelated large RAW/RGB copies from distorting memory pressure and cache
 * state for another stage.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class Cr3RawProcessingStagesBenchmark {

    @Benchmark
    public int subtractBlackLevel(BlackLevelState state) {
        BlackLevelProcessor.process(state.input, state.blackLevel);
        return lastRawValue(state.input);
    }

    @Benchmark
    public int applyWhiteBalance(WhiteBalanceState state) {
        WhiteBalanceProcessor.process(
                state.input,
                WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS);
        return lastRawValue(state.input);
    }

    @Benchmark
    public BufferedImage demosaicBayer(DemosaicState state) {
        return BayerDemosaicer.demosaic(state.input, state.effectiveWhiteLevel);
    }

    @Benchmark
    public int applyColorMatrix(ColorMatrixState state) {
        ColorMatrixProcessor.process(
                state.input,
                ColorMatrixProcessor.DEFAULT_COLOR_MATRIX);
        return lastRgbValue(state.input);
    }

    @Benchmark
    public int convertToSrgb(SrgbState state) {
        SrgbConverter.process(state.input);
        return lastRgbValue(state.input);
    }

    @State(Scope.Thread)
    public static class BlackLevelState {
        @Param({"sample.cr3", "eosr-iso-low.cr3"})
        public String sample;

        private final BlackLevel blackLevel = BlackLevel.defaultLevel();
        private Cr3RawImage baseline;
        private Cr3RawImage input;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            baseline = decodeRawFixture(sample);
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            input = copyRaw(baseline);
        }
    }

    @State(Scope.Thread)
    public static class WhiteBalanceState {
        @Param({"sample.cr3", "eosr-iso-low.cr3"})
        public String sample;

        private Cr3RawImage baseline;
        private Cr3RawImage input;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            baseline = decodeRawFixture(sample);
            BlackLevelProcessor.process(baseline, BlackLevel.defaultLevel());
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            input = copyRaw(baseline);
        }
    }

    @State(Scope.Thread)
    public static class DemosaicState {
        @Param({"sample.cr3", "eosr-iso-low.cr3"})
        public String sample;

        private Cr3RawImage baseline;
        private Cr3RawImage input;
        private int effectiveWhiteLevel;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            baseline = decodeRawFixture(sample);
            BlackLevel blackLevel = BlackLevel.defaultLevel();
            effectiveWhiteLevel = Math.max(1, baseline.maxValue() - blackLevel.globalLevel());
            BlackLevelProcessor.process(baseline, blackLevel);
            WhiteBalanceProcessor.process(
                    baseline,
                    WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS);
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            input = copyRaw(baseline);
        }
    }

    @State(Scope.Thread)
    public static class ColorMatrixState {
        @Param({"sample.cr3", "eosr-iso-low.cr3"})
        public String sample;

        private BufferedImage baseline;
        private BufferedImage input;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            baseline = createDemosaicedFixture(sample);
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            input = copyImage(baseline);
        }
    }

    @State(Scope.Thread)
    public static class SrgbState {
        @Param({"sample.cr3", "eosr-iso-low.cr3"})
        public String sample;

        private BufferedImage baseline;
        private BufferedImage input;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            baseline = createDemosaicedFixture(sample);
            ColorMatrixProcessor.process(
                    baseline,
                    ColorMatrixProcessor.DEFAULT_COLOR_MATRIX);
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            input = copyImage(baseline);
        }
    }

    private static BufferedImage createDemosaicedFixture(String sampleName) throws Exception {
        Cr3RawImage raw = decodeRawFixture(sampleName);
        BlackLevel blackLevel = BlackLevel.defaultLevel();
        int whiteLevel = Math.max(1, raw.maxValue() - blackLevel.globalLevel());
        BlackLevelProcessor.process(raw, blackLevel);
        WhiteBalanceProcessor.process(raw, WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS);
        return BayerDemosaicer.demosaic(raw, whiteLevel);
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

    private static int lastRawValue(Cr3RawImage image) {
        return image.data()[image.data().length - 1];
    }

    private static int lastRgbValue(BufferedImage image) {
        return image.getRGB(image.getWidth() - 1, image.getHeight() - 1);
    }
}
