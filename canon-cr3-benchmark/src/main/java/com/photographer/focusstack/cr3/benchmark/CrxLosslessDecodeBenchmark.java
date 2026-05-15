package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;

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
import org.openjdk.jmh.infra.Blackhole;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

/**
 * Hot-path benchmark of the CRX lossless entropy decoder
 * ({@link CrxLosslessRawDecoder#decode}). Container parsing and the bulk
 * read of the CRX RAW track bytes are amortised in {@link #setup()} so the
 * timed section is pure CPU on the Rice-Golomb / MED-prediction inner loop.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class CrxLosslessDecodeBenchmark {

    @Param({
            "sample.cr3",
            "sample-iso-high.cr3",
            "sample-wb-tungsten.cr3",
            "sample-wide.cr3",
            "sample-tele.cr3",
            "eosr-iso-low.cr3",
            "eosr-iso-mid.cr3",
            "eosr-iso-high.cr3"
    })
    public String sample;

    private byte[] crxData;
    private int width;
    private int height;
    private int bitDepth;
    private CrxHeader.CodecVersion codecVersion;
    private Cmp1Box cmp1;

    @Setup
    public void setup() throws Exception {
        byte[] bytes = SampleFiles.readAllBytes(sample);
        Cr3Container container;
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            container = Cr3Parser.parse(input);
        }
        Cr3Track rawTrack = null;
        for (Cr3Track t : container.tracks()) {
            if (t.type() == Cr3Track.TrackType.CRX_BIG
                    || t.type() == Cr3Track.TrackType.CRX_RAW) {
                rawTrack = t;
                break;
            }
        }
        if (rawTrack == null) {
            throw new IllegalStateException("No CRX RAW track in " + sample);
        }
        cmp1 = rawTrack.cmp1();
        width = cmp1 != null && cmp1.imageWidth() > 0 ? cmp1.imageWidth() : rawTrack.width();
        height = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : rawTrack.height();
        bitDepth = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
        codecVersion = container.cncvBox() != null
                ? CrxHeader.detectVersion(container.cncvBox().versionString())
                : CrxHeader.CodecVersion.V1;

        // Pull the raw CRX track bytes out of the on-heap copy of the file
        long off = rawTrack.dataOffset();
        long size = rawTrack.dataSize();
        if (off < 0 || size <= 0 || off + size > bytes.length) {
            throw new IllegalStateException("Invalid CRX track range in " + sample);
        }
        crxData = new byte[(int) size];
        System.arraycopy(bytes, (int) off, crxData, 0, (int) size);
    }

    @Benchmark
    public void decode(Blackhole bh) throws Exception {
        bh.consume(CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth, codecVersion, cmp1));
    }
}
