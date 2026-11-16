package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Measures the Photographer file-backed stack from CR3 originals through PNG encoding. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class RawFocusStackBenchmark {

    @Param({"eosr-iso-low.cr3"})
    public String series;

    private List<OriginalFrame> originals;
    private StackFileProcessor processor;

    public static void main(String[] args) throws Exception {
        var options = new OptionsBuilder()
                .include(System.getProperty("focus.stack.pattern"))
                .param("series", System.getProperty("focus.stack.series"))
                .result(System.getProperty("focus.stack.result"))
                .resultFormat(ResultFormatType.JSON)
                .addProfiler(GCProfiler.class)
                .shouldFailOnError(true);
        new Runner(options.build()).run();
    }

    @Setup
    public void setup() throws IOException {
        originals = originals(series);
        processor = new StackFileProcessor(new RawDecodeBudget(0, Long.getLong("focus.stack.maxBytes", 0L)));
    }

    @Benchmark
    public byte[] mergeCpu() throws IOException {
        return mergeCpu(processor, originals);
    }

    static byte[] mergeCpu(StackFileProcessor processor, List<OriginalFrame> originals) throws IOException {
        var result = processor.merge(originals);
        if (result.source() != StackFileProcessor.Source.RAW || result.frameCount() != originals.size()) {
            throw new IOException("Benchmark requires full RAW development of every original");
        }
        return result.png();
    }

    static List<OriginalFrame> originals(String series) throws IOException {
        Path samples = Path.of(System.getProperty("focus.stack.samples", "."));
        Path input = samples.resolve(series);
        if (Files.isRegularFile(input) && isCr3(input)) {
            // A stable reference workload for the history when a capture series is unavailable.
            return Collections.nCopies(3, new OriginalFrame(input.getFileName().toString(), input));
        }
        try (var files = Files.list(input)) {
            var originals = files.filter(Files::isRegularFile).filter(RawFocusStackBenchmark::isCr3)
                    .sorted().map(file -> new OriginalFrame(file.getFileName().toString(), file)).toList();
            if (originals.size() < 2) {
                throw new IOException("A RAW focus series needs at least two CR3 files in " + input);
            }
            return originals;
        }
    }

    private static boolean isCr3(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3");
    }
}
