package com.photographer.focusstack.benchmark;

import com.photographer.focusstack.astro.AstroStackingService;
import com.photographer.focusstack.common.model.FrameTransform;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class AstroStackingBenchmark {

    @Param({"1024x768", "5632x3710"})
    public String size;

    private int width;
    private int height;
    private AstroStackingService stackingService;
    private List<AstroStackingService.FrameInput> singleFrame;
    private List<AstroStackingService.FrameInput> eightFrames;

    @Setup
    public void setup() {
        width = Integer.parseInt(size.substring(0, size.indexOf('x')));
        height = Integer.parseInt(size.substring(size.indexOf('x') + 1));
        stackingService = new AstroStackingService();
        singleFrame = List.of(new AstroStackingService.FrameInput(createImage(0), FrameTransform.identity()));

        List<AstroStackingService.FrameInput> frames = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            frames.add(new AstroStackingService.FrameInput(createImage(i), new FrameTransform(0, i % 2, i / 2 % 2, null)));
        }
        eightFrames = List.copyOf(frames);
    }

    @Benchmark
    public void singleFrameStack(Blackhole bh) {
        bh.consume(stackingService.stack(singleFrame, width, height));
    }

    @Benchmark
    public void eightFrameStack(Blackhole bh) {
        bh.consume(stackingService.stack(eightFrames, width, height));
    }

    private BufferedImage createImage(int salt) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            int v = (i + (salt * 97)) & 0xFF;
            pixels[i] = (v << 16) | (v << 8) | v;
        }
        return image;
    }
}
