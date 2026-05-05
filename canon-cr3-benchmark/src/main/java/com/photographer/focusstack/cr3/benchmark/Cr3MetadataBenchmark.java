package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;

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
 * Measures EXIF / MakerNote extraction via {@link Cr3MetadataReader#read}.
 * Container parsing is done once in {@link #setup()} and excluded from the
 * timed section so the measurement reflects metadata reader CPU only.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class Cr3MetadataBenchmark {

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
    private Cr3Container container;

    @Setup
    public void setup() throws Exception {
        bytes = SampleFiles.readAllBytes(sample);
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            container = Cr3Parser.parse(input);
        }
    }

    @Benchmark
    public void readMetadata(Blackhole bh) throws Exception {
        try (MemoryCacheImageInputStream input =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            bh.consume(Cr3MetadataReader.read(input, container));
        }
    }
}
