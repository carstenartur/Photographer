package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import com.photographer.focusstack.cr3.reference.SpatialReferenceMetrics;

import java.util.ArrayList;
import java.util.List;

final class KAdaptationPanelMetrics {
    record Metric(double mae, double rmse, double pearson) {}
    record Result(Metric global, double opticalBlackMae, double boundaryMae,
                  List<Metric> planes, double saturation,
                  double completion, boolean exhausted) {}

    private KAdaptationPanelMetrics() {}

    static Result evaluate(ReferenceImage reference,
                           KAdaptationPanelDecode.ImageResult actual,
                           Cmp1Box cmp1, Iad1Box iad1) {
        Metric global = metric(ReferenceMetrics.compare(reference.samples(), actual.pixels()));
        List<Metric> planes = new ArrayList<>(4);
        for (int plane = 0; plane < 4; plane++) {
            planes.add(plane(reference.samples(), actual.pixels(),
                    reference.width(), reference.height(), plane));
        }
        double opticalBlack = opticalBlack(reference.samples(), actual.pixels(),
                reference.width(), reference.height(), iad1);
        double boundary = cmp1.tileWidth() <= 0 || cmp1.tileHeight() <= 0
                ? Double.NaN
                : SpatialReferenceMetrics.tileBoundaryMae(
                        reference.samples(), actual.pixels(),
                        reference.width(), reference.height(),
                        cmp1.tileWidth(), cmp1.tileHeight());
        return new Result(global, opticalBlack, boundary, planes,
                saturation(actual.pixels(), cmp1.bitsPerSample()),
                actual.completion(), actual.exhausted());
    }

    private static Metric plane(int[] expected, int[] actual,
                                int width, int height, int plane) {
        int rowOffset = plane / 2;
        int columnOffset = plane % 2;
        int count = ((height - rowOffset + 1) / 2)
                * ((width - columnOffset + 1) / 2);
        int[] expectedPlane = new int[count];
        int[] actualPlane = new int[count];
        int index = 0;
        for (int y = rowOffset; y < height; y += 2) {
            for (int x = columnOffset; x < width; x += 2) {
                expectedPlane[index] = expected[y * width + x];
                actualPlane[index++] = actual[y * width + x];
            }
        }
        return metric(ReferenceMetrics.compare(expectedPlane, actualPlane));
    }

    private static double opticalBlack(int[] expected, int[] actual,
                                       int width, int height, Iad1Box iad1) {
        if (iad1 == null) {
            return Double.NaN;
        }
        List<SpatialReferenceMetrics.Rect> regions = List.of(
                        SpatialReferenceMetrics.Rect.clip(iad1.leftObLeft(), iad1.leftObTop(),
                                iad1.leftObRight(), iad1.leftObBottom(), width, height),
                        SpatialReferenceMetrics.Rect.clip(iad1.topObLeft(), iad1.topObTop(),
                                iad1.topObRight(), iad1.topObBottom(), width, height))
                .stream().filter(region -> !region.isEmpty()).toList();
        return regions.isEmpty() ? Double.NaN
                : SpatialReferenceMetrics.compareRegions(
                        expected, actual, width, height, regions).mae();
    }

    private static double saturation(int[] pixels, int bitDepth) {
        int max = (1 << bitDepth) - 1;
        long count = 0;
        for (int pixel : pixels) {
            if (pixel == max) {
                count++;
            }
        }
        return pixels.length == 0 ? 0.0 : (double) count / pixels.length;
    }

    private static Metric metric(ReferenceMetrics.Metrics value) {
        return new Metric(value.mae(), value.rmse(), value.pearson());
    }
}
