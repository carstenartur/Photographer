package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.container.Cr3Parser;

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
 * Measures pure ISO-BMFF box parsing — {@link Cr3Parser#parse} — over the eight
 * sample CR3 fixtures. File bytes are pre-loaded in {@link #setup()} so the
 * measurement reflects parser CPU only, not disk I/O.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class Cr3BoxParseBenchmark {

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

    private byte[] bytes;

    @Setup
    public void setup() throws Exception {
        bytes = SampleFiles.readAllBytes(sample);
    }

    @Benchmark
    public void parse(Blackhole bh) throws Exception {
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            bh.consume(Cr3Parser.parse(input));
        }
    }
}
