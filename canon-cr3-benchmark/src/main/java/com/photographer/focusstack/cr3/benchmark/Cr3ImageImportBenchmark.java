package com.photographer.focusstack.cr3.benchmark;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;

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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Measures complete consumer-facing CR3 imports through the ImageIO SPI.
 *
 * <p>Each invocation includes opening the file, ImageIO reader discovery,
 * container and metadata parsing, decoding, processing and resource cleanup.
 * This complements the isolated decoder and processing-stage benchmarks with
 * the latency an application actually experiences.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class Cr3ImageImportBenchmark {

    @Param({"sample.cr3", "eosr-iso-low.cr3"})
    public String sample;

    private Path file;

    @Setup
    public void setup() {
        ImageIO.scanForPlugins();
        file = SampleFiles.resolve(sample);
    }

    /** Full CR3 file to processed, display-ready RGB image via ImageIO. */
    @Benchmark
    public BufferedImage importProcessedRawViaImageIo() throws IOException {
        CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
        param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
        return read(param);
    }

    /** Raw Bayer codes, including the full sensor margins, through the public reader. */
    @Benchmark
    public BufferedImage importSensorViaImageIo() throws IOException {
        CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
        param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
        return read(param);
    }

    /** A Bayer patch near the start of a tile plane. */
    @Benchmark
    public BufferedImage importSensorRegionViaImageIo() throws IOException {
        return readRegion(Cr3DecodeOptions.rawSensor());
    }

    /** A developed patch in the output orientation. */
    @Benchmark
    public BufferedImage importProcessedRegionViaImageIo() throws IOException {
        return readRegion(Cr3DecodeOptions.rawProcessed());
    }

    private BufferedImage readRegion(Cr3DecodeOptions options) throws IOException {
        CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
        param.setDecodeOptions(options);
        param.setSourceRegion(new Rectangle(32, 32, 64, 64));
        return read(param);
    }

    /** Full-resolution color development through the standard ImageIO file call. */
    @Benchmark
    public BufferedImage importDefaultRawViaImageIo() throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("No ImageIO reader found for " + file);
        }
        return image;
    }

    /** Complete fast-preview import path, including parsing and JPEG extraction. */
    @Benchmark
    public BufferedImage importPreviewViaImageIo() throws IOException {
        CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
        param.setPreviewOnly(true);
        return read(param);
    }

    private BufferedImage read(CanonCr3ImageReadParam param) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                throw new IOException("Could not create ImageInputStream for " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No ImageIO reader found for " + file);
            }
            ImageReader reader = readers.next();
            try {
                // Reader-provider probes are expected to restore the stream, but
                // seeking explicitly makes the end-to-end benchmark independent
                // of other installed ImageIO providers and their probe order.
                input.seek(0);
                reader.setInput(input, false, false);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }
}
