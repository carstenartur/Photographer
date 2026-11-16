package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.decode.Cr3PreparedRaw;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.processing.ColorMatrixProcessor;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Exact pixel checks for the experimental kernels, with optional device execution. */
public final class TornadoRawStackParity {
    private TornadoRawStackParity() {}

    public static void main(String[] args) throws Exception {
        boolean gpu = args.length > 0 && args[0].equals("--gpu");
        int checked = verifySynthetic(gpu);
        for (int i = gpu ? 1 : 0; i < args.length; i++) {
            verifyFixture(Path.of(args[i]), gpu);
            checked++;
        }
        System.out.println("Exact pixel parity on " + checked + " cases ("
                + (gpu ? TornadoRawStack.gpuDevice().getPhysicalDevice().getDeviceName() : "Java kernel methods") + ")");
    }

    private static int verifySynthetic(boolean gpu) {
        int checked = 0;
        // Small shapes also exercise demosaic neighborhoods with zero valid samples.
        int[][] dimensions = {{13, 9}, {1, 5}, {5, 1}, {1, 1}, {2, 2}};
        for (int[] size : dimensions) {
            Cr3PreparedRaw[] stack = new Cr3PreparedRaw[8];
            for (BayerPattern pattern : BayerPattern.values()) {
                for (int orientation = 1; orientation <= 8; orientation++) {
                    Cr3PreparedRaw frame = synthetic(size[0], size[1], orientation, pattern);
                    checkPixels(develop(frame), mergeKernels(new Cr3PreparedRaw[]{frame}, gpu),
                            "single " + pattern + " orientation " + orientation);
                    if (pattern.ordinal() == (orientation - 1) % BayerPattern.values().length) {
                        stack[orientation - 1] = frame;
                    }
                    checked++;
                }
            }
            var cpu = new ImageJStackMerger.ColorAccumulator(develop(stack[0]));
            for (Cr3PreparedRaw frame : stack) {
                cpu.add(develop(frame));
            }
            checkPixels(cpu.image(), mergeKernels(stack, gpu), "eight-frame orientation stack");
            checked++;
        }

        // A 1x1 image has a zero Laplacian at its only pixel regardless of the pixel's color.
        Cr3PreparedRaw red = synthetic(1, 1, 1, BayerPattern.RGGB);
        Cr3PreparedRaw blue = synthetic(1, 1, 1, BayerPattern.BGGR);
        red.image().data()[0] = red.image().maxValue();
        blue.image().data()[0] = blue.image().maxValue();
        BufferedImage first = develop(red);
        if (first.getRGB(0, 0) == develop(blue).getRGB(0, 0)) {
            throw new AssertionError("Tie test requires different RGB values");
        }
        checkPixels(first, mergeKernels(new Cr3PreparedRaw[]{red, blue}, gpu), "first wins equal score");
        checkPixels(develop(blue), mergeKernels(new Cr3PreparedRaw[]{blue, red}, gpu), "tie in reverse order");
        return checked + 2;
    }

    private static Cr3PreparedRaw synthetic(int outWidth, int outHeight, int orientation, BayerPattern pattern) {
        int width = orientation >= 5 ? outHeight : outWidth;
        int height = orientation >= 5 ? outWidth : outHeight;
        int depth = orientation % 2 == 0 ? 14 : 16;
        int maxValue = (1 << depth) - 1;
        var random = new SplittableRandom(809L + orientation * 41L + pattern.ordinal());
        int[] samples = new int[width * height];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = i % 11 == 0 ? 10 : random.nextInt(maxValue + 1);
        }
        int[][] levelRows = {
                {510, 512, 514, 516}, {511, 513, 515, 517},
                {501, 533, 505, 527}, {502, 504, 506, 508}
        };
        BlackLevel black = switch (orientation % 3) {
            case 0 -> BlackLevel.global(512);
            case 1 -> BlackLevel.perChannel(495, 522, 509, 580);
            default -> BlackLevel.perRow(levelRows);
        };
        return new Cr3PreparedRaw(new Cr3RawImage(width, height, samples, depth, pattern), black,
                orientation & 1, (orientation >> 1) & 1,
                new double[]{1.9814981, 1.0001, 1.5670901}, maxValue - 495,
                new double[][]{{1.67, -0.64, -0.03}, {-0.13, 1.45, -0.32}, {0.01, -0.29, 1.28}},
                orientation);
    }

    private static void verifyFixture(Path path, boolean gpu) throws Exception {
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(path);
            Cr3PreparedRaw prepared = decoder.prepareRawDevelopment(1);
            BufferedImage expected = decoder.decode(Cr3DecodeOptions.rawProcessed().maxWorkers(1));
            checkPixels(expected, mergeKernels(new Cr3PreparedRaw[]{prepared}, gpu), path.toString());
        }
    }

    private static BufferedImage develop(Cr3PreparedRaw frame) {
        Cr3RawImage raw = frame.image();
        var image = new Cr3RawImage(raw.width(), raw.height(), raw.data().clone(), raw.bitDepth(), raw.bayerPattern());
        WhiteBalanceProcessor.process(image, frame.whiteBalance(), frame.blackLevel(), frame.sensorX(), frame.sensorY(), 1);
        BufferedImage rgb = BayerDemosaicer.demosaicInPlace(image, frame.whiteLevel());
        ColorMatrixProcessor.process(rgb, frame.colorMatrix());
        SrgbConverter.process(rgb);
        return ExifOrientationTransformer.applyToOwnedImage(rgb, frame.orientation());
    }

    private static BufferedImage mergeKernels(Cr3PreparedRaw[] frames, boolean gpu) {
        if (gpu) {
            try (var stack = new TornadoRawStack(frames[0])) {
                for (Cr3PreparedRaw frame : frames) {
                    stack.add(frame);
                }
                return stack.image();
            }
        }
        try (var buffers = new TornadoRawStack.Buffers(frames[0])) {
            for (int i = 0; i < frames.length; i++) {
                buffers.stage(frames[i], i == 0);
                TornadoRawStackKernels.correct(buffers.raw, buffers.settings, buffers.black, buffers.color);
                TornadoRawStackKernels.develop(buffers.raw, buffers.settings, buffers.color, buffers.gamma,
                        buffers.rgb, buffers.luminance);
                TornadoRawStackKernels.select(buffers.rgb, buffers.luminance, buffers.settings,
                        buffers.winner, buffers.bestScore);
            }
            return buffers.image();
        }
    }

    private static void checkPixels(BufferedImage expected, BufferedImage actual, String label) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        if (actual.getWidth() != width || actual.getHeight() != height) {
            throw new AssertionError("Different image dimensions in " + label);
        }
        int[] expectedRow = new int[width];
        int[] actualRow = new int[width];
        for (int y = 0; y < height; y++) {
            expected.getRGB(0, y, width, 1, expectedRow, 0, width);
            actual.getRGB(0, y, width, 1, actualRow, 0, width);
            int x = Arrays.mismatch(expectedRow, actualRow);
            if (x >= 0) {
                throw new AssertionError("Pixel (%d,%d) in %s was %06x, expected %06x".formatted(
                        x, y, label, actualRow[x] & 0xffffff, expectedRow[x] & 0xffffff));
            }
        }
    }
}
