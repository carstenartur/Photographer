package com.photographer.focusstack.benchmark;

import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
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

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class BayerDemosaicerBenchmark {

    @Param({"1024x768", "5632x3710"})
    public String size;

    private Cr3RawImage rawImage;

    @Setup
    public void setup() {
        int width = Integer.parseInt(size.substring(0, size.indexOf('x')));
        int height = Integer.parseInt(size.substring(size.indexOf('x') + 1));
        int[] data = new int[width * height];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i * 37) & 0x3FFF;
        }
        rawImage = new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);
    }

    @Benchmark
    public void demosaic(Blackhole bh) {
        bh.consume(BayerDemosaicer.demosaic(rawImage, 16383));
    }
}
