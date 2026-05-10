package com.photographer.focusstack.benchmark;

import com.photographer.focusstack.cr3.processing.SrgbConverter;
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

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SrgbConverterBenchmark {

    @Param({"1024x768", "5632x3710"})
    public String size;

    private int width;
    private int height;
    private int[] sourcePixels;

    @Setup
    public void setup() {
        width = Integer.parseInt(size.substring(0, size.indexOf('x')));
        height = Integer.parseInt(size.substring(size.indexOf('x') + 1));
        sourcePixels = new int[width * height];
        for (int i = 0; i < sourcePixels.length; i++) {
            int v = i & 0xFF;
            sourcePixels[i] = (v << 16) | (v << 8) | v;
        }
    }

    @Benchmark
    public void convert(Blackhole bh) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(sourcePixels, 0, target, 0, sourcePixels.length);
        SrgbConverter.process(image);
        bh.consume(target[0]);
    }
}
