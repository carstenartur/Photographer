package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.benchmark.SampleFiles;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Measures the explicit stages that make up the CRX lossless RAW decoder.
 *
 * <p>The benchmarks use real CRX payloads for header parsing, plane decoding,
 * prediction and plane assembly. Very small per-symbol operations use a fixed,
 * deterministic synthetic sequence so JMH can report stable nanoseconds per
 * symbol without file-I/O or fixture-content noise.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CrxDecoderStagesBenchmark {

    private static final int COMPONENT_OPERATIONS = 16_384;
    private static final int SYNTHETIC_RICE_K = 2;
    private static final int SYNTHETIC_ROW_WIDTH = 256;

    /**
     * One PowerShot and one EOS fixture provide different image geometries while
     * keeping the longitudinal full-suite runtime bounded.
     */
    @Param({"sample.cr3", "eosr-iso-low.cr3"})
    public String sample;

    private byte[] crxPayload;
    private CrxHeader.CodecVersion codecVersion;
    private int bitDepth;

    private int subbandOffset;
    private int subbandEnd;
    private int tilePlaneWidth;
    private int tilePlaneHeight;

    private int[] decodedTile;
    private int[] copiedPlane;
    private int[][] positionalPlanes;
    private int[] interleavedBayer;
    private int bayerWidth;
    private int bayerHeight;
    private int planeWidth;
    private int planeHeight;

    private byte[] syntheticRiceData;
    private int[] encodedResiduals;
    private int[] signedResiduals;
    private CrxRiceAdaptationState adaptationState;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        byte[] fileBytes = SampleFiles.readAllBytes(sample);
        Cr3Container container;
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(fileBytes))) {
            container = Cr3Parser.parse(input);
        }

        Cr3Track rawTrack = container.tracks().stream()
                .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                        || track.type() == Cr3Track.TrackType.CRX_RAW)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No CRX RAW track in " + sample));

        Cmp1Box cmp1 = rawTrack.cmp1();
        bayerWidth = cmp1 != null && cmp1.imageWidth() > 0
                ? cmp1.imageWidth() : rawTrack.width();
        bayerHeight = cmp1 != null && cmp1.imageHeight() > 0
                ? cmp1.imageHeight() : rawTrack.height();
        bitDepth = cmp1 != null && cmp1.bitsPerSample() > 0
                ? cmp1.bitsPerSample() : 14;
        codecVersion = container.cncvBox() != null
                ? CrxHeader.detectVersion(container.cncvBox().versionString())
                : CrxHeader.CodecVersion.V1;

        long rawOffset = rawTrack.dataOffset();
        long rawSize = rawTrack.dataSize();
        if (rawOffset < 0 || rawSize <= 0 || rawOffset + rawSize > fileBytes.length
                || rawSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid CRX track range in " + sample);
        }
        byte[] crxData = Arrays.copyOfRange(
                fileBytes, (int) rawOffset, Math.toIntExact(rawOffset + rawSize));

        ParsedCrx parsed = parseCrxData(crxData, codecVersion, cmp1);
        crxPayload = parsed.data();
        CrxHeader header = parsed.header();
        if (header.tiles().isEmpty()) {
            throw new IllegalStateException("No CRX tiles in " + sample);
        }

        CrxHeader.Tile firstTile = header.tiles().stream()
                .min(Comparator.comparingInt(CrxHeader.Tile::index))
                .orElseThrow();
        CrxHeader.Plane firstPlane = firstTile.planes().stream()
                .min(Comparator.comparingInt(CrxHeader.Plane::index))
                .orElseThrow(() -> new IllegalStateException("No CRX planes in " + sample));
        CrxHeader.Subband firstSubband = firstPlane.subbands().stream()
                .min(Comparator.comparingInt(CrxHeader.Subband::index))
                .orElseThrow(() -> new IllegalStateException("No CRX subbands in " + sample));

        planeWidth = (bayerWidth + 1) / 2;
        planeHeight = (bayerHeight + 1) / 2;
        int tileCount = header.tiles().size();
        tilePlaneWidth = resolveTilePlaneWidth(cmp1, tileCount, planeWidth, bayerWidth);
        tilePlaneHeight = resolveTilePlaneHeight(cmp1, planeHeight);

        subbandOffset = Math.toIntExact(firstSubband.dataOffset());
        subbandEnd = Math.min(crxPayload.length,
                Math.toIntExact(firstSubband.dataOffset() + firstSubband.dataSize()));
        if (subbandEnd <= subbandOffset) {
            throw new IllegalStateException("Invalid first subband range in " + sample);
        }

        decodedTile = CrxLosslessPlaneDecoder.decode(
                crxPayload,
                subbandOffset,
                subbandEnd,
                tilePlaneWidth,
                tilePlaneHeight,
                bitDepth,
                CrxLosslessRawDecoder.DEFAULT_K_UPDATE_RULE);

        copiedPlane = new int[Math.multiplyExact(planeWidth, tilePlaneHeight)];
        positionalPlanes = new int[4][Math.multiplyExact(planeWidth, planeHeight)];
        for (int plane = 0; plane < positionalPlanes.length; plane++) {
            int[] destination = positionalPlanes[plane];
            for (int i = 0; i < destination.length; i++) {
                destination[i] = decodedTile[i % decodedTile.length] + plane;
            }
        }
        interleavedBayer = new int[Math.multiplyExact(bayerWidth, bayerHeight)];

        encodedResiduals = new int[COMPONENT_OPERATIONS];
        signedResiduals = new int[COMPONENT_OPERATIONS];
        for (int i = 0; i < COMPONENT_OPERATIONS; i++) {
            int magnitude = i & 15;
            encodedResiduals[i] = (magnitude << 1) | (i & 1);
            signedResiduals[i] = (i & 1) == 0 ? magnitude : -magnitude;
        }
        syntheticRiceData = encodeRiceSymbols(encodedResiduals, SYNTHETIC_RICE_K);
    }

    @Setup(Level.Invocation)
    public void resetInvocationState() {
        adaptationState = new CrxRiceAdaptationState(
                CrxLosslessRawDecoder.DEFAULT_K_UPDATE_RULE, bitDepth);
    }

    @Benchmark
    public CrxHeader parseHeader() throws IOException {
        return CrxHeader.parse(crxPayload, codecVersion);
    }

    @Benchmark
    public int[] decodeFirstPlane() throws IOException {
        return CrxLosslessPlaneDecoder.decode(
                crxPayload,
                subbandOffset,
                subbandEnd,
                tilePlaneWidth,
                tilePlaneHeight,
                bitDepth,
                CrxLosslessRawDecoder.DEFAULT_K_UPDATE_RULE);
    }

    @Benchmark
    public int copyFirstTilePlane() {
        CrxPlaneAssembler.copyTilePlane(
                decodedTile,
                copiedPlane,
                tilePlaneWidth,
                tilePlaneHeight,
                planeWidth,
                0);
        int writtenIndex = (tilePlaneHeight - 1) * planeWidth + tilePlaneWidth - 1;
        return copiedPlane[writtenIndex];
    }

    @Benchmark
    public int interleavePositionalPlanes() {
        CrxPlaneAssembler.interleaveByPosition(
                positionalPlanes,
                interleavedBayer,
                bayerWidth,
                bayerHeight,
                planeWidth,
                planeHeight);
        return interleavedBayer[interleavedBayer.length - 1];
    }

    @Benchmark
    public long predictFirstPlane() {
        long checksum = 0;
        for (int y = 0; y < tilePlaneHeight; y++) {
            for (int x = 0; x < tilePlaneWidth; x++) {
                checksum += CrxMedPredictor.predict(decodedTile, tilePlaneWidth, x, y);
            }
        }
        return checksum;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long decodeRiceSymbols() throws IOException {
        BitReader reader = new BitReader(syntheticRiceData);
        long checksum = 0;
        for (int i = 0; i < COMPONENT_OPERATIONS; i++) {
            checksum += CrxRiceSymbolDecoder.decode(
                    reader,
                    SYNTHETIC_RICE_K,
                    CrxRiceSymbolDecoder.UnaryDirection.ONES_UNTIL_ZERO);
        }
        return checksum;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long mapResidualSigns() {
        long checksum = 0;
        for (int encodedResidual : encodedResiduals) {
            checksum += CrxResidualSignMapper.decode(
                    encodedResidual,
                    CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE);
        }
        return checksum;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long updateRiceAdaptation() {
        long checksum = 0;
        for (int i = 0; i < signedResiduals.length; i++) {
            if (i % SYNTHETIC_ROW_WIDTH == 0) {
                adaptationState.startRow();
            }
            adaptationState.update(signedResiduals[i]);
            checksum += adaptationState.k();
        }
        return checksum;
    }

    private static ParsedCrx parseCrxData(
            byte[] data,
            CrxHeader.CodecVersion version,
            Cmp1Box cmp1) throws IOException {
        try {
            CrxHeader header = CrxHeader.parse(data, version);
            if (!header.tiles().isEmpty()) {
                return new ParsedCrx(data, header);
            }
        } catch (IOException firstError) {
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize <= 0 || headerSize >= data.length) {
                throw firstError;
            }
        }

        int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
        if (headerSize > 0 && headerSize < data.length) {
            byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
            return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
        }
        return new ParsedCrx(data, CrxHeader.parse(data, version));
    }

    private static int resolveTilePlaneWidth(
            Cmp1Box cmp1,
            int tileCount,
            int fullPlaneWidth,
            int rawWidth) {
        if (cmp1 != null && cmp1.tileWidth() > 0) {
            return Math.min(Math.max(1, (cmp1.tileWidth() + 1) / 2), fullPlaneWidth);
        }
        return tileCount > 1
                ? Math.max(1, (rawWidth / tileCount + 1) / 2)
                : fullPlaneWidth;
    }

    private static int resolveTilePlaneHeight(Cmp1Box cmp1, int fullPlaneHeight) {
        if (cmp1 != null && cmp1.tileHeight() > 0) {
            return Math.min(Math.max(1, (cmp1.tileHeight() + 1) / 2), fullPlaneHeight);
        }
        return fullPlaneHeight;
    }

    private static byte[] encodeRiceSymbols(int[] symbols, int k) {
        int bitCount = 0;
        for (int symbol : symbols) {
            bitCount = Math.addExact(bitCount, (symbol >>> k) + 1 + k);
        }
        byte[] encoded = new byte[(bitCount + 7) / 8];
        int bitIndex = 0;
        for (int symbol : symbols) {
            int quotient = symbol >>> k;
            for (int i = 0; i < quotient; i++) {
                bitIndex = writeBit(encoded, bitIndex, 1);
            }
            bitIndex = writeBit(encoded, bitIndex, 0);
            for (int shift = k - 1; shift >= 0; shift--) {
                bitIndex = writeBit(encoded, bitIndex, (symbol >>> shift) & 1);
            }
        }
        return encoded;
    }

    private static int writeBit(byte[] destination, int bitIndex, int value) {
        if (value != 0) {
            int byteIndex = bitIndex >>> 3;
            int shift = 7 - (bitIndex & 7);
            destination[byteIndex] |= (byte) (1 << shift);
        }
        return bitIndex + 1;
    }

    private record ParsedCrx(byte[] data, CrxHeader header) {
    }
}
