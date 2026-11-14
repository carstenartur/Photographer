package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.benchmark.SampleFiles;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.imageio.stream.FileImageInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Measures the structural stages that make up the CRX lossless RAW decoder.
 *
 * <p>The benchmarks use real CRX payloads for header parsing, plane decoding,
 * prediction and plane assembly. Per-symbol entropy and adaptation operations
 * are isolated in {@link CrxDecoderMicroStagesBenchmark} so their invocation
 * setup cannot affect the fixture-backed series.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CrxDecoderStagesBenchmark {

    /**
     * One PowerShot and one EOS fixture provide different image geometries while
     * keeping the longitudinal full-suite runtime bounded.
     */
    @Param({"sample.cr3", "eosr-iso-low.cr3"})
    public String sample;

    private byte[] crxPayload;
    private CrxHeader.CodecVersion codecVersion;
    private int directoryBytes;

    private int subbandOffset;
    private int subbandLength;
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

    @Setup
    public void setup() throws Exception {
        try (var input = new FileImageInputStream(SampleFiles.resolve(sample).toFile())) {
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No CRX RAW track in " + sample));

            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 == null || !cmp1.isLossless()) {
                throw new IllegalStateException("Lossless benchmark requires a lossless CMP1 track: " + sample);
            }
            codecVersion = container.cncvBox() == null ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());
            bayerWidth = cmp1.imageWidth();
            bayerHeight = cmp1.imageHeight();
            directoryBytes = cmp1.mdatHeaderSize();
            planeWidth = Math.ceilDiv(bayerWidth, 2);
            planeHeight = Math.ceilDiv(bayerHeight, 2);
            tilePlaneWidth = Math.ceilDiv(cmp1.tileWidth(), 2);
            tilePlaneHeight = Math.ceilDiv(cmp1.tileHeight(), 2);

            crxPayload = new byte[Math.toIntExact(rawTrack.dataSize())];
            input.seek(rawTrack.dataOffset());
            input.readFully(crxPayload);
        }
        var header = CrxHeader.parseDirectory(crxPayload, directoryBytes, codecVersion);
        var band = header.tiles().getFirst().planes().getFirst().subbands().getFirst();
        subbandOffset = Math.toIntExact(band.dataOffset());
        subbandLength = Math.toIntExact(band.dataSize());
        decodedTile = new int[Math.multiplyExact(tilePlaneWidth, tilePlaneHeight)];
        decodeNativeFirstPlane();

        copiedPlane = new int[Math.multiplyExact(planeWidth, tilePlaneHeight)];
        positionalPlanes = new int[4][Math.multiplyExact(planeWidth, planeHeight)];
        for (int plane = 0; plane < positionalPlanes.length; plane++) {
            int[] destination = positionalPlanes[plane];
            for (int i = 0; i < destination.length; i++) {
                destination[i] = decodedTile[i % decodedTile.length] + plane;
            }
        }
        interleavedBayer = new int[Math.multiplyExact(bayerWidth, bayerHeight)];
    }

    @Benchmark
    public CrxHeader parseHeader() throws IOException {
        return CrxHeader.parseDirectory(crxPayload, directoryBytes, codecVersion);
    }

    @Benchmark
    public int[] decodeNativeFirstPlane() throws IOException {
        CrxNativeLosslessPlane.decodeSensor(crxPayload, subbandOffset, subbandLength,
                tilePlaneWidth, tilePlaneHeight, 0, 0,
                (x, y, row) -> System.arraycopy(row, 0, decodedTile, (y / 2) * tilePlaneWidth, row.length));
        return decodedTile;
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
}
