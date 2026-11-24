package com.photographer.focusstack.cr3.imageio;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import com.photographer.focusstack.cr3.imageio.ImageQualityHelper.ReferenceWhite;

/** Compare a physical chart's independently supplied CIELAB values with the public RGB16 import. */
public final class Cr3ColorChartProbe {
    private Cr3ColorChartProbe() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: Cr3ColorChartProbe original.cr3 D50|D65 reference.tsv regions.tsv");
        }
        var white = ReferenceWhite.valueOf(args[1]);
        var reference = readTable(Path.of(args[2]), "patch\tL\ta\tb");
        var regions = readTable(Path.of(args[3]), "patch\tx\ty\twidth\theight");
        var file = Path.of(args[0]).toFile();
        try (var input = ImageIO.createImageInputStream(file)) {
            if (input == null) {
                throw new IOException("Cannot open " + file);
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No ImageIO reader accepts " + file);
            }
            var first = readers.next();
            try {
                if (!(first instanceof CanonCr3ImageReader)) {
                    throw new IOException("Expected Photographer's CR3 reader for " + file + ", found " + first.getClass());
                }
            } finally {
                first.dispose();
            }
        }
        var image = ImageIO.read(file);
        evaluate(image, white, reference, regions, System.out);
    }

    /** IDs preserve reference order, while a separately framed capture can give its regions in any order. */
    static Map<String, String[]> readTable(Path path, String header) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !lines.getFirst().equals(header)) {
            throw new IOException(path + " requires header '" + header + "' and at least one patch");
        }
        int columns = header.split("\t").length;
        Map<String, String[]> entries = new LinkedHashMap<>();
        for (int row = 1; row < lines.size(); row++) {
            String[] fields = lines.get(row).split("\t", -1);
            if (fields.length != columns || fields[0].isBlank() || fields[0].startsWith("#")) {
                throw new IOException(path + ": invalid patch at line " + (row + 1));
            }
            if (entries.putIfAbsent(fields[0], fields) != null) {
                throw new IOException(path + ": duplicate patch " + fields[0]);
            }
        }
        return entries;
    }

    static void evaluate(BufferedImage image, ReferenceWhite white, Map<String, String[]> reference,
            Map<String, String[]> regions, PrintStream report) {
        if (image == null || !image.getColorModel().getColorSpace().isCS_sRGB()
                || image.getRaster().getTransferType() != DataBuffer.TYPE_USHORT
                || !Arrays.equals(image.getSampleModel().getSampleSize(), new int[]{16, 16, 16})) {
            throw new IllegalArgumentException("Expected a developed three-channel, unsigned 16-bit sRGB image");
        }
        if (!reference.keySet().equals(regions.keySet()) || reference.isEmpty()) {
            throw new IllegalArgumentException("Reference and region files must describe the same nonempty patch set");
        }
        var measured = new ArrayList<Patch>();
        for (var entry : reference.entrySet()) {
            String id = entry.getKey();
            String[] values = entry.getValue();
            String[] box = regions.get(id);
            var roi = new Rectangle(Integer.parseInt(box[1]), Integer.parseInt(box[2]),
                    Integer.parseInt(box[3]), Integer.parseInt(box[4]));
            if (roi.isEmpty() || !image.getRaster().getBounds().contains(roi)) {
                throw new IllegalArgumentException("Region for " + id + " is empty or outside the image: " + roi);
            }
            double[] target = {Double.parseDouble(values[1]), Double.parseDouble(values[2]),
                    Double.parseDouble(values[3])};
            if (Arrays.stream(target).anyMatch(value -> !Double.isFinite(value)) || target[0] < 0 || target[0] > 100) {
                throw new IllegalArgumentException("Invalid reference Lab for " + id);
            }
            measured.add(measure(image.getRaster(), white, id, roi, target));
        }
        report.printf(Locale.ROOT, "# reference_white=%s observer=2deg width=%d height=%d%n",
                white, image.getWidth(), image.getHeight());
        report.println("patch\tref_L\tref_a\tref_b\tmeas_L\tmeas_a\tmeas_b\tdelta_L\tdelta_a\tdelta_b"
                + "\tdelta_E00\tpixels\tany_channel_zero\tany_channel_max");
        for (Patch patch : measured) {
            double[] ref = patch.reference(), lab = patch.lab();
            report.printf(Locale.ROOT, "%s\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f"
                    + "\t%.4f\t%d\t%d\t%d%n", patch.id(), ref[0], ref[1], ref[2], lab[0], lab[1], lab[2],
                    lab[0] - ref[0], lab[1] - ref[1], lab[2] - ref[2], patch.deltaE(),
                    patch.pixels(), patch.zeroPixels(), patch.maxPixels());
        }
        double[] sorted = measured.stream().mapToDouble(Patch::deltaE).sorted().toArray();
        int last = sorted.length - 1;
        report.printf(Locale.ROOT, "# patches=%d mean_delta_E00=%.4f median_delta_E00=%.4f max_delta_E00=%.4f%n",
                sorted.length, Arrays.stream(sorted).average().orElseThrow(),
                (sorted[last / 2] + sorted[sorted.length / 2]) / 2, sorted[last]);
    }

    record Patch(String id, double[] reference, double[] lab, double deltaE, long pixels,
            long zeroPixels, long maxPixels) {}

    private static Patch measure(Raster raster, ReferenceWhite white, String id, Rectangle roi, double[] reference) {
        int[] pixel = new int[3];
        double[] sum = new double[3];
        long zero = 0, max = 0;
        long count = (long) roi.width * roi.height;
        for (int y = roi.y; y < roi.y + roi.height; y++) {
            for (int x = roi.x; x < roi.x + roi.width; x++) {
                raster.getPixel(x, y, pixel);
                for (int c = 0; c < 3; c++) {
                    sum[c] += ImageQualityHelper.srgbToLinear(pixel[c] / 65535.0);
                }
                if (pixel[0] == 0 || pixel[1] == 0 || pixel[2] == 0) {
                    zero++;
                }
                if (pixel[0] == 65535 || pixel[1] == 65535 || pixel[2] == 65535) {
                    max++;
                }
            }
        }
        double[] lab = ImageQualityHelper.linearSrgbToLab(sum[0] / count, sum[1] / count, sum[2] / count, white);
        return new Patch(id, reference, lab, ImageQualityHelper.deltaE2000(reference, lab), count, zero, max);
    }
}
