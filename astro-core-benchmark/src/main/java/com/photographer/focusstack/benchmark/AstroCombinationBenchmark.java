package com.photographer.focusstack.benchmark;

import com.photographer.focusstack.astro.AstroStackingService;
import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import com.photographer.focusstack.common.model.FrameTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class AstroCombinationBenchmark {
    @Param({"MEAN", "MEDIAN", "SIGMA_CLIPPED_MEAN"})
    public AstroStackingMode mode;
    @Param({"640x480", "1920x1080"})
    public String size;
    @Param({"4", "16"})
    public int frameCount;
    @Param({"RGB8", "GRAY16"})
    public String format;

    private final AstroStackingService service = new AstroStackingService();
    private final List<AstroStackingService.FrameInput> frames = new ArrayList<>();
    private AstroStackingConfig config;
    private int width;
    private int height;

    @Setup
    public void setup() {
        String[] dimensions = size.split("x");
        width = Integer.parseInt(dimensions[0]);
        height = Integer.parseInt(dimensions[1]);
        config = new AstroStackingConfig(mode, 2.0, 3, 3);
        boolean gray = format.equals("GRAY16");
        for (int index = 0; index < frameCount; index++) {
            BufferedImage image = new BufferedImage(width, height,
                    gray ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Repeating spatial background, bounded temporal noise, occasional bright transient.
                    int intensity = (x + y) % 180 + index % 3;
                    if ((x + 13 * y + index) % 97 == 0) {
                        intensity = 255;
                    }
                    if (gray) {
                        image.getRaster().setSample(x, y, 0, intensity * 257);
                    } else {
                        image.setRGB(x, y, intensity * 0x010101);
                    }
                }
            }
            frames.add(new AstroStackingService.FrameInput(image, FrameTransform.identity()));
        }
    }

    @Benchmark
    public BufferedImage stack() {
        return service.stack(frames, width, height, config);
    }
}
