package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;

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

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end benchmark of the linear RAW pipeline:
 * {@code Cr3Decoder.decode(Cr3DecodeOptions.rawLinear())} —
 * CRX entropy → black-level → 16-bit grayscale BufferedImage. No demosaic,
 * no white-balance, no colour matrix, no sRGB. Useful as a counter-point to
 * {@link Cr3RawProcessedBenchmark} to attribute speedups between the entropy
 * decoder and the post-processing pipeline.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@State(Scope.Benchmark)
public class Cr3RawLinearBenchmark {

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

    private Path file;

    @Setup
    public void setup() {
        file = SampleFiles.resolve(sample);
    }

    @Benchmark
    public void decodeRawLinear(Blackhole bh) throws Exception {
        Cr3Decoder decoder = new Cr3Decoder();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            decoder.setInput(input);
            bh.consume(decoder.decode(Cr3DecodeOptions.rawLinear()));
        }
    }
}
