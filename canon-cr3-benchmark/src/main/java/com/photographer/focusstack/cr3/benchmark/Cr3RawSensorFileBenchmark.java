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

import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Compares the two core file inputs from open through unsigned Bayer output and close. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@State(Scope.Thread)
public class Cr3RawSensorFileBenchmark {
    @Param({"sample.cr3", "eosr-iso-low.cr3", "research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3"})
    public String sample;

    /** Zero uses the decoder's automatic worker count. */
    @Param({"0"})
    public int workers;

    private Path file;
    private Cr3DecodeOptions options;

    @Setup
    public void setup() {
        file = SampleFiles.resolve(sample);
        options = Cr3DecodeOptions.rawSensor().maxWorkers(workers);
    }

    @Benchmark
    public BufferedImage decodeFromPath() throws Exception {
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(file);
            return decoder.decode(options);
        }
    }

    @Benchmark
    public BufferedImage decodeFromStream() throws Exception {
        try (var stream = new FileImageInputStream(file.toFile());
             var decoder = new Cr3Decoder()) {
            decoder.setInput(stream);
            return decoder.decode(options);
        }
    }
}
